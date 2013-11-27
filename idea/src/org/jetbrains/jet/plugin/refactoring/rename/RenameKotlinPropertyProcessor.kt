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

package org.jetbrains.jet.plugin.refactoring.rename

import com.intellij.refactoring.rename.RenamePsiElementProcessor
import com.intellij.psi.PsiElement
import org.jetbrains.jet.lang.psi.JetProperty
import com.intellij.psi.search.SearchScope
import com.intellij.openapi.application.ApplicationManager
import org.jetbrains.jet.asJava.LightClassUtil
import com.intellij.psi.search.searches.OverridingMethodsSearch
import com.intellij.openapi.util.Computable
import org.jetbrains.jet.asJava.LightClassUtil.PropertyAccessorsPsiMethods
import com.intellij.psi.PsiMethod
import com.intellij.psi.PsiMirrorElement
import com.intellij.psi.SyntheticElement
import com.intellij.refactoring.util.RefactoringUtil
import com.intellij.refactoring.rename.RenameProcessor
import org.jetbrains.jet.lang.psi.JetPropertyAccessor
import com.intellij.psi.util.PsiTreeUtil
import com.intellij.psi.PsiNamedElement
import org.jetbrains.jet.codegen.PropertyCodegen
import org.jetbrains.jet.lang.resolve.name.Name
import com.intellij.usageView.UsageInfo
import com.intellij.refactoring.listeners.RefactoringElementListener
import org.jetbrains.jet.lang.psi.JetFile
import org.jetbrains.jet.lang.resolve.java.JvmAbi
import com.intellij.openapi.editor.Editor
import org.jetbrains.jet.lexer.JetTokens
import org.jetbrains.jet.plugin.project.AnalyzerFacadeWithCache
import org.jetbrains.jet.lang.resolve.BindingContextUtils
import org.jetbrains.jet.lang.resolve.BindingContext
import org.jetbrains.jet.lang.descriptors.CallableDescriptor
import org.jetbrains.jet.lang.psi.JetPsiUtil
import org.jetbrains.jet.lang.psi.JetClassOrObject
import com.intellij.openapi.ui.Messages
import org.jetbrains.jet.lang.resolve.DescriptorUtils
import org.jetbrains.jet.lang.psi.JetElement
import org.jetbrains.jet.lang.resolve.java.jetAsJava.KotlinLightMethod

public class RenameKotlinPropertyProcessor : RenamePsiElementProcessor() {
    override fun canProcessElement(element: PsiElement): Boolean = unwrapToJetProperty(element) != null

    override fun substituteElementToRename(element: PsiElement?, editor: Editor?): PsiElement? {
        val jetProperty: JetProperty = when (element) {
            is JetProperty -> element
            is KotlinLightMethod -> element.getOriginalElement() as JetProperty
            else -> throw IllegalStateException("Can't be for element $element there because of canProcessElement()")
        }

        val jetElement = findDeepestOverriddenElement(jetProperty)

        if (jetElement is JetProperty && jetElement.isWritable() && jetElement.isPhysical()) {
            val superProperty: JetProperty = jetElement

            if (ApplicationManager.getApplication()!!.isUnitTestMode()) {
                return superProperty
            }

            val propertyText: String? =
                    JetPsiUtil.getFQName(superProperty)?.parent()?.asString() ?:
                    (superProperty.getParent() as? JetClassOrObject)?.getName()

            val result = Messages.showYesNoCancelDialog(
                    superProperty.getProject(),
                    if (propertyText != null) "Do you want to rename base property from \n$propertyText" else "Do you want to rename base property",
                    "Rename warning",
                    Messages.getQuestionIcon())

            return when(result) {
                Messages.YES -> superProperty
                Messages.NO -> jetProperty
                else -> /* Cancel rename */ null
            }
        }

        return super.substituteElementToRename(element, editor)
    }

    override fun prepareRenaming(element: PsiElement?, newName: String?, allRenames: MutableMap<PsiElement, String>, scope: SearchScope) {
        super.prepareRenaming(element, newName, allRenames, scope)

        if (element is JetProperty) {
            val propertyMethods = ApplicationManager.getApplication()!!.runReadAction(Computable<PropertyAccessorsPsiMethods> {
                LightClassUtil.getLightClassPropertyMethods(element)
            })

            if (propertyMethods != null) {
                for (propertyMethod in propertyMethods) {
                    addRenameElements(propertyMethod, element.getName(), newName, allRenames, scope)
                }
            }
        }
    }

