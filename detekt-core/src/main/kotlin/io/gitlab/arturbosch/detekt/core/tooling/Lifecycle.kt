package io.gitlab.arturbosch.detekt.core.tooling

import io.gitlab.arturbosch.detekt.api.Config
import io.gitlab.arturbosch.detekt.api.Detektion
import io.gitlab.arturbosch.detekt.api.FileProcessListener
import io.gitlab.arturbosch.detekt.api.Finding
import io.gitlab.arturbosch.detekt.api.RuleSet
import io.gitlab.arturbosch.detekt.api.RuleSetProvider
import io.gitlab.arturbosch.detekt.core.Analyzer
import io.gitlab.arturbosch.detekt.core.DetektResult
import io.gitlab.arturbosch.detekt.core.FileProcessorLocator
import io.gitlab.arturbosch.detekt.core.ProcessingSettings
import io.gitlab.arturbosch.detekt.core.config.validation.checkConfiguration
import io.gitlab.arturbosch.detekt.core.extensions.handleReportingExtensions
import io.gitlab.arturbosch.detekt.core.generateBindingContext
import io.gitlab.arturbosch.detekt.core.reporting.OutputFacade
import io.gitlab.arturbosch.detekt.core.rules.createRuleProviders
import io.gitlab.arturbosch.detekt.core.util.PerformanceMonitor.Phase
import io.gitlab.arturbosch.detekt.core.util.getOrCreateMonitor
import org.jetbrains.kotlin.psi.KtFile
import org.jetbrains.kotlin.resolve.BindingContext

internal interface Lifecycle {

    val baselineConfig: Config
    val settings: ProcessingSettings
    val parsingStrategy: ParsingStrategy
    val bindingProvider: (files: List<KtFile>) -> BindingContext
    val processorsProvider: () -> List<FileProcessListener>
    val ruleSetsProvider: () -> List<RuleSetProvider>

    private fun <R> measure(phase: Phase, block: () -> R): R = settings.getOrCreateMonitor().measure(phase, block)

    fun analyze(): Detektion {
        measure(Phase.ValidateConfig) { checkConfiguration(settings, baselineConfig) }
        val filesToAnalyze = measure(Phase.Parsing) { parsingStrategy.invoke(settings) }
        val bindingContext = measure(Phase.Binding) { bindingProvider.invoke(filesToAnalyze) }
        val (processors, ruleSets) = measure(Phase.LoadingExtensions) {
            processorsProvider.invoke() to ruleSetsProvider.invoke()
        }

        val result = measure(Phase.Analyzer) {
            val analyzer = Analyzer(settings, ruleSets, processors)
            processors.forEach { it.onStart(filesToAnalyze, bindingContext) }
            val findings: Map<RuleSet.Id, List<Finding>> = analyzer.run(filesToAnalyze, bindingContext)
            val result: Detektion = DetektResult(findings.toSortedMap { o1, o2 -> o1.value.compareTo(o2.value) })
            processors.forEach { it.onFinish(filesToAnalyze, result, bindingContext) }
            result
        }

        return measure(Phase.Reporting) {
            val finalResult = handleReportingExtensions(settings, result)
            OutputFacade(settings).run(finalResult)
            finalResult
        }
    }
}

internal class DefaultLifecycle(
    override val baselineConfig: Config,
    override val settings: ProcessingSettings,
    override val parsingStrategy: ParsingStrategy,
    override val bindingProvider: (files: List<KtFile>) -> BindingContext =
        { generateBindingContext(settings.environment, settings.classpath, it, settings::debug) },
    override val processorsProvider: () -> List<FileProcessListener> =
        { FileProcessorLocator(settings).load() },
    override val ruleSetsProvider: () -> List<RuleSetProvider> =
        { settings.createRuleProviders() }
) : Lifecycle
