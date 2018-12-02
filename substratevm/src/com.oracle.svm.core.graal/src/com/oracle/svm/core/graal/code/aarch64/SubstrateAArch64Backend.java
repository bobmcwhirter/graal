/*
 * Copyright (c) 2012, 2018, Oracle and/or its affiliates. All rights reserved.
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
package com.oracle.svm.core.graal.code.aarch64;

import static com.oracle.svm.core.util.VMError.unimplemented;

import com.oracle.svm.core.SubstrateOptions;
import org.graalvm.collections.EconomicSet;
import org.graalvm.compiler.asm.Assembler;
import org.graalvm.compiler.asm.aarch64.AArch64MacroAssembler;
import org.graalvm.compiler.code.CompilationResult;
import org.graalvm.compiler.core.aarch64.AArch64ArithmeticLIRGenerator;
import org.graalvm.compiler.core.aarch64.AArch64NodeMatchRules;
import org.graalvm.compiler.core.common.CompilationIdentifier;
import org.graalvm.compiler.core.common.alloc.RegisterAllocationConfig;
import org.graalvm.compiler.core.target.Backend;
import org.graalvm.compiler.debug.DebugContext;
import org.graalvm.compiler.lir.LIR;
import org.graalvm.compiler.lir.aarch64.AArch64FrameMap;
import org.graalvm.compiler.lir.aarch64.AArch64FrameMapBuilder;
import org.graalvm.compiler.lir.asm.CompilationResultBuilder;
import org.graalvm.compiler.lir.asm.CompilationResultBuilderFactory;
import org.graalvm.compiler.lir.asm.DataBuilder;
import org.graalvm.compiler.lir.asm.FrameContext;
import org.graalvm.compiler.lir.framemap.FrameMap;
import org.graalvm.compiler.lir.framemap.FrameMapBuilder;
import org.graalvm.compiler.lir.gen.LIRGenerationResult;
import org.graalvm.compiler.lir.gen.LIRGeneratorTool;
import org.graalvm.compiler.nodes.StructuredGraph;
import org.graalvm.compiler.nodes.spi.NodeLIRBuilderTool;
import org.graalvm.compiler.options.OptionValues;
import org.graalvm.compiler.phases.tiers.SuitesProvider;
import org.graalvm.compiler.phases.util.Providers;
import org.graalvm.nativeimage.Feature;
import org.graalvm.nativeimage.ImageSingletons;
import org.graalvm.nativeimage.Platform;
import org.graalvm.nativeimage.Platforms;

import com.oracle.svm.core.annotate.AutomaticFeature;
import com.oracle.svm.core.deopt.Deoptimizer;
import com.oracle.svm.core.graal.code.SubstrateBackendFactory;
import com.oracle.svm.core.graal.code.SubstrateCompiledCode;
import com.oracle.svm.core.graal.code.SubstrateDataBuilder;
import com.oracle.svm.core.graal.code.amd64.SubstrateCallingConventionType;
import com.oracle.svm.core.meta.SharedMethod;

import jdk.vm.ci.code.CallingConvention;
import jdk.vm.ci.code.CodeUtil;
import jdk.vm.ci.code.CompilationRequest;
import jdk.vm.ci.code.CompiledCode;
import jdk.vm.ci.code.Register;
import jdk.vm.ci.code.RegisterConfig;
import jdk.vm.ci.code.RegisterValue;
import jdk.vm.ci.meta.ResolvedJavaMethod;

@AutomaticFeature
@Platforms(Platform.AARCH64.class)
class SubstrateAArch4BackendFeature implements Feature {
    @Override
    public void afterRegistration(AfterRegistrationAccess access) {
        ImageSingletons.add(SubstrateBackendFactory.class, new SubstrateBackendFactory() {
            @Override
            public Backend newBackend(Providers newProviders) {
                return new SubstrateAArch64Backend(newProviders);
            }
        });
    }
}

public class SubstrateAArch64Backend extends Backend {

    protected SubstrateAArch64Backend(Providers providers) {
        super(providers);
    }

    @Override
    public SuitesProvider getSuites() {
        throw unimplemented();
    }

    @Override
    public FrameMapBuilder newFrameMapBuilder(RegisterConfig registerConfig) {
        RegisterConfig registerConfigNonNull = registerConfig == null ? getCodeCache().getRegisterConfig() : registerConfig;
        return new AArch64FrameMapBuilder(newFrameMap(registerConfigNonNull), getCodeCache(), registerConfigNonNull);
    }

    @Override
    public FrameMap newFrameMap(RegisterConfig registerConfig) {
        return new AArch64FrameMap(getProviders().getCodeCache(), registerConfig, new SubstrateAArch64Backend.SubstrateReferenceMapBuilderFactory(), ((SubstrateAArch64RegisterConfig) registerConfig).shouldUseBasePointer());
    }

    @Override
    public RegisterAllocationConfig newRegisterAllocationConfig(RegisterConfig registerConfig, String[] allocationRestrictedTo) {
        RegisterConfig registerConfigNonNull = registerConfig == null ? getCodeCache().getRegisterConfig() : registerConfig;
        return new RegisterAllocationConfig(registerConfigNonNull, allocationRestrictedTo);
    }

    @Override
    public LIRGeneratorTool newLIRGenerator(LIRGenerationResult lirGenRes) {
        RegisterValue nullRegisterValue = useLinearPointerCompression() ? getHeapBaseRegister(lirGenRes).asValue() : null;
        AArch64ArithmeticLIRGenerator arithmeticLIRGen = createArithmeticLIRGen(nullRegisterValue);
        AArch64MoveFactoryBase.BackupSlotProvider backupSlotProvider = new AArch64MoveFactoryBase.BackupSlotProvider(lirGenRes.getFrameMapBuilder());
        AArch64MoveFactoryBase moveFactory = createMoveFactory(lirGenRes, backupSlotProvider);
        return new SubstrateAArch64Backend.SubstrateAArch64LIRGenerator(createLirKindTool(), arithmeticLIRGen, moveFactory, getProviders(), lirGenRes);
    }

    @Override
    public LIRGenerationResult newLIRGenerationResult(CompilationIdentifier compilationId, LIR lir, FrameMapBuilder frameMapBuilder, StructuredGraph graph, Object stub) {
        SharedMethod method = (SharedMethod) graph.method();
        CallingConvention callingConvention = CodeUtil.getCallingConvention(getCodeCache(), method.isEntryPoint() ? SubstrateCallingConventionType.NativeCallee
                : SubstrateCallingConventionType.JavaCallee, method, this);
        return new SubstrateAArch64Backend.SubstrateLIRGenerationResult(compilationId, lir, frameMapBuilder, callingConvention, method);
    }

    @Override
    public NodeLIRBuilderTool newNodeLIRBuilder(StructuredGraph graph, LIRGeneratorTool lirGen) {
        AArch64NodeMatchRules nodeMatchRules = createMatchRules(lirGen);
        return new SubstrateAArch64Backend.SubstrateAArch64NodeLIRBuilder(graph, lirGen, nodeMatchRules);
    }

    @Override
    protected Assembler createAssembler(FrameMap frameMap) {
        return new AArch64MacroAssembler(getTarget());
    }

    @Override
    public CompilationResultBuilder newCompilationResultBuilder(LIRGenerationResult lirGenResult, FrameMap frameMap, CompilationResult compilationResult, CompilationResultBuilderFactory factory) {
        Assembler masm = createAssembler(frameMap);
        SharedMethod method = ((SubstrateAArch64Backend.SubstrateLIRGenerationResult) lirGenResult).getMethod();
        Deoptimizer.StubType stubType = method.getDeoptStubType();
        DataBuilder dataBuilder = new SubstrateDataBuilder();
        final FrameContext frameContext;
        if (stubType == Deoptimizer.StubType.EntryStub) {
            frameContext = new SubstrateAArch64Backend.DeoptEntryStubContext();
        } else if (stubType == Deoptimizer.StubType.ExitStub) {
            frameContext = new SubstrateAArch64Backend.DeoptExitStubContext();
        } else {
            frameContext = new SubstrateAArch64Backend.SubstrateAArch64FrameContext();
        }
        LIR lir = lirGenResult.getLIR();
        OptionValues options = lir.getOptions();
        DebugContext debug = lir.getDebug();
        Register nullRegister = useLinearPointerCompression() ? getHeapBaseRegister(lirGenResult) : Register.None;
        CompilationResultBuilder tasm = factory.createBuilder(getCodeCache(), getForeignCalls(), lirGenResult.getFrameMap(), masm, dataBuilder, frameContext, options, debug, compilationResult,
                                                              nullRegister);
        tasm.setTotalFrameSize(lirGenResult.getFrameMap().totalFrameSize());
        return tasm;
    }


    @Override
    protected CompiledCode createCompiledCode(ResolvedJavaMethod method, CompilationRequest compilationRequest, CompilationResult compilationResult, boolean isDefault, OptionValues options) {
        return new SubstrateCompiledCode(compilationResult);
    }

    @Override
    public void emitCode(CompilationResultBuilder crb, LIR lir, ResolvedJavaMethod installedCodeOwner) {
        crb.emit(lir);
    }

    @Override
    public EconomicSet<Register> translateToCallerRegisters(EconomicSet<Register> calleeRegisters) {
        return calleeRegisters;
    }

    private static boolean useLinearPointerCompression() {
        return SubstrateOptions.SpawnIsolates.getValue();
    }
}
