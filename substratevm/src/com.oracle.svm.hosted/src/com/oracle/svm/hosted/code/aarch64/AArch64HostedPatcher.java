/*
 * Copyright (c) 2019, 2019, Oracle and/or its affiliates. All rights reserved.
 * DO NOT ALTER OR REMOVE COPYRIGHT NOTICES OR THIS FILE HEADER.
 *
 * This code is free software; you can redistribute it and/or modify it
 * under the terms of the GNU General Public License version 2 only, as
 * published by the Free Software Foundation.  Oracle designates this
 * particular file as subject to the "Classpath" exception as provided
 * by Oracle in the LICENSE file that accompanied this code.
 *
 * This code is distributed in the hope that it will be useful, but WITHOUT
 * ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or
 * FITNESS FOR A PARTICULAR PURPOSE.  See the GNU General Public License
 * version 2 for more details (a copy is included in the LICENSE file that
 * accompanied this code).
 *
 * You should have received a copy of the GNU General Public License version
 * 2 along with this work; if not, write to the Free Software Foundation,
 * Inc., 51 Franklin St, Fifth Floor, Boston, MA 02110-1301 USA.
 *
 * Please contact Oracle, 500 Oracle Parkway, Redwood Shores, CA 94065 USA
 * or visit www.oracle.com if you need additional information or have any
 * questions.
 */
package com.oracle.svm.hosted.code.aarch64;

import java.util.function.Consumer;

import org.graalvm.compiler.asm.Assembler.CodeAnnotation;
import org.graalvm.compiler.asm.aarch64.AArch64Assembler;
import org.graalvm.compiler.asm.aarch64.AArch64Assembler.OperandDataAnnotation;
import org.graalvm.compiler.code.CompilationResult;
import org.graalvm.compiler.core.common.NumUtil;
import org.graalvm.nativeimage.Feature;
import org.graalvm.nativeimage.ImageSingletons;
import org.graalvm.nativeimage.Platform;
import org.graalvm.nativeimage.Platforms;

import com.oracle.svm.core.annotate.AutomaticFeature;
import com.oracle.svm.core.annotate.Uninterruptible;
import com.oracle.svm.core.graal.code.PatchConsumerFactory;
import com.oracle.svm.hosted.code.HostedPatcher;
import com.oracle.svm.hosted.image.RelocatableBuffer;

import jdk.vm.ci.code.site.Reference;

import static org.graalvm.compiler.asm.aarch64.AArch64Assembler.*;

@AutomaticFeature
@Platforms({Platform.AArch64.class})
class AArch64HostedPatcherFeature implements Feature {
    @Override
    public void afterRegistration(AfterRegistrationAccess access) {
        ImageSingletons.add(PatchConsumerFactory.HostedPatchConsumerFactory.class, new PatchConsumerFactory.HostedPatchConsumerFactory() {
            @Override
            public Consumer<CodeAnnotation> newConsumer(CompilationResult compilationResult) {
                return new Consumer<CodeAnnotation>() {
                    @Override
                    public void accept(CodeAnnotation annotation) {
                        if (annotation instanceof OperandDataAnnotation) {
                            System.err.println( "ODA on " + System.identityHashCode(compilationResult) + " for " + annotation + " at " + annotation.instructionPosition);
                            compilationResult.addAnnotation(new AArch64HostedPatcher(annotation.instructionPosition, (OperandDataAnnotation) annotation));
                        } else if (annotation instanceof NativeAddressOperandDataAnnotation) {
                            System.err.println( "NAODA on " + System.identityHashCode(compilationResult) + " for " + annotation + " at " + annotation.instructionPosition);
                            compilationResult.addAnnotation(new AArch64NativeAddressHostedPatcher(annotation.instructionPosition, (NativeAddressOperandDataAnnotation) annotation));
                        }
                    }
                };
            }
        });
    }
}

public class AArch64HostedPatcher extends CompilationResult.CodeAnnotation implements HostedPatcher {
    private final OperandDataAnnotation annotation;

    public AArch64HostedPatcher(int instructionStartPosition, OperandDataAnnotation annotation) {
        super(instructionStartPosition);
        this.annotation = annotation;
    }

