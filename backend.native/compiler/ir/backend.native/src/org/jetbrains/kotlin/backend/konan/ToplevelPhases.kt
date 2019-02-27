package org.jetbrains.kotlin.backend.konan

import org.jetbrains.kotlin.backend.common.LoggingContext
import org.jetbrains.kotlin.backend.common.extensions.IrGenerationExtension
import org.jetbrains.kotlin.backend.common.phaser.*
import org.jetbrains.kotlin.backend.konan.descriptors.isForwardDeclarationModule
import org.jetbrains.kotlin.backend.konan.descriptors.konanLibrary
import org.jetbrains.kotlin.backend.konan.ir.KonanSymbols
import org.jetbrains.kotlin.backend.konan.llvm.*
import org.jetbrains.kotlin.backend.konan.lower.ExpectToActualDefaultValueCopier
import org.jetbrains.kotlin.backend.konan.serialization.*
import org.jetbrains.kotlin.cli.common.messages.AnalyzerWithCompilerReport
import org.jetbrains.kotlin.config.CommonConfigurationKeys
import org.jetbrains.kotlin.config.languageVersionSettings
import org.jetbrains.kotlin.ir.IrElement
import org.jetbrains.kotlin.ir.declarations.*
import org.jetbrains.kotlin.ir.expressions.IrCall
import org.jetbrains.kotlin.ir.expressions.impl.IrVarargImpl
import org.jetbrains.kotlin.ir.util.*
import org.jetbrains.kotlin.ir.visitors.IrElementVisitorVoid
import org.jetbrains.kotlin.ir.visitors.acceptChildrenVoid
import org.jetbrains.kotlin.ir.visitors.acceptVoid
import org.jetbrains.kotlin.konan.target.CompilerOutputKind
import org.jetbrains.kotlin.psi2ir.Psi2IrConfiguration
import org.jetbrains.kotlin.psi2ir.Psi2IrTranslator
import java.util.Collections.emptySet

internal fun konanUnitPhase(
        name: String,
        description: String,
        prerequisite: Set<AnyNamedPhase> = emptySet(),
        op: Context.() -> Unit
) = namedOpUnitPhase(name, description, prerequisite, op)

internal val frontendPhase = konanUnitPhase(
        op = {
            val environment = environment
            val analyzerWithCompilerReport = AnalyzerWithCompilerReport(messageCollector,
                    environment.configuration.languageVersionSettings)

            // Build AST and binding info.
            analyzerWithCompilerReport.analyzeAndReport(environment.getSourceFiles()) {
                TopDownAnalyzerFacadeForKonan.analyzeFiles(environment.getSourceFiles(), this)
            }
            if (analyzerWithCompilerReport.hasErrors()) {
                throw KonanCompilationException()
            }
            moduleDescriptor = analyzerWithCompilerReport.analysisResult.moduleDescriptor
            bindingContext = analyzerWithCompilerReport.analysisResult.bindingContext
        },
        name = "Frontend",
        description = "Frontend builds AST"
)

// FIXME: a temporary workaround with JVM-inliner issue. It's unable to obtain compiled function body
internal inline fun <reified T : IrElement> T.deepCopyWithSymbols(
        initialParent: IrDeclarationParent? = null,
        descriptorRemapper: DescriptorsRemapper = DescriptorsRemapper.DEFAULT
): T {
    val symbolRemapper = DeepCopySymbolRemapper(descriptorRemapper)
    acceptVoid(symbolRemapper)
    val typeRemapper = DeepCopyTypeRemapper(symbolRemapper)
    return transform(DeepCopyIrTreeWithSymbols(symbolRemapper, typeRemapper), null).patchDeclarationParents(initialParent) as T
}

