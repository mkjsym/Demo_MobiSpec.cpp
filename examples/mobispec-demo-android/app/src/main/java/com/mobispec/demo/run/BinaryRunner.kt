package com.mobispec.demo.run

import android.content.Context
import android.os.SystemClock
import android.system.Os
import android.system.OsConstants
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.buffer
import kotlinx.coroutines.flow.callbackFlow
import java.io.File
import java.io.FileInputStream
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.concurrent.thread

/**
 * Runs one driver as a child process. The binary is the experiments' own build, packaged as
 * lib<name>.so and executed from nativeLibraryDir; events arrive on a FIFO (MOBISPEC_EVENTS) so
 * the driver's stdout/stderr stay what they are in the sweeps.
 */
class BinaryRunner(private val context: Context) {

    data class Spec(
        val bin: String,
        val args: List<String>,
        val env: Map<String, String>,
        val affinity: String? = null,
        val tree: Boolean = false,
    )

    val libDir: String get() = context.applicationInfo.nativeLibraryDir

    fun command(spec: Spec): List<String> = buildList {
        if (spec.affinity != null) { add("/system/bin/taskset"); add(spec.affinity) }
        add("$libDir/${spec.bin}")
        addAll(spec.args)
    }

    fun run(spec: Spec): Flow<RunEvent> = callbackFlow {
        val work = File(context.cacheDir, "run").apply { mkdirs() }
        val fifo = File(work, "events-${System.nanoTime()}.fifo")
        try {
            Os.mkfifo(fifo.path, "600".toInt(8))
        } catch (e: Exception) {
            trySend(RunEvent.Failed("mkfifo failed: ${e.message}")); close(); return@callbackFlow
        }

        val pb = ProcessBuilder(command(spec)).directory(work)
        pb.environment().apply {
            // the NPU skel is looked up through ADSP_LIBRARY_PATH (';'-separated, FastRPC convention)
            put("LD_LIBRARY_PATH", "$libDir:/vendor/lib64")
            put("ADSP_LIBRARY_PATH", "$libDir;/vendor/lib/rfsa/adsp;/vendor/dsp/cdsp;/vendor/dsp")
            put("MOBISPEC_EVENTS", fifo.path)
            if (spec.tree) put("MOBISPEC_EVENTS_TREE", "1")
            putAll(spec.env)
        }

        val t0 = SystemClock.elapsedRealtime()
        val proc = try {
            pb.start()
        } catch (e: Exception) {
            fifo.delete()
            trySend(RunEvent.Failed("cannot start ${spec.bin}: ${e.message}")); close(); return@callbackFlow
        }

        val connected = AtomicBoolean(false)
        val events = thread(name = "events") {
            runCatching {
                FileInputStream(fifo).bufferedReader().use { r ->
                    connected.set(true)
                    r.forEachLine { line -> EventParser.parse(line)?.let { trySend(it) } }
                }
            }
        }
        val out = thread(name = "stdout") {
            runCatching { proc.inputStream.bufferedReader().forEachLine { trySend(RunEvent.Log(false, it)) } }
        }
        val err = thread(name = "stderr") {
            runCatching { proc.errorStream.bufferedReader().forEachLine { trySend(RunEvent.Log(true, it)) } }
        }
        thread(name = "waiter") {
            val code = proc.waitFor()
            if (!connected.get()) releaseReader(fifo)   // the driver died before opening its end
            events.join(); out.join(); err.join()
            trySend(RunEvent.Exit(code, SystemClock.elapsedRealtime() - t0))
            close()
        }

        awaitClose {
            if (proc.isAlive) {
                proc.destroy()
                if (!proc.waitFor(2, TimeUnit.SECONDS)) proc.destroyForcibly()
            }
            if (!connected.get()) releaseReader(fifo)
            fifo.delete()
        }
    }.buffer(Channel.UNLIMITED)

    // Opening the write end lets a reader blocked in open() return and then see EOF.
    private fun releaseReader(fifo: File) {
        runCatching {
            val fd = Os.open(fifo.path, OsConstants.O_WRONLY or OsConstants.O_NONBLOCK, 0)
            Os.close(fd)
        }
    }
}