    private fun addRenameElements(psiMethod: PsiMethod?, baseName: String?, newName: String?, allRenames: MutableMap<PsiElement, String>, scope: SearchScope) {
        if (psiMethod != null) {
            OverridingMethodsSearch.search(psiMethod, scope, true).forEach { overrider ->
                var overriderElement: PsiNamedElement = overrider

                if (overriderElement is KotlinLightMethod) {
                    val prototype = (overriderElement as KotlinLightMethod).getOriginalElement()
                    if (prototype is PsiMethod) {
                        overriderElement = prototype
                    }
                    else if (prototype is JetProperty) {
                        overriderElement = prototype
                    }
                    else if (prototype is JetPropertyAccessor) {
                        overriderElement = PsiTreeUtil.getParentOfType(prototype, javaClass<JetProperty>())!!
                    }
                }

                if (!(overriderElement is SyntheticElement)) {
                    RenameProcessor.assertNonCompileElement(overriderElement)

                    val overriderName = overriderElement.getName()

                    if (overriderElement is PsiMethod) {
                        if (newName != null && Name.isValidIdentifier(newName)) {
                            RenameProcessor.assertNonCompileElement(overriderElement)

                            val isGetter = (overriderElement as PsiMethod).getParameterList().getParametersCount() == 0
                            val name = Name.identifier(newName)

                            allRenames[overriderElement] = if (isGetter) PropertyCodegen.getterName(name) else PropertyCodegen.setterName(name)
                        }
                    }
                    else {
                        val newOverriderName = RefactoringUtil.suggestNewOverriderName(overriderName, baseName, newName)
                        if (newOverriderName != null) {
                            allRenames[overriderElement] = newOverriderName
                        }
                    }
                }

                true
            }
        }
    }

    override fun renameElement(element: PsiElement?, newName: String?, usages: Array<out UsageInfo>, listener: RefactoringElementListener?) {
        if (element is JetProperty) {
            enum class RefKind {
                SAME_NAME
                OTHER_SETTER
                OTHER_GETTER
            }

            val jetNonJetFiles = usages.toList().groupBy {
                val reference = it.getReference()!!
                val refText = reference.getCanonicalText()
                when {
                    it.getFile() is JetFile || refText == element.getName() || refText.contains(".${element.getName()}") -> RefKind.SAME_NAME
                    refText.startsWith(JvmAbi.SETTER_PREFIX) || refText.contains(".${JvmAbi.SETTER_PREFIX}") -> RefKind.OTHER_SETTER
                    refText.startsWith(JvmAbi.GETTER_PREFIX) || refText.contains(".${JvmAbi.GETTER_PREFIX}") -> RefKind.OTHER_GETTER
                    else -> throw IllegalStateException("Unknown reference")
                }
            }

            super.renameElement(element, PropertyCodegen.setterName(Name.identifier(newName!!)),
                                jetNonJetFiles[RefKind.OTHER_SETTER]?.copyToArray() ?: array<UsageInfo>(),
                                null)

            super.renameElement(element, PropertyCodegen.getterName(Name.identifier(newName)),
                                jetNonJetFiles[RefKind.OTHER_GETTER]?.copyToArray() ?: array<UsageInfo>(),
                                null)

            super.renameElement(element, newName,
                                jetNonJetFiles[RefKind.SAME_NAME]?.copyToArray() ?: array<UsageInfo>(),
                                null)

            if (listener != null) {
                listener.elementRenamed(element)
            }
        }
        else {
            super.renameElement(element, newName, usages, listener)
        }
    }

    private fun findDeepestOverriddenElement(jetProperty: JetProperty): JetElement? {
        if (jetProperty.getModifierList()?.hasModifier(JetTokens.OVERRIDE_KEYWORD).equals(true)) {
            val bindingContext = AnalyzerFacadeWithCache.getContextForElement(jetProperty)
            val descriptor = bindingContext.get(BindingContext.DECLARATION_TO_DESCRIPTOR, jetProperty)

            if (descriptor != null) {
                assert(descriptor is CallableDescriptor, "Should be property descriptor")

                val deepestOverridden = DescriptorUtils.getDeepestOverridden(descriptor as CallableDescriptor)
                if (deepestOverridden != descriptor) {
                    val superPsiElement = BindingContextUtils.descriptorToDeclaration(bindingContext, deepestOverridden)
                    if (superPsiElement is JetElement) {
                        return superPsiElement
                    }
                }
            }
        }

        return null
    }

    private fun unwrapToJetProperty(element: PsiElement?): JetProperty? = when (element) {
        is JetProperty -> element
        is KotlinLightMethod -> element.getOriginalElement() as? JetProperty
        else -> null
    }
}