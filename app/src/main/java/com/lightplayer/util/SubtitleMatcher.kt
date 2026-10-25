package com.lightplayer.util

/**
 * Works out which subtitle file belongs to which video, from the file names alone: first by
 * episode number (S01E02, 1x02, "Episode 2", " - 02"), otherwise by how similar the names are.
 */
object SubtitleMatcher {

    private val NOISE = setOf(
        "1080p", "720p", "480p", "2160p", "4k", "x264", "x265", "h264", "h265", "hevc", "avc", "aac",
        "ac3", "dts", "bluray", "bdrip", "brrip", "webrip", "webdl", "web", "dl", "hdtv", "dvdrip",
        "10bit", "8bit", "eng", "english", "en", "sub", "subs", "subtitle", "subtitles", "dual",
        "multi", "hardsub", "softsub", "proper", "repack", "uncut", "sdh", "forced"
    )
    private val RESOLUTION_NUMBERS = setOf(144, 240, 360, 480, 540, 576, 720, 1080, 1440, 2160, 264, 265)

    private val BRACKETS = Regex("""\[[^\]]*]|\([^)]*\)|\{[^}]*}""")
    private val SEASON_EPISODE = Regex("""\bs(\d{1,2})[ ._-]?e(\d{1,3})\b""")
    private val CROSS_FORMAT = Regex("""\b(\d{1,2})x(\d{1,3})\b""")
    private val EPISODE_WORD = Regex("""\b(?:episode|ep|e)[ ._-]?(\d{1,3})\b""")
    private val SPLIT = Regex("[^a-z0-9]+")

    class Parsed(val titleTokens: Set<String>, val season: Int?, val episode: Int?)

    fun baseName(fileName: String): String {
        val dot = fileName.lastIndexOf('.')
        return if (dot > 0 && fileName.length - dot <= 5) fileName.substring(0, dot) else fileName
    }

    fun extension(fileName: String): String {
        val dot = fileName.lastIndexOf('.')
        return if (dot > 0 && fileName.length - dot <= 5) fileName.substring(dot + 1) else ""
    }

    fun parse(fileName: String): Parsed {
        var text = BRACKETS.replace(baseName(fileName), " ").lowercase()
        text = text.replace('.', ' ').replace('_', ' ')

        var season: Int? = null
        var episode: Int? = null

        SEASON_EPISODE.find(text)?.let {
            season = it.groupValues[1].toIntOrNull()
            episode = it.groupValues[2].toIntOrNull()
            text = text.replace(it.value, " ")
        }
        if (episode == null) {
            CROSS_FORMAT.find(text)?.let {
                season = it.groupValues[1].toIntOrNull()
                episode = it.groupValues[2].toIntOrNull()
                text = text.replace(it.value, " ")
            }
        }
        if (episode == null) {
            EPISODE_WORD.find(text)?.let {
                episode = it.groupValues[1].toIntOrNull()
                text = text.replace(it.value, " ")
            }
        }

        val tokens = text.split(SPLIT).filter { it.isNotEmpty() }.toMutableList()
        if (episode == null) {
            // "Show Name - 07": the last short number is the episode.
            val index = tokens.indexOfLast { t ->
                t.length in 1..3 && t.all { c -> c.isDigit() } && (t.toIntOrNull() ?: 0) !in RESOLUTION_NUMBERS
            }
            if (index >= 0) {
                episode = tokens[index].toIntOrNull()
                tokens.removeAt(index)
            }
        }

        val title = tokens.filter { it !in NOISE && !it.all { c -> c.isDigit() } }.toSet()
        return Parsed(title, season, episode)
    }

    /** Higher = better; below zero = not the same video. */
    private fun score(video: Parsed, sub: Parsed): Double {
        val union = video.titleTokens.union(sub.titleTokens).size
        val similarity =
            if (video.titleTokens.isEmpty() || sub.titleTokens.isEmpty() || union == 0) 0.0
            else video.titleTokens.intersect(sub.titleTokens).size.toDouble() / union

        val ve = video.episode
        val se = sub.episode
        if (ve != null && se != null) {
            if (ve != se) return -1.0
            if (video.season != null && sub.season != null && video.season != sub.season) return -1.0
            val noTitles = video.titleTokens.isEmpty() || sub.titleTokens.isEmpty()
            return if (similarity >= 0.25 || noTitles) 100.0 + similarity * 100.0 else -1.0
        }
        return if (similarity >= 0.6) similarity * 100.0 else -1.0
    }

    /** For every subtitle, the index of the video it belongs to, or -1. One subtitle per video. */
    fun matchAll(subtitles: List<String>, videos: List<String>): IntArray {
        val subParsed = subtitles.map { parse(it) }
        val videoParsed = videos.map { parse(it) }
        val candidates = ArrayList<Triple<Double, Int, Int>>()
        for (s in subParsed.indices) {
            for (v in videoParsed.indices) {
                val sc = score(videoParsed[v], subParsed[s])
                if (sc >= 0.0) candidates.add(Triple(sc, s, v))
            }
        }
        candidates.sortByDescending { it.first }
        val result = IntArray(subtitles.size) { -1 }
        val usedVideos = HashSet<Int>()
        for ((_, s, v) in candidates) {
            if (result[s] == -1 && usedVideos.add(v)) result[s] = v
        }
        return result
    }

    /** The subtitle that best fits one video, or -1. */
    fun bestSubtitle(video: String, subtitles: List<String>): Int {
        val vp = parse(video)
        var best = -1
        var bestScore = -1.0
        subtitles.forEachIndexed { i, name ->
            val sc = score(vp, parse(name))
            if (sc > bestScore) {
                bestScore = sc
                best = i
            }
        }
        return if (bestScore >= 0.0) best else -1
    }
}
