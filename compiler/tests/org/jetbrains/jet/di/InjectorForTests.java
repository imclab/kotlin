/*
 * Copyright 2010-2014 JetBrains s.r.o.
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

package org.jetbrains.jet.di;

import com.intellij.openapi.project.Project;
import org.jetbrains.jet.lang.descriptors.ModuleDescriptor;
import org.jetbrains.jet.lang.PlatformToKotlinClassMap;
import org.jetbrains.jet.lang.resolve.DescriptorResolver;
import org.jetbrains.jet.lang.types.expressions.ExpressionTypingServices;
import org.jetbrains.jet.lang.types.expressions.ExpressionTypingUtils;
import org.jetbrains.jet.lang.resolve.TypeResolver;
import org.jetbrains.jet.context.GlobalContext;
import org.jetbrains.jet.storage.StorageManager;
import org.jetbrains.jet.lang.resolve.AnnotationResolver;
import org.jetbrains.jet.lang.resolve.calls.CallResolver;
import org.jetbrains.jet.lang.resolve.calls.ArgumentTypeResolver;
import org.jetbrains.jet.lang.resolve.calls.CandidateResolver;
import org.jetbrains.jet.lang.resolve.DelegatedPropertyResolver;
import org.jetbrains.jet.lang.types.expressions.ExpressionTypingComponents;
import org.jetbrains.jet.lang.types.expressions.ControlStructureTypingUtils;
import org.jetbrains.jet.lang.types.expressions.ForLoopConventionsChecker;
import org.jetbrains.jet.lang.resolve.calls.CallExpressionResolver;
import org.jetbrains.jet.lang.resolve.calls.CallResolverExtensionProvider;
import org.jetbrains.jet.lang.resolve.QualifiedExpressionResolver;
import org.jetbrains.annotations.NotNull;
import javax.annotation.PreDestroy;

/* This file is generated by org.jetbrains.jet.generators.injectors.InjectorsPackage. DO NOT EDIT! */
@SuppressWarnings("ALL")
public class InjectorForTests {
    
    private final Project project;
    private final ModuleDescriptor moduleDescriptor;
    private final PlatformToKotlinClassMap platformToKotlinClassMap;
    private final DescriptorResolver descriptorResolver;
    private final ExpressionTypingServices expressionTypingServices;
    private final ExpressionTypingUtils expressionTypingUtils;
    private final TypeResolver typeResolver;
    private final GlobalContext globalContext;
    private final StorageManager storageManager;
    private final AnnotationResolver annotationResolver;
    private final CallResolver callResolver;
    private final ArgumentTypeResolver argumentTypeResolver;
    private final CandidateResolver candidateResolver;
    private final DelegatedPropertyResolver delegatedPropertyResolver;
    private final ExpressionTypingComponents expressionTypingComponents;
    private final ControlStructureTypingUtils controlStructureTypingUtils;
    private final ForLoopConventionsChecker forLoopConventionsChecker;
    private final CallExpressionResolver callExpressionResolver;
    private final CallResolverExtensionProvider callResolverExtensionProvider;
    private final QualifiedExpressionResolver qualifiedExpressionResolver;
    
