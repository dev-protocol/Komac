package commands
import ExitCode
import com.github.ajalt.clikt.core.CliktCommand
import com.github.ajalt.clikt.parameters.options.option
import com.github.ajalt.mordant.terminal.Terminal
import data.DefaultLocaleManifestData
import data.GitHubImpl
import data.InstallerManifestData
import data.PreviousManifestData
import data.SharedManifestData
import data.VersionManifestData
import data.VersionUpdateState
import data.installer.Architecture.architecturePrompt
import data.installer.Commands.commandsPrompt
import data.installer.FileExtensions.fileExtensionsPrompt
import data.installer.InstallModes.installModesPrompt
import data.installer.InstallerScope.installerScopePrompt
import data.installer.InstallerSuccessCodes.installerSuccessCodesPrompt
import data.installer.InstallerSwitch.installerSwitchPrompt
import data.installer.InstallerType.installerTypePrompt
import data.installer.ProductCode.productCodePrompt
import data.installer.Protocols.protocolsPrompt
import data.installer.UpgradeBehaviour.upgradeBehaviourPrompt
import data.locale.Author.authorPrompt
import data.locale.Copyright.copyrightPrompt
import data.locale.Description.descriptionPrompt
import data.locale.DescriptionType
import data.locale.License.licensePrompt
import data.locale.LocaleUrl
import data.locale.Moniker.monikerPrompt
import data.locale.Tags.tagsPrompt
import data.shared.Locale.localePrompt
import data.shared.PackageIdentifier.packageIdentifierPrompt
import data.shared.PackageName.packageNamePrompt
import data.shared.PackageVersion.packageVersionPrompt
import data.shared.Publisher.publisherPrompt
import data.shared.Url.installerDownloadPrompt
import data.shared.Url.localeUrlPrompt
import input.FileWriter.writeFiles
import input.InstallerSwitch
import input.LocaleType
import input.ManifestResultOption
import input.Prompts.pullRequestPrompt
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.koin.core.component.KoinComponent
import org.koin.core.component.get
import org.koin.core.component.inject
import schemas.Schema
import schemas.Schemas
import schemas.SchemasImpl
import schemas.manifest.LocaleManifest
import schemas.manifest.YamlConfig
import java.io.IOException
import kotlin.system.exitProcess

class NewManifest : CliktCommand(name = "new"), KoinComponent {
    private val installerManifestData: InstallerManifestData by inject()
    private val defaultLocalManifestData: DefaultLocaleManifestData by inject()
    private val versionManifestData: VersionManifestData by inject()
    private val sharedManifestData: SharedManifestData by inject()
    private var previousManifestData: PreviousManifestData? = null
    private lateinit var files: List<Pair<String, String?>>
    private val githubImpl: GitHubImpl by inject()
    private val manifestVersion: String? by option()

    override fun run(): Unit = runBlocking {
        manifestVersion?.let { get<SchemasImpl>().manifestOverride = it }
        with(currentContext.terminal) {
            packageIdentifierPrompt()
            launch { if (sharedManifestData.updateState != VersionUpdateState.NewPackage) previousManifestData = get() }
            launch {
                packageVersionPrompt()
                do {
                    installerDownloadPrompt()
                    architecturePrompt()
                    installerTypePrompt()
                    InstallerSwitch.values().forEach { installerSwitchPrompt(it) }
                    localePrompt(LocaleType.Installer)
                    productCodePrompt()
                    installerScopePrompt()
                    upgradeBehaviourPrompt()
                    installerManifestData.addInstaller()
                    val shouldContinue = confirm(colors.brightYellow(additionalInstallerInfo))
                        ?: exitProcess(ExitCode.CtrlC.code)
                } while (shouldContinue)
                fileExtensionsPrompt()
                protocolsPrompt()
                commandsPrompt()
                installerSuccessCodesPrompt()
                installModesPrompt()
                localePrompt(LocaleType.Package)
                publisherPrompt()
                packageNamePrompt()
                monikerPrompt()
                localeUrlPrompt(LocaleUrl.PublisherUrl)
                localeUrlPrompt(LocaleUrl.PublisherSupportUrl)
                localeUrlPrompt(LocaleUrl.PublisherPrivacyUrl)
                authorPrompt()
                localeUrlPrompt(LocaleUrl.PackageUrl)
                licensePrompt()
                localeUrlPrompt(LocaleUrl.LicenseUrl)
                copyrightPrompt()
                localeUrlPrompt(LocaleUrl.CopyrightUrl)
                tagsPrompt()
                DescriptionType.values().forEach { descriptionPrompt(it) }
                localeUrlPrompt(LocaleUrl.ReleaseNotesUrl)
                createFiles()
                pullRequestPrompt(sharedManifestData).also { manifestResultOption ->
                    when (manifestResultOption) {
                        ManifestResultOption.PullRequest -> {
                            commit()
                            pullRequest()
                        }
                        ManifestResultOption.WriteToFiles -> writeFiles(files)
                        else -> echo("Exiting")
                    }
                }
            }
        }
    }

    private suspend fun createFiles() {
        files = listOf(
            githubImpl.installerManifestName to installerManifestData.createInstallerManifest(),
            githubImpl.defaultLocaleManifestName to defaultLocalManifestData.createDefaultLocaleManifest(),
            githubImpl.versionManifestName to versionManifestData.createVersionManifest(),
        ) + previousManifestData?.remoteLocaleData?.map { localeManifest ->
            githubImpl.getLocaleManifestName(localeManifest.packageLocale) to localeManifest.copy(
                packageIdentifier = sharedManifestData.packageIdentifier,
                packageVersion = sharedManifestData.packageVersion,
                manifestVersion = Schemas.manifestVersion
            ).let {
                Schemas.buildManifestString(
                    Schema.Locale,
                    YamlConfig.default.encodeToString(LocaleManifest.serializer(), it)
                )
            }
        }.orEmpty()
    }

    private suspend fun Terminal.commit() {
        previousManifestData?.apply {
            remoteVersionDataJob.join()
            remoteLocaleDataJob.join()
            remoteDefaultLocaleDataJob.join()
        }
        val repository = githubImpl.getWingetPkgsFork(terminal = this) ?: return
        val ref = githubImpl.createBranchFromDefaultBranch(repository = repository, terminal = this) ?: return
        githubImpl.commitFiles(
            repository = repository,
            branch = ref,
            files = files.map { "${githubImpl.baseGitHubPath}/${it.first}" to it.second }
        )
    }

    private suspend fun Terminal.pullRequest() {
        val ghRepository = githubImpl.getMicrosoftWingetPkgs() ?: return
        try {
            ghRepository.createPullRequest(
                /* title = */ githubImpl.getCommitTitle(),
                /* head = */ "${githubImpl.github.await().myself.login}:${githubImpl.pullRequestBranch?.ref}",
                /* base = */ ghRepository.defaultBranch,
                /* body = */ githubImpl.getPullRequestBody()
            ).also { success("Pull request created: ${it.htmlUrl}") }
        } catch (ioException: IOException) {
            danger(ioException.message ?: "Failed to create pull request")
        }
    }

    companion object {
        private const val additionalInstallerInfo = "Do you want to create another installer?"
    }
}
