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

package org.jetbrains.jet.plugin;

import com.intellij.openapi.fileTypes.LanguageFileType;
import com.intellij.openapi.util.IconLoader;
import com.intellij.openapi.util.NotNullLazyValue;
import org.jetbrains.annotations.NotNull;

import javax.swing.*;

public class JetFileType extends LanguageFileType {
    public static final JetFileType INSTANCE = new JetFileType();
    private final NotNullLazyValue<Icon> myIcon = new NotNullLazyValue<Icon>() {
        @NotNull
        @Override
        protected Icon compute() {
            return IconLoader.getIcon("/org/jetbrains/jet/plugin/icons/kotlin_file.png");
        }
    };

    private JetFileType() {
        super(JetLanguage.INSTANCE);
    }

    @Override
    @NotNull
    public String getName() {
        return JetLanguage.NAME;
    }

    @Override
    @NotNull
    public String getDescription() {
        return getName();
    }

    @Override
    @NotNull
    public String getDefaultExtension() {
        return "kt";
    }

    @Override
    public Icon getIcon() {
        return myIcon.getValue();
    }

    @Override
    public boolean isJVMDebuggingSupported() {
        return true;
    }
}
