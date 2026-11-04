package com.opensub.app.translate

import com.opensub.app.core.SUBTITLE_RULES
import com.opensub.app.core.Translator
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull
import java.io.File
import java.util.concurrent.TimeUnit

private val LINE_BREAKS = Regex("[\\t\\r\\n]+")
/** hy-translate's own wording for a plain request, which a request with references has to spell out. */
private const val INSTRUCTION = "Translate the following text into Chinese. Note that you should only output the translated result without any additional explanation:\n\n"

/**
 * Hy-MT2, kept loaded by the separate hy-translate program, on Windows and on the tablet alike. Its first answer also waits
 * for the model to load. What the program writes to its error stream goes to [errors].
 */
class HyTranslator(private val program: File, private val model: File, private val threads: Int, private val errors: File,
    private val answerMs: Long = 30_000, private val firstAnswerMs: Long = 120_000) : Translator {
    private class Worker(val process: Process) {
        val requests = process.outputStream.bufferedWriter(Charsets.UTF_8)
        val replies = process.inputStream.bufferedReader(Charsets.UTF_8)
    }
    private var worker = Worker(ProcessBuilder(program.path, model.path, threads.toString()).redirectError(errors).start())
    private var started = false
    /** One request at a time, so every reply is matched to its own request. */
    private val lock = Mutex()
    /** A pipe read cannot be interrupted, so it runs here; a caller that gives up only stops waiting for it. */
    private val reading = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    /** The reply of a request whose caller was cancelled: it is still on its way and must be read before the next one. */
    private var owed: Deferred<String?>? = null

    override val continues get() = true

    override suspend fun translate(english: String): String = continueFrom(english, "")

    override suspend fun continueFrom(english: String, shown: String): String {
        val asked = LINE_BREAKS.replace(english, " ")
        val references = SUBTITLE_RULES.references(asked)
        val first = if (references.isEmpty()) asked else withReferences(references, INSTRUCTION + asked)
        return shown + ask(first + "\t" + LINE_BREAKS.replace(shown, " "))
    }

    override suspend fun translateAfter(english: String, before: String): String {
        val asked = LINE_BREAKS.replace(english, " ")
        val background = LINE_BREAKS.replace(before, " ")
        val references = SUBTITLE_RULES.references(asked)
        if (references.isEmpty()) return ask("$asked\t\t$background")
        return ask(withReferences(references, "[Background Information]\n$background\nPlease translate the following text into Chinese, " +
            "taking the provided background information into consideration.\n[Source Text]\n$asked") + "\t\t")
    }

    /** The model card's terminology template: the rules this request needs as reference translations, then the request itself. */
    private fun withReferences(references: List<String>, request: String) =
        "\u0001" + ("Reference the following translations:\n" + references.joinToString("\n") + "\n\n" + request).replace("\n", "\\n")

    /**
     * Sends one request and waits for its reply. A cancelled caller leaves the reply [owed]; the next request reads and drops it
     * first. A program that does not answer in time (the first answer also waits for the model to load) is killed and started
     * again, and the request comes back empty, which the captions treat as nothing shown yet.
     */
    private suspend fun ask(request: String): String = lock.withLock {
        val limit = if (started) answerMs else firstAnswerMs
        owed?.let { late -> if (withTimeoutOrNull(limit) { late.await() } == null && !late.isCompleted) return@withLock restart() }
        owed = null
        val current = worker
        val reply = reading.async {
            current.requests.write(request + "\n")
            current.requests.flush()
            current.replies.readLine()
        }
        owed = reply
        val answer = withTimeoutOrNull(limit) { reply.await() } ?: run {
            if (!reply.isCompleted) return@withLock restart()
            error("翻译程序意外退出。请重新开始；如果反复出现，请贴出完整日志。")
        }
        owed = null
        started = true
        answer
    }

    /** Kills a program that stopped answering; the blocked read then ends by itself. */
    private fun restart(): String {
        worker.process.destroyForcibly().waitFor(5, TimeUnit.SECONDS)
        owed = null
        started = false
        worker = Worker(ProcessBuilder(program.path, model.path, threads.toString()).redirectError(ProcessBuilder.Redirect.appendTo(errors)).start())
        return ""
    }

    override fun close() {
        worker.process.destroy()
        reading.cancel()
    }
}
