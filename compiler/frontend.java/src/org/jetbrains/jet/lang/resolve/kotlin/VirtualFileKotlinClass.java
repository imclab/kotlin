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

package org.jetbrains.jet.lang.resolve.kotlin;

import com.intellij.openapi.diagnostic.Logger;
import com.intellij.openapi.util.Pair;
import com.intellij.openapi.util.Ref;
import com.intellij.openapi.vfs.VirtualFile;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.jetbrains.asm4.ClassReader;
import org.jetbrains.asm4.ClassVisitor;
import org.jetbrains.asm4.FieldVisitor;
import org.jetbrains.asm4.MethodVisitor;
import org.jetbrains.jet.lang.resolve.java.JvmClassName;
import org.jetbrains.jet.lang.resolve.kotlin.header.KotlinClassHeader;
import org.jetbrains.jet.lang.resolve.kotlin.header.ReadKotlinClassHeaderAnnotationVisitor;
import org.jetbrains.jet.lang.resolve.name.Name;
import org.jetbrains.jet.utils.UtilsPackage;

import static org.jetbrains.asm4.ClassReader.*;
import static org.jetbrains.asm4.Opcodes.ASM4;

public class VirtualFileKotlinClass implements KotlinJvmBinaryClass {
    private final static Logger LOG = Logger.getInstance(VirtualFileKotlinClass.class);

    private final VirtualFile file;
    private final JvmClassName className;
    private final KotlinClassHeader classHeader;

    private VirtualFileKotlinClass(@NotNull VirtualFile file, @NotNull JvmClassName className, @NotNull KotlinClassHeader classHeader) {
        this.file = file;
        this.className = className;
        this.classHeader = classHeader;
    }

    @Nullable
    private static Pair<JvmClassName, KotlinClassHeader> readClassNameAndHeader(@NotNull byte[] fileContents) {
        final ReadKotlinClassHeaderAnnotationVisitor readHeaderVisitor = new ReadKotlinClassHeaderAnnotationVisitor();
        final Ref<JvmClassName> classNameRef = Ref.create();
        new ClassReader(fileContents).accept(new ClassVisitor(ASM4) {
            @Override
            public void visit(int version, int access, String name, String signature, String superName, String[] interfaces) {
                classNameRef.set(JvmClassName.byInternalName(name));
            }

            @Override
            public org.jetbrains.asm4.AnnotationVisitor visitAnnotation(String desc, boolean visible) {
                return convertAnnotationVisitor(readHeaderVisitor, desc);
            }

            @Override
            public void visitEnd() {
                readHeaderVisitor.visitEnd();
            }
        }, SKIP_CODE | SKIP_DEBUG | SKIP_FRAMES);

        JvmClassName className = classNameRef.get();
        if (className == null) return null;

        KotlinClassHeader header = readHeaderVisitor.createHeader();
        if (header == null) return null;

        return Pair.create(className, header);
    }

    @Nullable
    /* package */ static VirtualFileKotlinClass create(@NotNull VirtualFile file) {
        try {
            byte[] fileContents = file.contentsToByteArray();
            Pair<JvmClassName, KotlinClassHeader> nameAndHeader = readClassNameAndHeader(fileContents);
            if (nameAndHeader == null) {
                return null;
            }

            return new VirtualFileKotlinClass(file, nameAndHeader.first, nameAndHeader.second);
        }
        catch (Throwable e) {
            LOG.warn(renderFileReadingErrorMessage(file), e);
            return null;
        }
    }

    @Nullable
    public static KotlinClassHeader readClassHeader(@NotNull byte[] fileContents) {
        Pair<JvmClassName, KotlinClassHeader> pair = readClassNameAndHeader(fileContents);
        return pair == null ? null : pair.second;
    }

    @NotNull
    public VirtualFile getFile() {
        return file;
    }

    @NotNull
    @Override
    public JvmClassName getClassName() {
        return className;
    }

    @NotNull
    @Override
    public KotlinClassHeader getClassHeader() {
        return classHeader;
    }

    @Override
    public void loadClassAnnotations(@NotNull final AnnotationVisitor annotationVisitor) {
        try {
            new ClassReader(file.contentsToByteArray()).accept(new ClassVisitor(ASM4) {
                @Override
                public org.jetbrains.asm4.AnnotationVisitor visitAnnotation(String desc, boolean visible) {
                    return convertAnnotationVisitor(annotationVisitor, desc);
                }

                @Override
                public void visitEnd() {
                    annotationVisitor.visitEnd();
                }
            }, SKIP_CODE | SKIP_DEBUG | SKIP_FRAMES);
        }
        catch (Throwable e) {
            LOG.error(renderFileReadingErrorMessage(file), e);
            throw UtilsPackage.rethrow(e);
        }
    }

