package io.gitlab.arturbosch.detekt

import io.gitlab.arturbosch.detekt.extensions.DetektReport
import io.gitlab.arturbosch.detekt.extensions.DetektReportType
import io.gitlab.arturbosch.detekt.extensions.DetektReports
import io.gitlab.arturbosch.detekt.extensions.FailOnSeverity
import io.gitlab.arturbosch.detekt.invoke.AllRulesArgument
import io.gitlab.arturbosch.detekt.invoke.AutoCorrectArgument
import io.gitlab.arturbosch.detekt.invoke.BasePathArgument
import io.gitlab.arturbosch.detekt.invoke.BaselineArgument
import io.gitlab.arturbosch.detekt.invoke.BuildUponDefaultConfigArgument
import io.gitlab.arturbosch.detekt.invoke.ClasspathArgument
import io.gitlab.arturbosch.detekt.invoke.CliArgument
import io.gitlab.arturbosch.detekt.invoke.ConfigArgument
import io.gitlab.arturbosch.detekt.invoke.CustomReportArgument
import io.gitlab.arturbosch.detekt.invoke.DebugArgument
import io.gitlab.arturbosch.detekt.invoke.DefaultReportArgument
import io.gitlab.arturbosch.detekt.invoke.DetektInvoker
import io.gitlab.arturbosch.detekt.invoke.DetektWorkAction
import io.gitlab.arturbosch.detekt.invoke.DisableDefaultRuleSetArgument
import io.gitlab.arturbosch.detekt.invoke.FailOnSeverityArgument
import io.gitlab.arturbosch.detekt.invoke.InputArgument
import io.gitlab.arturbosch.detekt.invoke.JdkHomeArgument
import io.gitlab.arturbosch.detekt.invoke.JvmTargetArgument
import io.gitlab.arturbosch.detekt.invoke.LanguageVersionArgument
import io.gitlab.arturbosch.detekt.invoke.ParallelArgument
import org.gradle.api.Action
import org.gradle.api.file.ConfigurableFileCollection
import org.gradle.api.file.Directory
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.file.FileTree
import org.gradle.api.file.RegularFile
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.model.ObjectFactory
import org.gradle.api.provider.Property
import org.gradle.api.provider.Provider
import org.gradle.api.provider.ProviderFactory
import org.gradle.api.reporting.ReportingExtension
import org.gradle.api.tasks.CacheableTask
import org.gradle.api.tasks.Classpath
import org.gradle.api.tasks.Console
import org.gradle.api.tasks.IgnoreEmptyDirectories
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.InputFile
import org.gradle.api.tasks.InputFiles
import org.gradle.api.tasks.Internal
import org.gradle.api.tasks.Optional
import org.gradle.api.tasks.OutputFile
import org.gradle.api.tasks.OutputFiles
import org.gradle.api.tasks.PathSensitive
import org.gradle.api.tasks.PathSensitivity
import org.gradle.api.tasks.SkipWhenEmpty
import org.gradle.api.tasks.SourceTask
import org.gradle.api.tasks.TaskAction
import org.gradle.api.tasks.options.Option
import org.gradle.language.base.plugins.LifecycleBasePlugin
import org.gradle.workers.WorkerExecutor
import javax.inject.Inject

