package com.opensub.app.core

private const val DEFAULT_CAPACITY = 256

/** Speech repeats ("Thank you."), so recent sentences skip the model. The oldest entry leaves first. */
class CachingTranslator(private val delegate: Translator, private val capacity: Int = DEFAULT_CAPACITY) : Translator {
    private val cache = LinkedHashMap<String, String>()

    override suspend fun translate(english: String): String = continueFrom(english, "")

    override suspend fun continueFrom(english: String, shown: String): String {
        val key = english.trim().lowercase() + "\n" + shown
        cache[key]?.let { return it }
        val chinese = if (shown.isEmpty()) delegate.translate(english) else delegate.continueFrom(english, shown)
        cache[key] = chinese
        if (cache.size > capacity) cache.remove(cache.keys.first())
        return chinese
    }

    /** Cached too: a subtitle is often translated ahead, the moment the speaker seems to stop, and asked for again when they do. */
    override suspend fun translateAfter(english: String, before: String): String {
        val key = english.trim().lowercase() + "\n\u0001" + before
        cache[key]?.let { return it }
        val chinese = delegate.translateAfter(english, before)
        cache[key] = chinese
        if (cache.size > capacity) cache.remove(cache.keys.first())
        return chinese
    }

    override val continues get() = delegate.continues
    override val readsBackground get() = delegate.readsBackground

    override fun close() = delegate.close()
}
