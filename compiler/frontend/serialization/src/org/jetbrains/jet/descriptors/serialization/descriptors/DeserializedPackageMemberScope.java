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

package org.jetbrains.jet.descriptors.serialization.descriptors;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.jetbrains.jet.descriptors.serialization.*;
import org.jetbrains.jet.lang.descriptors.ClassDescriptor;
import org.jetbrains.jet.lang.descriptors.DeclarationDescriptor;
import org.jetbrains.jet.lang.descriptors.PackageFragmentDescriptor;
import org.jetbrains.jet.lang.descriptors.ReceiverParameterDescriptor;
import org.jetbrains.jet.lang.resolve.name.FqName;
import org.jetbrains.jet.lang.resolve.name.FqNameUnsafe;
import org.jetbrains.jet.lang.resolve.name.Name;
import org.jetbrains.jet.storage.StorageManager;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;

public class DeserializedPackageMemberScope extends DeserializedMemberScope {
    private final DescriptorFinder descriptorFinder;

    private final FqName packageFqName;

    public DeserializedPackageMemberScope(
            @NotNull StorageManager storageManager,
            @NotNull PackageFragmentDescriptor packageDescriptor,
            @NotNull Deserializers deserializers,
            @NotNull MemberFilter memberFilter,
            @NotNull DescriptorFinder descriptorFinder,
            @NotNull ProtoBuf.Package proto,
            @NotNull NameResolver nameResolver
    ) {
        super(storageManager, packageDescriptor,
              DescriptorDeserializer.create(storageManager, packageDescriptor, nameResolver, descriptorFinder, deserializers),
              getFilteredMembers(packageDescriptor, proto, memberFilter, nameResolver));
        this.descriptorFinder = descriptorFinder;
        this.packageFqName = packageDescriptor.getFqName();
    }

    public DeserializedPackageMemberScope(
            @NotNull StorageManager storageManager,
            @NotNull PackageFragmentDescriptor packageDescriptor,
            @NotNull Deserializers deserializers,
            @NotNull MemberFilter memberFilter,
            @NotNull DescriptorFinder descriptorFinder,
            @NotNull PackageData packageData
    ) {
        this(storageManager, packageDescriptor, deserializers, memberFilter, descriptorFinder, packageData.getPackageProto(),
             packageData.getNameResolver());
    }

    @Nullable
    @Override
    protected ClassDescriptor getClassDescriptor(@NotNull Name name) {
        return descriptorFinder.findClass(new ClassId(packageFqName, FqNameUnsafe.topLevel(name)));
    }

    @Override
    protected void addAllClassDescriptors(@NotNull Collection<DeclarationDescriptor> result) {
        for (Name className : descriptorFinder.getClassNames(packageFqName)) {
            ClassDescriptor classDescriptor = getClassDescriptor(className);

            if (classDescriptor != null) {
                result.add(classDescriptor);
            }
        }
    }

    @Override
    protected void addNonDeclaredDescriptors(@NotNull Collection<DeclarationDescriptor> result) {
        // Do nothing
    }

    @Nullable
    @Override
    protected ReceiverParameterDescriptor getImplicitReceiver() {
        return null;
    }

    @NotNull
    private static Collection<ProtoBuf.Callable> getFilteredMembers(
            @NotNull PackageFragmentDescriptor packageDescriptor,
            @NotNull ProtoBuf.Package packageProto,
            @NotNull MemberFilter memberFilter,
            @NotNull NameResolver nameResolver
    ) {
        List<ProtoBuf.Callable> result = new ArrayList<ProtoBuf.Callable>();
        for (ProtoBuf.Callable member : packageProto.getMemberList()) {
            if (memberFilter.acceptPackagePartClass(packageDescriptor, member, nameResolver)) {
                result.add(member);
            }
        }
        return result;
    }
}