    @Uninterruptible(reason = ".")
    @Override
    public void patch(int codePos, int relative, byte[] code) {
        System.err.println( "PATCH-op: " + dump(relative) );
        System.err.println( "PATCHING" );
        dump( code, annotation.instructionPosition);

        int curValue = relative - 4; // 32-bit instr, next is 4 bytes away.

        curValue = curValue >> annotation.shift;

        System.err.println( "adjusted shifted patch: " + dump(curValue));

        int bitsRemaining = annotation.operandSizeBits;
        int offsetRemaining = annotation.offsetBits;
        
        for ( int i = 0 ; i < 4 ; ++i ) {
            if ( offsetRemaining >= 8 ) {
                offsetRemaining -= 8;
                continue;
            }

            // non-zero bits set
            int mask = 0;
            System.err.println( "mask for " + bitsRemaining + " // offset: "+  offsetRemaining);
            for ( int j = 0 ; j < 8 ; ++j ) {
                if ( j >= offsetRemaining ) {
                    mask |= (1 << j);
                    --bitsRemaining;
                }
                if ( bitsRemaining == 0 ) {
                    break;
                }
            }

            //System.err.println( "before-op " + i + ": " + dump( code[annotation.instructionPosition + i]));
            byte patchTarget = code[annotation.instructionPosition+i];
            System.err.println( "patching byte: " + dump(patchTarget));
            System.err.println( "mask: " + dump(mask) + " ~" + dump(~mask << offsetRemaining));
            byte patch = (byte) (( ((byte) (curValue & 0xFF)) & mask) << offsetRemaining);
            System.err.println( "patch with: " + dump(curValue) + " == " + dump(patch) );
            byte retainedPatchTarget = (byte) (patchTarget & ( ~mask << offsetRemaining ));
            System.err.println( "retain: " + dump(retainedPatchTarget));
            patchTarget = (byte) (retainedPatchTarget | patch);
            code[annotation.instructionPosition + i] = patchTarget;
            System.err.println( "after-op " + i + ": " + dump( code[annotation.instructionPosition + i]));
            curValue = curValue >>> (8 - offsetRemaining);
            offsetRemaining = 0;
        }

        System.err.println( "PATCHED" );
        dump( code, annotation.instructionPosition);
    }

    public void dump(byte[] bytes, int start) {
        for ( int i = 0 ; i < 4 ; ++i ) {
            System.err.println( i + ": " + dump( bytes[start+i] ) );
        }
    }

    public String dump(byte b) {
        return dump( Byte.toUnsignedInt(b));
    }

    public String dump(int i) {
        String b = String.format("%32s", Integer.toBinaryString(i)).replace(' ', '0');
        String h = String.format("%4s", Integer.toHexString(i)).replace(' ', '0');
        return i + " ["+ b + "] (" + h + ")";
    }



    @Override
    public boolean equals(Object obj) {
        return obj == this;
    }

    @Override
    public void relocate(Reference ref, RelocatableBuffer relocs, int compStart) {

    }
}

class AArch64NativeAddressHostedPatcher extends CompilationResult.CodeAnnotation implements HostedPatcher {
    private final NativeAddressOperandDataAnnotation annotation;

    public AArch64NativeAddressHostedPatcher(int instructionStartPosition, NativeAddressOperandDataAnnotation annotation) {
        super(instructionStartPosition);
        this.annotation = annotation;
    }

    @Uninterruptible(reason = ".")
    @Override
    public void patch(int codePos, int relative, byte[] code) {
        System.err.println( "PATCH-na: " + Integer.toHexString(relative));
        int curValue = relative - (4 * annotation.numInstrs); // 3 32-bit instrs to patch 48-bit movs

        int bitsRemaining = annotation.operandSizeBits;

        for ( int i = 0 ; i < 4 * annotation.numInstrs ; i = i + 4 ) {
            if ( bitsRemaining >= 8) {
                code[annotation.instructionPosition + i] = (byte) (curValue & 0xFF);
                bitsRemaining -= 8;
            } else {
                int mask = 0;
                for ( int j = 0 ; j < bitsRemaining ; ++j ) {
                    mask |= ( 1 << j );
                }
                System.err.println( "before-na " + i + ": " + Integer.toHexString( code[annotation.instructionPosition + i]));
                code[annotation.instructionPosition + i] = (byte) ( ( (byte) (curValue & mask) ) | ( code[annotation.instructionPosition & ~mask]) );
                System.err.println( "after-na " + i + ": " + Integer.toHexString( code[annotation.instructionPosition + i]));
            }
            curValue = curValue >>> 8;
        }
    }

    @Override
    public boolean equals(Object obj) {
        return obj == this;
    }

    @Override
    public void relocate(Reference ref, RelocatableBuffer relocs, int compStart) {

    }
}