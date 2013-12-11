/*
 * Copyright 2010-2013 JetBrains s.r.o.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.jetbrains.jet.plugin.refactoring.rename;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonObject;
import com.intellij.openapi.util.io.FileUtil;
import com.intellij.refactoring.MultiFileTestCase;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.jet.InTextDirectivesUtils;
import org.jetbrains.jet.lang.resolve.name.FqName;
import org.jetbrains.jet.lang.resolve.name.FqNameUnsafe;
import org.jetbrains.jet.plugin.PluginTestCaseBase;
import org.jetbrains.jet.utils.UtilsPackage;
import org.junit.Assert;

import java.io.File;
import java.io.IOException;

@SuppressWarnings("JUnitTestCaseWithNoTests")
public class AbstractPortTest extends MultiFileTestCase {
    private enum RenameType {
        JAVA_CLASS,
        JAVA_METHOD,
        KOTLIN_CLASS,
        KOTLIN_FUNCTION,
        KOTLIN_PROPERTY,
    }

    @SuppressWarnings("MethodMayBeStatic")
    public void doTest(String path) {
        try {
            String fileText = FileUtil.loadFile(new File(path));
            String renameDirective = InTextDirectivesUtils.findStringWithPrefixes(fileText, "// RENAME:");

            if (renameDirective == null) return;

            String[] strings = renameDirective.split("->");
            Assert.assertTrue("'// RENAME:' directive should have at least AbstractRenameTest.RenameType parameter", strings.length > 0);

            String hintDirective = InTextDirectivesUtils.findStringWithPrefixes(fileText, "// HINT:");

            String mainFile = InTextDirectivesUtils.findStringWithPrefixes(fileText, "// FILE:");

            String renameTypeStr = strings[0];
            RenameType type = RenameType.valueOf(renameTypeStr);

            FqNameUnsafe fqNameUnsafe = new FqNameUnsafe(strings[1]);

            switch (type) {
                case JAVA_CLASS:
                    renameJavaClassTest(path, renameTypeStr, fqNameUnsafe.asString(), strings[2], hintDirective);
                    break;
                case JAVA_METHOD:
                    renameJavaMethodTest(path, renameTypeStr, fqNameUnsafe.asString(), strings[2], strings[3], hintDirective);
                    break;
                case KOTLIN_CLASS:
                    renameKotlinClassTest(path, renameTypeStr, fqNameUnsafe.toSafe(), strings[2], mainFile, hintDirective);
                    break;
                case KOTLIN_FUNCTION:
                    renameKotlinFunctionTest(path, renameTypeStr, fqNameUnsafe.parent().toSafe(), fqNameUnsafe.shortName().asString(), strings[2], mainFile, hintDirective);
                    break;
                case KOTLIN_PROPERTY:
                    renameKotlinPropertyTest(path, renameTypeStr, fqNameUnsafe.parent().toSafe(), fqNameUnsafe.shortName().asString(), strings[2], mainFile, hintDirective);
                    break;
            }
        }
        catch (Exception e) {
            throw UtilsPackage.rethrow(e);
        }
    }

    private static void renameJavaClassTest(String path, String renameTypeStr, String qClassName, String newName, String hintDirective) {
        writeJson(path, renameTypeStr, hintDirective, qClassName, null, null, newName, null);
    }

    private static void renameJavaMethodTest(
            String path,
            String renameTypeStr,
            String className,
            String methodSignature,
            String newName,
            String hintDirective
    ) {
        writeJson(path, renameTypeStr, hintDirective, className, methodSignature, null, newName, null);
    }

    private static void renameKotlinFunctionTest(
            String path,
            String renameTypeStr,
            FqName qClassName,
            String oldMethodName,
            String newMethodName,
            String mainFile,
            String hintDirective
    ) throws Exception {
        writeJson(path, renameTypeStr, hintDirective, qClassName.asString(), null, oldMethodName, newMethodName, mainFile);
    }

    private static void renameKotlinPropertyTest(
            String path,
            String renameTypeStr,
            FqName qClassName,
            String oldPropertyName,
            String newPropertyName,
            String mainFile,
            String hintDirective
    ) throws Exception {
        writeJson(path, renameTypeStr, hintDirective, qClassName.asString(), null, oldPropertyName, newPropertyName, mainFile);
    }

    private static void renameKotlinClassTest(
            String path,
            String renameTypeStr,
            FqName qClassName,
            String newName,
            String mainFile,
            String hintDirective
    ) throws Exception {
        writeJson(path, renameTypeStr, hintDirective, qClassName.asString(), null, null, newName, mainFile);
    }

    private static void writeJson(
            String path,
            String type,
            String hint,
            String classFQN,
            String signature,
            String oldName,
            String newName,
            String mainFile
    ) {
        JsonObject object = new JsonObject();
        object.addProperty("type", type);
        object.addProperty("hint", hint);
        object.addProperty("classFQN", classFQN);

        if (signature != null) {
            object.addProperty("methodSignature", signature);
        }

        if (oldName != null) {
            object.addProperty("oldName", oldName);
        }

        object.addProperty("newName", newName);

        if (mainFile != null) {
            object.addProperty("mainFile", mainFile);
        }

        Gson gson = new GsonBuilder().setPrettyPrinting().create();
        String json = gson.toJson(object);

        try {
            FileUtil.writeToFile(new File(path), json);
        }
        catch (IOException e) {
            //noinspection CallToPrintStackTrace
            e.printStackTrace();
        }
    }

    protected String getTestDirName(boolean lowercaseFirstLetter) {
        String testName = getTestName(lowercaseFirstLetter);
        return testName.substring(0, testName.indexOf('_'));
    }

    @Override
    protected void doTest(PerformAction performAction) {
        super.doTest(performAction, getTestDirName(true));
    }

    @NotNull
    @Override
    protected String getTestRoot() {
        return "/refactoring/rename/";
    }

    @Override
    protected String getTestDataPath() {
        return PluginTestCaseBase.getTestDataPathBase();
    }
}