internal val psiToIrPhase = konanUnitPhase(
        op = {
            // Translate AST to high level IR.
            val translator = Psi2IrTranslator(config.configuration.languageVersionSettings,
                    Psi2IrConfiguration(false))
            val generatorContext = translator.createGeneratorContext(moduleDescriptor, bindingContext)
            @Suppress("DEPRECATION")
            psi2IrGeneratorContext = generatorContext

            val forwardDeclarationsModuleDescriptor = moduleDescriptor.allDependencyModules.firstOrNull { it.isForwardDeclarationModule }

            val deserializer = KonanIrModuleDeserializer(
                    moduleDescriptor,
                    this as LoggingContext,
                    generatorContext.irBuiltIns,
                    generatorContext.symbolTable,
                    forwardDeclarationsModuleDescriptor
            )

            val modules = mutableMapOf<String, IrModuleFragment>()

            var dependenciesCount = 0
            while (true) {
                // context.config.librariesWithDependencies could change at each iteration.
                val dependencies = moduleDescriptor.allDependencyModules.filter {
                    config.librariesWithDependencies(moduleDescriptor).contains(it.konanLibrary)
                }
                for (dependency in dependencies) {
                    val konanLibrary = dependency.konanLibrary!!
                    if (modules.containsKey(konanLibrary.libraryName)) continue
                    konanLibrary.irHeader?.let { header ->
                        // TODO: consider skip deserializing explicitly exported declarations for libraries.
                        // Now it's not valid because of all dependencies that must be computed.
                        val deserializationStrategy = DeserializationStrategy.EXPLICITLY_EXPORTED
                        modules[konanLibrary.libraryName] = deserializer.deserializeIrModuleHeader(dependency, header, deserializationStrategy)
                    }
                }
                if (dependencies.size == dependenciesCount) break
                dependenciesCount = dependencies.size
            }


            val symbols = KonanSymbols(this, generatorContext.symbolTable, generatorContext.symbolTable.lazyWrapper)
            val module = translator.generateModuleFragment(generatorContext, environment.getSourceFiles(), deserializer)

            modules.values.forEach {
                it.patchDeclarationParents()
            }

            // TODO: Move to a separate phase. May be this should be a part of PsiToIr?
            fun fixAnnotation(call: IrCall) {
                call.accept(object: IrElementVisitorVoid {
                    override fun visitElement(element: IrElement) {
                        element.acceptChildrenVoid(this)
                    }

                    override fun visitCall(expression: IrCall) {
                        expression.symbol.owner.valueParameters.forEach {
                            if (expression.getValueArgument(it.index) == null)
                                expression.putValueArgument(it.index, it.defaultValue?.expression?.deepCopyWithSymbols() ?: IrVarargImpl(
                                        startOffset = expression.startOffset,
                                        endOffset = expression.endOffset,
                                        type = it.type,
                                        varargElementType = it.varargElementType!!
                                ))
                        }
                        super.visitCall(expression)
                    }
                }, data = null)
            }

            module.acceptChildrenVoid(object: IrElementVisitorVoid {
                override fun visitElement(element: IrElement) {
                    element.acceptChildrenVoid(this)
                    if (element is IrAnnotationContainer) {
                        element.annotations.forEach { fixAnnotation(it) }
                    }
                }
            })

            irModule = module
            irModules = modules
            ir.symbols = symbols

//        validateIrModule(this, module)
        },
        name = "Psi2Ir",
        description = "Psi to IR conversion"
)

internal val irGeneratorPluginsPhase = konanUnitPhase(
        op = {
            val extensions = IrGenerationExtension.getInstances(config.project)
            extensions.forEach { extension ->
                irModule!!.files.forEach {
                    irFile -> extension.generate(irFile, this, bindingContext)
                }
            }
        },
        name = "IrGeneratorPlugins",
        description = "Plugged-in ir generators"
)

// TODO: We copy default value expressions from expects to actuals before IR serialization,
// because the current infrastructure doesn't allow us to get them at deserialization stage.
// That requires some design and implementation work.
internal val copyDefaultValuesToActualPhase = konanUnitPhase(
        op = {
            irModule!!.files.forEach(ExpectToActualDefaultValueCopier(this)::lower)
        },
        name = "CopyDefaultValuesToActual",
        description = "Copy default values from expect to actual declarations"
)

internal val patchDeclarationParents0Phase = konanUnitPhase(
        op = { irModule!!.patchDeclarationParents() }, // why do we need it?
        name = "PatchDeclarationParents0",
        description = "Patch declaration parents"
)

internal val serializerPhase = konanUnitPhase(
        op = {
            val declarationTable = DeclarationTable(irModule!!.irBuiltins, DescriptorTable())
            val serializedIr = IrModuleSerializer(this, declarationTable).serializedIrModule(irModule!!)
            val serializer = KonanSerializationUtil(this, config.configuration.get(CommonConfigurationKeys.METADATA_VERSION)!!, declarationTable)
            serializedLinkData =
                    serializer.serializeModule(moduleDescriptor, /*if (!config.isInteropStubs) serializedIr else null*/ serializedIr)
        },
        name = "Serializer",
        description = "Serialize descriptor tree and inline IR bodies"
)

internal val setUpLinkStagePhase = konanUnitPhase(
        op =  { linkStage = LinkStage(this) },
        name = "SetUpLinkStage",
        description = "Set up link stage"
)

internal val objectFilesPhase = konanUnitPhase(
        op = { linkStage.makeObjectFiles() },
        name = "ObjectFiles",
        description = "Bitcode to object file"
)

internal val linkerPhase = konanUnitPhase(
        op = { linkStage.linkStage() },
        name = "Linker",
        description = "Linker"
)

internal val linkPhase = namedUnitPhase(
        name = "Link",
        description = "Link stage",
        lower = setUpLinkStagePhase then
                objectFilesPhase then
                linkerPhase
)

