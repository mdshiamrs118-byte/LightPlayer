package com.lightplayer.tts

/**
 * Generated audio is named after the subtitle file it came from:
 * "episode1.vtt" becomes "episode1.wav". These helpers also let an existing audio file be
 * recognised later from its name alone.
 */
object TtsNaming {

    private val SUBTITLE_EXTENSIONS = setOf("srt", "vtt", "ass", "ssa", "wav")
    private val KEY_SUFFIX = Regex(""" \[[0-9a-f]{8}]$""")

    /** Replaces characters Android doesn't allow in file names. */
    fun sanitize(name: String): String {
        val sb = StringBuilder(name.length)
        for (ch in name) sb.append(if (ch in "/\\:*?\"<>|") '_' else ch)
        var t = sb.toString().replace(" ~ ", "-").trim()
        if (t.isEmpty()) t = "audio"
        if (t.length > 80) t = t.substring(0, 80).trim()
        return t
    }

    /** "episode1.vtt" -> "episode1" */
    fun base(fileName: String): String {
        val dot = fileName.lastIndexOf('.')
        val ext = if (dot > 0) fileName.substring(dot + 1).lowercase() else ""
        return if (dot > 0 && ext in SUBTITLE_EXTENSIONS) fileName.substring(0, dot) else fileName
    }

    /**
     * The file name for new audio. Normally just "<subtitle name>.wav"; when that name is
     * already used by different audio, the subtitle's short content code is added.
     */
    fun fileNameFor(subtitleFileName: String, contentKey: String, nameTaken: Boolean): String {
        val base = sanitize(base(subtitleFileName))
        return if (nameTaken) "$base [${contentKey.take(8)}].wav" else "$base.wav"
    }

    /** The 8-character content code in "name [a1b2c3d4].wav", or null when the name has none. */
    fun keyOf(name: String): String? {
        val t = base(name.trim())
        return KEY_SUFFIX.find(t)?.value?.trim()?.removePrefix("[")?.removeSuffix("]")
    }

    /**
     * A form of a subtitle or audio name that is the same whichever way it was written:
     * no extension, no content-code suffix, no forbidden characters, lower case.
     * "Show - 01.vtt", "Show - 01.wav" and "Show - 01 [a1b2c3d4].wav" all give "show - 01".
     */
    fun comparable(name: String): String {
        var t = name.trim()
        t = base(t)
        t = KEY_SUFFIX.replace(t, "")
        return sanitize(t).lowercase()
    }
}