    @Nullable
    private static org.jetbrains.asm4.AnnotationVisitor convertAnnotationVisitor(@NotNull AnnotationVisitor visitor, @NotNull String desc) {
        AnnotationArgumentVisitor v = visitor.visitAnnotation(classNameFromAsmDesc(desc));
        return v == null ? null : convertAnnotationVisitor(v);
    }

    @NotNull
    private static org.jetbrains.asm4.AnnotationVisitor convertAnnotationVisitor(@NotNull final AnnotationArgumentVisitor v) {
        return new org.jetbrains.asm4.AnnotationVisitor(ASM4) {
            @Override
            public void visit(String name, Object value) {
                v.visit(name == null ? null : Name.identifier(name), value);
            }

            @Override
            public org.jetbrains.asm4.AnnotationVisitor visitArray(String name) {
                AnnotationArgumentVisitor av = v.visitArray(Name.guess(name));
                return av == null ? null : convertAnnotationVisitor(av);
            }

            @Override
            public void visitEnum(String name, String desc, String value) {
                v.visitEnum(Name.identifier(name), classNameFromAsmDesc(desc), Name.identifier(value));
            }

            @Override
            public void visitEnd() {
                v.visitEnd();
            }
        };
    }

    @Override
    public void visitMembers(@NotNull final MemberVisitor memberVisitor) {
        try {
            new ClassReader(file.contentsToByteArray()).accept(new ClassVisitor(ASM4) {
                @Override
                public FieldVisitor visitField(int access, String name, String desc, String signature, Object value) {
                    final AnnotationVisitor v = memberVisitor.visitField(Name.guess(name), desc, value);
                    if (v == null) return null;

                    return new FieldVisitor(ASM4) {
                        @Override
                        public org.jetbrains.asm4.AnnotationVisitor visitAnnotation(String desc, boolean visible) {
                            return convertAnnotationVisitor(v, desc);
                        }

                        @Override
                        public void visitEnd() {
                            v.visitEnd();
                        }
                    };
                }

                @Override
                public MethodVisitor visitMethod(int access, String name, String desc, String signature, String[] exceptions) {
                    final MethodAnnotationVisitor v = memberVisitor.visitMethod(Name.guess(name), desc);
                    if (v == null) return null;

                    return new MethodVisitor(ASM4) {
                        @Override
                        public org.jetbrains.asm4.AnnotationVisitor visitAnnotation(String desc, boolean visible) {
                            return convertAnnotationVisitor(v, desc);
                        }

                        @Override
                        public org.jetbrains.asm4.AnnotationVisitor visitParameterAnnotation(int parameter, String desc, boolean visible) {
                            AnnotationArgumentVisitor av = v.visitParameterAnnotation(parameter, classNameFromAsmDesc(desc));
                            return av == null ? null : convertAnnotationVisitor(av);
                        }

                        @Override
                        public void visitEnd() {
                            v.visitEnd();
                        }
                    };
                }
            }, SKIP_CODE | SKIP_DEBUG | SKIP_FRAMES);
        }
        catch (Throwable e) {
            LOG.error(renderFileReadingErrorMessage(file), e);
            throw UtilsPackage.rethrow(e);
        }
    }

    @NotNull
    private static JvmClassName classNameFromAsmDesc(@NotNull String desc) {
        assert desc.startsWith("L") && desc.endsWith(";") : "Not a JVM descriptor: " + desc;
        return JvmClassName.byInternalName(desc.substring(1, desc.length() - 1));
    }

    @NotNull
    private static String renderFileReadingErrorMessage(@NotNull VirtualFile file) {
        return "Could not read file: " + file.getPath() + "\n"
               + "Size in bytes: " + file.getLength() + "\n"
               + "File type: " + file.getFileType().getName();
    }

    @Override
    public int hashCode() {
        return file.hashCode();
    }

    @Override
    public boolean equals(Object obj) {
        return obj instanceof VirtualFileKotlinClass && ((VirtualFileKotlinClass) obj).file.equals(file);
    }

    @Override
    public String toString() {
        return getClass().getSimpleName() + ": " + file.toString();
    }
}