internal val allLoweringsPhase = namedIrModulePhase(
        name = "IrLowering",
        description = "IR Lowering",
        lower = removeExpectDeclarationsPhase then
                lowerBeforeInlinePhase then
                inlinePhase then
                lowerAfterInlinePhase then
                interopPart1Phase then
                patchDeclarationParents1Phase then
                performByIrFile(
                        name = "IrLowerByFile",
                        description = "IR Lowering by file",
                        lower = lateinitPhase then
                                stringConcatenationPhase then
                                enumConstructorsPhase then
                                initializersPhase then
                                sharedVariablesPhase then
                                localFunctionsPhase then
                                tailrecPhase then
                                defaultParameterExtentPhase then
                                innerClassPhase then
                                forLoopsPhase then
                                dataClassesPhase then
                                builtinOperatorPhase then
                                finallyBlocksPhase then
                                testProcessorPhase then
                                enumClassPhase then
                                delegationPhase then
                                callableReferencePhase then
                                interopPart2Phase then
                                varargPhase then
                                compileTimeEvaluatePhase then
                                coroutinesPhase then
                                typeOperatorPhase then
                                bridgesPhase then
                                autoboxPhase then
                                returnsInsertionPhase
                ) then
                checkDeclarationParentsPhase
//                                                validateIrModulePhase // Temporarily disabled until moving to new IR finished.
)

internal val dependenciesLowerPhase = SameTypeNamedPhaseWrapper(
        name = "LowerLibIR",
        description = "Lower library's IR",
        prerequisite = emptySet(),
        dumperVerifier = EmptyDumperVerifier(),
        lower = object : CompilerPhase<Context, IrModuleFragment, IrModuleFragment> {
            override fun invoke(phaseConfig: PhaseConfig, phaserState: PhaserState, context: Context, irModule: IrModuleFragment): IrModuleFragment {

                val files = mutableListOf<IrFile>()
                files += irModule.files
                irModule.files.clear()

                // TODO: KonanLibraryResolver.TopologicalLibraryOrder actually returns libraries in the reverse topological order.
                context.librariesWithDependencies
                        .reversed()
                        .forEach {
                            val libModule = context.irModules[it.libraryName]
                                    ?: return@forEach

                            irModule.files += libModule.files
                            allLoweringsPhase.invoke(phaseConfig, phaserState, context, irModule)

                            irModule.files.clear()
                        }

                // Save all files for codegen in reverse topological order.
                // This guarantees that libraries initializers are emitted in correct order.
                context.librariesWithDependencies
                        .forEach {
                            val libModule = context.irModules[it.libraryName]
                                    ?: return@forEach
                            irModule.files += libModule.files
                        }
                irModule.files += files

                return irModule
            }

        })

internal val bitcodePhase = namedIrModulePhase(
        name = "Bitcode",
        description = "LLVM Bitcode generation",
        lower = contextLLVMSetupPhase then
                RTTIPhase then
                generateDebugInfoHeaderPhase then
                deserializeDFGPhase then
                devirtualizationPhase then
                escapeAnalysisPhase then
                codegenPhase then
                finalizeDebugInfoPhase then
                cStubsPhase
)

internal val toplevelPhase = namedUnitPhase(
        name = "Compiler",
        description = "The whole compilation process",
        lower = frontendPhase then
                psiToIrPhase then
                irGeneratorPluginsPhase then
                copyDefaultValuesToActualPhase then
                patchDeclarationParents0Phase then
                serializerPhase then
                namedUnitPhase(
                        name = "Backend",
                        description = "All backend",
                        lower = takeFromContext<Context, Unit, IrModuleFragment> { it.irModule!! } then
                                allLoweringsPhase then // Lower current module first.
                                dependenciesLowerPhase then // Then lower all libraries in topological order.
                                                            // With that we guarantee that inline functions are unlowered while being inlined.
                                moduleIndexForCodegenPhase then
                                buildDFGPhase then
                                serializeDFGPhase then
                                bitcodePhase then
                                produceOutputPhase then
                                verifyBitcodePhase then
                                printBitcodePhase then
                                unitSink()
                ) then
                linkPhase
)

internal fun PhaseConfig.konanPhasesConfig(config: KonanConfig) {
    with(config.configuration) {
        disable(compileTimeEvaluatePhase)
        disable(buildDFGPhase)
        disable(deserializeDFGPhase)
        disable(devirtualizationPhase)
        disable(escapeAnalysisPhase)
        disable(serializeDFGPhase)

        // Don't serialize anything to a final executable.
        switch(serializerPhase, config.produce == CompilerOutputKind.LIBRARY)
        switch(dependenciesLowerPhase, config.produce != CompilerOutputKind.LIBRARY)
        switch(bitcodePhase, config.produce != CompilerOutputKind.LIBRARY)
        switch(linkPhase, config.produce.isNativeBinary)
        switch(testProcessorPhase, getNotNull(KonanConfigKeys.GENERATE_TEST_RUNNER) != TestRunnerKind.NONE)
    }
}