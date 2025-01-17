package detection.files.msi

import com.sun.jna.Library
import com.sun.jna.Native

@Suppress("FunctionName", "FunctionParameterNaming", "LocalVariableName")
interface LCIDLibrary : Library {
    fun GetLocaleInfoW(Locale: Int, LCType: Int, lpLCData: CharArray?, cchData: Int): Int

    companion object {
        private const val kernel32 = "kernel32"
        val INSTANCE = Native.load(kernel32, LCIDLibrary::class.java) as LCIDLibrary
    }
}
