package data

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import ktor.Ktor
import org.kohsuke.github.GHContent
import org.koin.core.annotation.Single
import org.koin.core.component.KoinComponent
import org.koin.core.component.get
import schemas.DefaultLocaleManifest
import schemas.InstallerManifest
import schemas.LocaleManifest
import schemas.VersionManifest
import java.io.BufferedReader
import java.io.InputStreamReader

@Single
class PreviousManifestData : KoinComponent {
    var sharedManifestData: SharedManifestData = get()
    var remoteInstallerData: InstallerManifest? = null
    private val githubImpl = get<GitHubImpl>()
    private val repository = githubImpl.getMicrosoftWingetPkgs()
    private val directoryPath: MutableList<GHContent>? = repository
        ?.getDirectoryContent(
            "${Ktor.getDirectoryPath(sharedManifestData.packageIdentifier)}/${sharedManifestData.latestVersion}"
        )
    var remoteInstallerDataJob: Job = CoroutineScope(Dispatchers.IO).launch {
        repository?.getFileContent(
            directoryPath?.first { it.name == "${sharedManifestData.packageIdentifier}.installer.yaml" }?.path
        )?.read()
            ?.let { BufferedReader(InputStreamReader(it)) }
            ?.use { reader ->
                var line: String?
                buildString {
                    while (reader.readLine().also { line = it } != null) {
                        appendLine(line)
                    }
                }.let {
                    remoteInstallerData = YamlConfig.installer.decodeFromString(
                        InstallerManifest.serializer(),
                        it
                    )
                }
            }
    }
    var remoteVersionDataJob: Job = CoroutineScope(Dispatchers.IO).launch(Dispatchers.IO) {
        repository?.getFileContent(
            directoryPath?.first { it.name == "${sharedManifestData.packageIdentifier}.yaml" }?.path
        )
            ?.read()
            ?.let { BufferedReader(InputStreamReader(it)) }
            ?.use { reader ->
                var line: String?
                buildString {
                    while (reader.readLine().also { line = it } != null) {
                        appendLine(line)
                    }
                }.let {
                    remoteVersionData = YamlConfig.other.decodeFromString(VersionManifest.serializer(), it)
                }
            }
    }.also { job ->
        job.invokeOnCompletion {
            remoteVersionData?.defaultLocale?.let {
                sharedManifestData.defaultLocale = it
            }
        }
    }
    var remoteDefaultLocaleData: DefaultLocaleManifest? = null
    var remoteDefaultLocaleDataJob: Job = CoroutineScope(Dispatchers.IO).launch(Dispatchers.IO) {
        remoteVersionDataJob.join()
        repository?.getFileContent(
            directoryPath?.first {
                it.name == "${sharedManifestData.packageIdentifier}.locale.${sharedManifestData.defaultLocale}.yaml"
            }?.path
        )?.read()
            ?.let { BufferedReader(InputStreamReader(it)) }
            ?.use { reader ->
                var line: String?
                buildString {
                    while (reader.readLine().also { line = it } != null) {
                        appendLine(line)
                    }
                }.let {
                    remoteDefaultLocaleData = YamlConfig.other.decodeFromString(
                        DefaultLocaleManifest.serializer(),
                        it
                    )
                }
            }
    }
    var remoteLocaleData: List<LocaleManifest>? = null
    var remoteLocaleDataJob: Job = CoroutineScope(Dispatchers.IO).launch {
        remoteVersionDataJob.join()
        directoryPath
            ?.filter {
                it.name.matches(Regex("${Regex.escape(sharedManifestData.packageIdentifier)}.locale\\..*\\.yaml"))
            }
            ?.filterNot { it.name.contains(sharedManifestData.defaultLocale) }
            ?.forEach { ghContent ->
                repository?.getFileContent(ghContent.path)
                    ?.read()
                    ?.let { BufferedReader(InputStreamReader(it)) }
                    ?.use { reader ->
                        var line: String?
                        buildString {
                            while (reader.readLine().also { line = it } != null) {
                                appendLine(line)
                            }
                        }.let {
                            remoteLocaleData = if (remoteLocaleData == null) {
                                listOf(
                                    YamlConfig.other.decodeFromString(LocaleManifest.serializer(), it)
                                )
                            } else {
                                remoteLocaleData!! + YamlConfig.other.decodeFromString(
                                    LocaleManifest.serializer(),
                                    it
                                )
                            }
                        }
                    }
            }
    }
    var remoteVersionData: VersionManifest? = null
}