@CacheableTask
abstract class Detekt @Inject constructor(
    private val objects: ObjectFactory,
    private val workerExecutor: WorkerExecutor,
    private val providers: ProviderFactory,
) : SourceTask() {

    @get:Classpath
    abstract val detektClasspath: ConfigurableFileCollection

    @get:Classpath
    abstract val pluginClasspath: ConfigurableFileCollection

    @get:InputFile
    @get:Optional
    @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val baseline: RegularFileProperty

    @get:InputFiles
    @get:Optional
    @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val config: ConfigurableFileCollection

    @get:Classpath
    @get:Optional
    abstract val classpath: ConfigurableFileCollection

    @get:Input
    @get:Optional
    abstract val languageVersion: Property<String>

    @get:Input
    @get:Optional
    abstract val jvmTarget: Property<String>

    @get:Internal
    abstract val jdkHome: DirectoryProperty

    @get:Console
    abstract val debug: Property<Boolean>

    @get:Internal
    abstract val parallel: Property<Boolean>

    @get:Input
    abstract val disableDefaultRuleSets: Property<Boolean>

    @get:Input
    abstract val buildUponDefaultConfig: Property<Boolean>

    @get:Input
    abstract val allRules: Property<Boolean>

    @get:Input
    abstract val ignoreFailures: Property<Boolean>

    @get:Input
    @get:Optional
    abstract val failOnSeverity: Property<FailOnSeverity>

    @get:Input
    @get:Option(option = "auto-correct", description = "Allow rules to auto correct code if they support it")
    abstract val autoCorrect: Property<Boolean>

    /**
     * Respect only the file path for incremental build. Using @InputFile respects both file path and content.
     */
    @get:Input
    @get:Optional
    abstract val basePath: Property<String>

    @get:Internal
    var reports: DetektReports = objects.newInstance(DetektReports::class.java)

    @get:Internal
    abstract val reportsDir: DirectoryProperty

    val xmlReportFile: Provider<RegularFile>
        @OutputFile
        @Optional
        get() = getTargetFileProvider(reports.xml)

    val htmlReportFile: Provider<RegularFile>
        @OutputFile
        @Optional
        get() = getTargetFileProvider(reports.html)

    val txtReportFile: Provider<RegularFile>
        @OutputFile
        @Optional
        get() = getTargetFileProvider(reports.txt)

    val sarifReportFile: Provider<RegularFile>
        @OutputFile
        @Optional
        get() = getTargetFileProvider(reports.sarif)

    val mdReportFile: Provider<RegularFile>
        @OutputFile
        @Optional
        get() = getTargetFileProvider(reports.md)

    internal val customReportFiles: ConfigurableFileCollection
        @OutputFiles
        @Optional
        get() = objects.fileCollection().from(reports.custom.mapNotNull { it.outputLocation.asFile.orNull })

    private val defaultReportsDir: Directory = project.layout.buildDirectory.get()
        .dir(ReportingExtension.DEFAULT_REPORTS_DIR_NAME)
        .dir("detekt")

    private val isDryRun = project.providers.gradleProperty(DRY_RUN_PROPERTY)

    init {
        group = LifecycleBasePlugin.VERIFICATION_GROUP
    }

    @get:Internal
    internal val arguments
        get() = listOf(
            InputArgument(source),
            ClasspathArgument(classpath),
            LanguageVersionArgument(languageVersion.orNull),
            JvmTargetArgument(jvmTarget.orNull),
            JdkHomeArgument(jdkHome),
            ConfigArgument(config),
            BaselineArgument(baseline.orNull),
            DefaultReportArgument(DetektReportType.XML, xmlReportFile.orNull),
            DefaultReportArgument(DetektReportType.HTML, htmlReportFile.orNull),
            DefaultReportArgument(DetektReportType.TXT, txtReportFile.orNull),
            DefaultReportArgument(DetektReportType.SARIF, sarifReportFile.orNull),
            DefaultReportArgument(DetektReportType.MD, mdReportFile.orNull),
            DebugArgument(debug.getOrElse(false)),
            ParallelArgument(parallel.getOrElse(false)),
            BuildUponDefaultConfigArgument(buildUponDefaultConfig.getOrElse(false)),
            AllRulesArgument(allRules.getOrElse(false)),
            AutoCorrectArgument(autoCorrect.getOrElse(false)),
            FailOnSeverityArgument(
                ignoreFailures = ignoreFailures.getOrElse(false),
                minSeverity = failOnSeverity.get()
            ),
            BasePathArgument(basePath.orNull),
            DisableDefaultRuleSetArgument(disableDefaultRuleSets.getOrElse(false))
        ).plus(convertCustomReportsToArguments()).flatMap(CliArgument::toArgument)

    @InputFiles
    @SkipWhenEmpty
    @IgnoreEmptyDirectories
    @PathSensitive(PathSensitivity.RELATIVE)
    override fun getSource(): FileTree = super.getSource()

    fun reports(configure: Action<DetektReports>) {
        configure.execute(reports)
    }

    @TaskAction
    fun check() {
        if (providers.gradleProperty(USE_WORKER_API).getOrElse("false") == "true") {
            logger.info("Executing $name using Worker API")
            val workQueue = workerExecutor.processIsolation { workerSpec ->
                workerSpec.classpath.from(detektClasspath)
                workerSpec.classpath.from(pluginClasspath)
            }

            workQueue.submit(DetektWorkAction::class.java) { workParameters ->
                workParameters.arguments.set(arguments)
                workParameters.ignoreFailures.set(ignoreFailures)
                workParameters.dryRun.set(isDryRun.orNull.toBoolean())
                workParameters.taskName.set(name)
            }
        } else {
            logger.info("Executing $name using DetektInvoker")
            DetektInvoker.create(isDryRun = isDryRun.orNull.toBoolean()).invokeCli(
                arguments = arguments,
                ignoreFailures = ignoreFailures.get(),
                classpath = detektClasspath.plus(pluginClasspath),
                taskName = name
            )
        }
    }

    private fun convertCustomReportsToArguments(): List<CustomReportArgument> = reports.custom.map {
        val reportId = it.reportId
        val destination = it.outputLocation.asFile.orNull

        checkNotNull(reportId) { "If a custom report is specified, the reportId must be present" }
        check(!DetektReportType.isWellKnownReportId(reportId)) {
            "The custom report reportId may not be same as one of the default reports"
        }
        checkNotNull(destination) { "If a custom report is specified, the destination must be present" }
        check(!destination.isDirectory) { "If a custom report is specified, the destination must be not a directory" }

        CustomReportArgument(reportId, objects.fileProperty().getOrElse { destination })
    }

    private fun getTargetFileProvider(
        report: DetektReport
    ): RegularFileProperty {
        val isEnabled = report.required.getOrElse(DetektPlugin.DEFAULT_REPORT_ENABLED_VALUE)
        val provider = objects.fileProperty()
        if (isEnabled) {
            val destination = report.outputLocation.orNull ?: reportsDir.getOrElse(defaultReportsDir)
                .file("${DetektReport.DEFAULT_FILENAME}.${report.type.extension}")
            provider.set(destination)
        }
        return provider
    }
}

private const val DRY_RUN_PROPERTY = "detekt-dry-run"
