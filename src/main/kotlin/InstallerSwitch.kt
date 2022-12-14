import input.PromptType
import org.koin.core.component.KoinComponent
import org.koin.core.component.get
import schemas.InstallerSchema
import schemas.InstallerSchemaImpl

enum class InstallerSwitch : KoinComponent {
    Silent,
    SilentWithProgress { override fun toString() = "Silent with Progress" },
    Custom;

    fun toPromptType(): PromptType {
        return when (this) {
            Silent -> PromptType.SilentSwitch
            SilentWithProgress -> PromptType.SilentWithProgressSwitch
            Custom -> PromptType.CustomSwitch
        }
    }

    fun getLengthBoundary(
        installerSchema: InstallerSchema = get<InstallerSchemaImpl>().installerSchema
    ): Pair<Int, Int> {
        val installerSwitchProperties = installerSchema.definitions.installerSwitches.properties
        return when (this) {
            Silent -> Pair(installerSwitchProperties.silent.minLength, installerSwitchProperties.silent.maxLength)
            SilentWithProgress -> Pair(
                installerSwitchProperties.silentWithProgress.minLength,
                installerSwitchProperties.silentWithProgress.maxLength
            )
            Custom -> Pair(installerSwitchProperties.custom.minLength, installerSwitchProperties.custom.maxLength)
        }
    }
}
