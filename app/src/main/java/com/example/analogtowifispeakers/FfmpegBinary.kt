package com.example.analogtowifispeakers

import android.content.Context
import android.os.Build
import android.util.Log
import java.io.File

object FfmpegBinary {

    private const val TAG = "FfmpegBinary"

    data class PreparedBinary(
        val binaryFile: File,
        val workingDir: File,
        val environment: Map<String, String>
    )

    fun prepare(context: Context): PreparedBinary {
        val abi = Build.SUPPORTED_ABIS.firstOrNull()
            ?: throw IllegalStateException("No supported ABI found")

        Log.d(TAG, "SUPPORTED_ABIS = ${Build.SUPPORTED_ABIS.joinToString()}")
        Log.d(TAG, "Selected ABI = $abi")

        val nativeLibDirPath = context.applicationInfo.nativeLibraryDir
            ?: throw IllegalStateException("applicationInfo.nativeLibraryDir is null")

        val nativeLibDir = File(nativeLibDirPath)
        Log.d(TAG, "nativeLibraryDir = ${nativeLibDir.absolutePath}")

        val binary = File(nativeLibDir, "libffmpeg.so")

        Log.d(TAG, "Binary path = ${binary.absolutePath}")
        Log.d(TAG, "Binary exists = ${binary.exists()}")
        Log.d(TAG, "Binary canExecute = ${binary.canExecute()}")
        Log.d(TAG, "Binary length = ${if (binary.exists()) binary.length() else -1L}")

        if (!binary.exists()) {
            throw IllegalStateException(
                "FFmpeg binary niet gevonden in nativeLibraryDir. Verwacht: ${binary.absolutePath}"
            )
        }

        val tmpDir = File(context.cacheDir, "ffmpeg-tmp").apply { mkdirs() }

        val env = mutableMapOf<String, String>()
        env["LD_LIBRARY_PATH"] = nativeLibDir.absolutePath
        env["TMPDIR"] = tmpDir.absolutePath

        Log.d(TAG, "LD_LIBRARY_PATH = ${env["LD_LIBRARY_PATH"]}")
        Log.d(TAG, "TMPDIR = ${env["TMPDIR"]}")

        return PreparedBinary(
            binaryFile = binary,
            workingDir = nativeLibDir,
            environment = env
        )
    }
}