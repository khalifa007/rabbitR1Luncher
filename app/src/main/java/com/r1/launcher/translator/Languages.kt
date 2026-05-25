package com.r1.launcher.translator

/**
 * Curated language catalog for the Translator app.
 *
 * `code` is ISO 639-1 — the same identifier passed to ElevenLabs Scribe v2 STT
 * via `language_code` AND understood by every LLM provider for the translation
 * prompt. `endonym` is the language name in its own script (what a native
 * speaker recognizes); `english` is the romanized label for the picker.
 *
 * `rtl` flips the bubble alignment + text direction for Arabic / Hebrew /
 * Persian / Urdu so target text renders the way it should.
 *
 * Curated rather than dumped from CLDR: ~25 languages covers the vast majority
 * of real-world R1 translator use and keeps the on-device picker scrollable in
 * one or two flicks on a 480×480 screen.
 */
data class Language(
    val code: String,
    val english: String,
    val endonym: String,
    val rtl: Boolean = false,
)

object Languages {
    val ALL: List<Language> = listOf(
        Language("en", "english",     "english"),
        Language("ar", "arabic",      "العربية", rtl = true),
        Language("es", "spanish",     "español"),
        Language("fr", "french",      "français"),
        Language("de", "german",      "deutsch"),
        Language("it", "italian",     "italiano"),
        Language("pt", "portuguese",  "português"),
        Language("ru", "russian",     "русский"),
        Language("zh", "chinese",     "中文"),
        Language("ja", "japanese",    "日本語"),
        Language("ko", "korean",      "한국어"),
        Language("hi", "hindi",       "हिन्दी"),
        Language("ur", "urdu",        "اُردُو", rtl = true),
        Language("fa", "persian",     "فارسی", rtl = true),
        Language("he", "hebrew",      "עברית",  rtl = true),
        Language("tr", "turkish",     "türkçe"),
        Language("nl", "dutch",       "nederlands"),
        Language("pl", "polish",      "polski"),
        Language("sv", "swedish",     "svenska"),
        Language("uk", "ukrainian",   "українська"),
        Language("vi", "vietnamese",  "tiếng việt"),
        Language("id", "indonesian",  "bahasa indonesia"),
        Language("th", "thai",        "ไทย"),
        Language("el", "greek",       "ελληνικά"),
        Language("cs", "czech",       "čeština"),
        Language("ro", "romanian",    "română"),
        Language("hu", "hungarian",   "magyar"),
        Language("fi", "finnish",     "suomi"),
        Language("da", "danish",      "dansk"),
        Language("no", "norwegian",   "norsk"),
    )

    /** Sentinel "language" meaning the source is unknown — the LLM detects it
     *  per-message. Only valid as a SOURCE; never a target. Not in [ALL] (it's
     *  not a real language) but [get] resolves it so chips/cards can render it. */
    const val AUTO = "auto"
    private val AUTO_LANG = Language(AUTO, "auto-detect", "auto-detect")

    private val byCode: Map<String, Language> = ALL.associateBy { it.code }

    /** Lookup by ISO code. Returns English as a safe fallback so the UI never
     *  has to deal with null — a malformed pref can't crash the panel. [AUTO]
     *  resolves to the synthetic auto-detect entry. */
    fun get(code: String): Language = when (code) {
        AUTO -> AUTO_LANG
        else -> byCode[code] ?: ALL.first()
    }

    fun isAuto(code: String): Boolean = code == AUTO

    /** Display label used in chips and pickers: `[AR] arabic`. */
    fun chipLabel(code: String): String {
        val lang = get(code)
        return "[${lang.code.uppercase()}] ${lang.english}"
    }

    /** Index in [ALL] for wheel-cycling. Defaults to 0 when unknown. */
    fun indexOf(code: String): Int {
        val i = ALL.indexOfFirst { it.code == code }
        return if (i < 0) 0 else i
    }

    fun cycle(code: String, delta: Int): String {
        val n = ALL.size
        val i = ((indexOf(code) + delta) % n + n) % n
        return ALL[i].code
    }
}
