package com.opensub.app.translate

import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals

/** A stand-in for hy-translate: answers each line at once, but "slow" after 1.5 s and "hang" never. */
private const val FAKE = """
public class Fake {
    public static void main(String[] args) throws Exception {
        var in = new java.io.BufferedReader(new java.io.InputStreamReader(System.in, "UTF-8"));
        for (String line; (line = in.readLine()) != null; ) {
            String english = line.split("\t", -1)[0];
            if (english.equals("hang")) Thread.sleep(Long.MAX_VALUE);
            if (english.equals("slow")) Thread.sleep(1500);
            System.out.println("zh:" + english);
            System.out.flush();
        }
    }
}
"""

class HyTranslatorTest {
    private fun fake(): HyTranslator {
        val folder = kotlin.io.path.createTempDirectory("hy-fake").toFile()
        val source = File(folder, "Fake.java").apply { writeText(FAKE) }
        val java = File(System.getProperty("java.home"), "bin/java")
        // hy-translate is started as: program model threads; here java runs the source file and ignores "1".
        return HyTranslator(java, source, 1, File(folder, "errors.log"), answerMs = 3_000, firstAnswerMs = 20_000)
    }

    @Test fun aHungProgramIsRestartedAndTheNextRequestWorks() = runBlocking {
        val translator = fake()
        try {
            assertEquals("zh:hello", translator.translate("hello"))
            assertEquals("", translator.translate("hang"))
            assertEquals("zh:again", translator.translate("again"))
        } finally { translator.close() }
    }

    @Test fun aCancelledRequestDoesNotShiftTheReplies() = runBlocking {
        val translator = fake()
        try {
            assertEquals("zh:warm", translator.translate("warm"))
            val gaveUp = async { runCatching { withTimeout(200) { translator.translate("slow") } } }
            assert(gaveUp.await().isFailure)
            // The late reply to "slow" must be dropped, not handed to this request.
            assertEquals("zh:next", translator.translate("next"))
        } finally { translator.close() }
    }

    @Test fun cancellingStopsTheWaitAtOnce() = runBlocking {
        val translator = fake()
        try {
            translator.translate("warm")
            val waiting = async { translator.translate("hang") }
            delay(200)
            val started = System.nanoTime()
            waiting.cancel()
            runCatching { waiting.await() }
            assert((System.nanoTime() - started) / 1_000_000 < 1_000) { "cancel waited for the program" }
        } finally { translator.close() }
    }
}
