package com.nestgallery.viewer.data.nsfw

import ai.onnxruntime.OrtSession
import android.app.ActivityManager
import android.content.Context
import com.nestgallery.viewer.data.face.Ort
import java.io.File

/**
 * Where the NSFW model runs. The NPU and GPU go through Qualcomm's QNN runtime (ONNX Runtime's QNN execution
 * provider, `onnxruntime-android-qnn`), so they only exist on Snapdragon phones; elsewhere session creation fails and
 * the scanner falls back to the CPU.
 */
enum class NsfwAccelerator(val title: String, val description: String) {
    AUTO("Auto", "Tries the NPU, then the GPU, then the CPU, and uses the first that works."),
    NPU("NPU (Hexagon)", "Snapdragon's neural processor, through Qualcomm QNN, at fp16 precision. Usually by far the fastest. " +
        "The first scan compiles the model for the chip (up to a minute); later scans reuse it."),
    GPU("GPU (Adreno)", "Snapdragon's graphics processor, through Qualcomm QNN. Experimental."),
    CPU("CPU", "Runs everywhere. Several photos at once, one core each.");

    companion object {
        fun byName(name: String?): NsfwAccelerator = entries.firstOrNull { it.name == name } ?: AUTO
    }
}

/** How a detector session was actually set up (shown in Settings and on the NSFW screen). */
class NsfwBackend(
    val accelerator: NsfwAccelerator,
    /** Photos analysed at once by the scanner. */
    val workers: Int,
    val label: String
)

internal object NsfwSessions {
    /**
     * Session options for [model] on [accel] (never AUTO). NPU / GPU need fixed input shapes: the dynamic height and
     * width are pinned to a [NsfwModel.inputSize] square (the photo is padded into it, exactly like nudenet.py).
     * @param contextFile where the NPU's compiled graph is cached (created on first use)
     * @param strict refuse to create the session unless QNN takes every node. Without it, ONNX Runtime silently runs
     *   whatever QNN rejects - all of it, if QNN can't even load - on the CPU, which looked like a working (slow) NPU.
     */
    fun options(context: Context, model: NsfwModel, accel: NsfwAccelerator, contextFile: File?, strict: Boolean = false): OrtSession.SessionOptions = when (accel) {
        NsfwAccelerator.CPU, NsfwAccelerator.AUTO -> Ort.options()      // 1 thread: the scanner runs several photos at once
        NsfwAccelerator.NPU, NsfwAccelerator.GPU -> OrtSession.SessionOptions().apply {
            setOptimizationLevel(OrtSession.SessionOptions.OptLevel.ALL_OPT)
            setIntraOpNumThreads(2)                                         // for any nodes left on the CPU
            if (strict) addConfigEntry("session.disable_cpu_ep_fallback", "1")
            setSymbolicDimensionValue("batch", 1)
            setSymbolicDimensionValue("height", model.inputSize.toLong())
            setSymbolicDimensionValue("width", model.inputSize.toLong())
            val libDir = context.applicationInfo.nativeLibraryDir
            val qnn = HashMap<String, String>()
            if (accel == NsfwAccelerator.NPU) {
                // The DSP loads its half of QNN (libQnnHtpV*Skel.so) from ADSP_LIBRARY_PATH.
                setDspLibraryPath(libDir)
                qnn["backend_path"] = "$libDir/libQnnHtp.so"
                qnn["enable_htp_fp16_precision"] = "1"                     // float model, fp16 on the HTP: no quantisation
                qnn["htp_performance_mode"] = "burst"
                qnn["htp_graph_finalization_optimization_mode"] = "3"
                if (contextFile != null && !contextFile.exists()) {
                    // first time: compile, and save the compiled graph so later sessions load in a second
                    addConfigEntry("ep.context_enable", "1")
                    addConfigEntry("ep.context_embed_mode", "1")
                    addConfigEntry("ep.context_file_path", contextFile.absolutePath)
                }
            } else {
                qnn["backend_path"] = "$libDir/libQnnGpu.so"
            }
            addQnn(qnn)
        }
    }

    private fun setDspLibraryPath(libDir: String) {
        val path = "$libDir;/system/lib/rfsa/adsp;/system/vendor/lib/rfsa/adsp;/vendor/lib/rfsa/adsp;/dsp"
        try { android.system.Os.setenv("ADSP_LIBRARY_PATH", path, true) } catch (_: Exception) {}
    }

    /** The NPU's cached compiled graph for [model] (tied to the ONNX Runtime / QNN version via [CACHE_VERSION]). */
    fun contextFile(context: Context, model: NsfwModel): File =
        File(context.filesDir, "qnn_ctx/${model.asset.removeSuffix(".onnx")}_$CACHE_VERSION.onnx").apply { parentFile?.mkdirs() }

    /** True if [file] (an EP context model) holds at least one QNN-compiled partition. */
    fun hasQnnPartition(file: File): Boolean {
        if (!file.exists() || file.length() == 0L) return false
        val needle = "EPContext".toByteArray()
        file.inputStream().buffered().use { input ->
            var matched = 0
            while (true) {
                val b = input.read()
                if (b < 0) return false
                matched = if (b.toByte() == needle[matched]) matched + 1 else if (b.toByte() == needle[0]) 1 else 0
                if (matched == needle.size) return true
            }
        }
    }

    /**
     * The app's own recent ONNX Runtime / QNN log lines (warnings and errors). QNN explains why it rejected a model
     * only in logcat, which is unreachable without a computer; an app may read its own log, so the speed test shows it.
     */
    fun recentQnnLog(maxLines: Int = 25): String = try {
        val proc = ProcessBuilder("logcat", "-d", "-t", "2000", "--pid=${android.os.Process.myPid()}", "*:W")
            .redirectErrorStream(true).start()
        val lines = proc.inputStream.bufferedReader().readLines()
        proc.destroy()
        lines.filter { l -> listOf("qnn", "onnxruntime", "htp", "fastrpc", "dsp", "opencl").any { l.contains(it, ignoreCase = true) } }
            .takeLast(maxLines)
            .joinToString("\n")
    } catch (e: Exception) { "" }

    /** Bump with the onnxruntime-android-qnn / qnn-runtime versions: compiled graphs don't carry across them. */
    private const val CACHE_VERSION = "ort1.22-qnn2.33-v3"

    /**
     * Photos analysed at once. CPU: one per core pair (each run is single-threaded); the 640 px model holds far more
     * memory per run, so phones with little RAM run fewer. NPU / GPU: two, so one photo is prepared on the CPU
     * while the other runs (the accelerator itself runs them one at a time).
     */
    fun workers(context: Context, model: NsfwModel, accel: NsfwAccelerator): Int {
        if (accel == NsfwAccelerator.NPU || accel == NsfwAccelerator.GPU) return 2
        val cores = Runtime.getRuntime().availableProcessors()
        if (model.inputSize <= 320) return (cores / 2).coerceIn(1, 4)
        val mem = ActivityManager.MemoryInfo().also { (context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager).getMemoryInfo(it) }
        val gb = mem.totalMem / (1024L * 1024 * 1024)
        return when {
            gb >= 6 -> (cores / 2).coerceIn(1, 4)
            gb >= 4 -> 2
            else -> 1
        }
    }
}