    public InjectorForTests(
        @NotNull Project project,
        @NotNull ModuleDescriptor moduleDescriptor
    ) {
        this.project = project;
        this.moduleDescriptor = moduleDescriptor;
        this.platformToKotlinClassMap = moduleDescriptor.getPlatformToKotlinClassMap();
        this.descriptorResolver = new DescriptorResolver();
        this.expressionTypingComponents = new ExpressionTypingComponents();
        this.expressionTypingServices = new ExpressionTypingServices(expressionTypingComponents);
        this.callResolver = new CallResolver();
        this.expressionTypingUtils = new ExpressionTypingUtils(getExpressionTypingServices(), callResolver);
        this.typeResolver = new TypeResolver();
        this.globalContext = org.jetbrains.jet.context.ContextPackage.GlobalContext();
        this.storageManager = globalContext.getStorageManager();
        this.annotationResolver = new AnnotationResolver();
        this.argumentTypeResolver = new ArgumentTypeResolver();
        this.candidateResolver = new CandidateResolver();
        this.delegatedPropertyResolver = new DelegatedPropertyResolver();
        this.controlStructureTypingUtils = new ControlStructureTypingUtils(getExpressionTypingServices());
        this.forLoopConventionsChecker = new ForLoopConventionsChecker();
        this.callExpressionResolver = new CallExpressionResolver();
        this.callResolverExtensionProvider = new CallResolverExtensionProvider();
        this.qualifiedExpressionResolver = new QualifiedExpressionResolver();

        this.descriptorResolver.setAnnotationResolver(annotationResolver);
        this.descriptorResolver.setDelegatedPropertyResolver(delegatedPropertyResolver);
        this.descriptorResolver.setExpressionTypingServices(expressionTypingServices);
        this.descriptorResolver.setStorageManager(storageManager);
        this.descriptorResolver.setTypeResolver(typeResolver);

        this.expressionTypingServices.setAnnotationResolver(annotationResolver);
        this.expressionTypingServices.setCallExpressionResolver(callExpressionResolver);
        this.expressionTypingServices.setCallResolver(callResolver);
        this.expressionTypingServices.setDescriptorResolver(descriptorResolver);
        this.expressionTypingServices.setExtensionProvider(callResolverExtensionProvider);
        this.expressionTypingServices.setProject(project);
        this.expressionTypingServices.setTypeResolver(typeResolver);

        this.typeResolver.setAnnotationResolver(annotationResolver);
        this.typeResolver.setModuleDescriptor(moduleDescriptor);
        this.typeResolver.setQualifiedExpressionResolver(qualifiedExpressionResolver);

        annotationResolver.setCallResolver(callResolver);
        annotationResolver.setStorageManager(storageManager);

        callResolver.setArgumentTypeResolver(argumentTypeResolver);
        callResolver.setCandidateResolver(candidateResolver);
        callResolver.setExpressionTypingServices(expressionTypingServices);
        callResolver.setTypeResolver(typeResolver);

        argumentTypeResolver.setExpressionTypingServices(expressionTypingServices);
        argumentTypeResolver.setTypeResolver(typeResolver);

        candidateResolver.setArgumentTypeResolver(argumentTypeResolver);

        delegatedPropertyResolver.setCallResolver(callResolver);
        delegatedPropertyResolver.setExpressionTypingServices(expressionTypingServices);

        expressionTypingComponents.setCallResolver(callResolver);
        expressionTypingComponents.setControlStructureTypingUtils(controlStructureTypingUtils);
        expressionTypingComponents.setExpressionTypingServices(expressionTypingServices);
        expressionTypingComponents.setExpressionTypingUtils(expressionTypingUtils);
        expressionTypingComponents.setForLoopConventionsChecker(forLoopConventionsChecker);
        expressionTypingComponents.setGlobalContext(globalContext);
        expressionTypingComponents.setPlatformToKotlinClassMap(platformToKotlinClassMap);

        forLoopConventionsChecker.setExpressionTypingServices(expressionTypingServices);
        forLoopConventionsChecker.setExpressionTypingUtils(expressionTypingUtils);
        forLoopConventionsChecker.setProject(project);

        callExpressionResolver.setExpressionTypingServices(expressionTypingServices);

    }
    
    @PreDestroy
    public void destroy() {
    }
    
    public DescriptorResolver getDescriptorResolver() {
        return this.descriptorResolver;
    }
    
    public ExpressionTypingServices getExpressionTypingServices() {
        return this.expressionTypingServices;
    }
    
    public ExpressionTypingUtils getExpressionTypingUtils() {
        return this.expressionTypingUtils;
    }
    
    public TypeResolver getTypeResolver() {
        return this.typeResolver;
    }
    
}
