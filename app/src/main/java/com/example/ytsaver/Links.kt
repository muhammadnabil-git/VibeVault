package com.example.ytsaver

private val urlRegex = Regex("""https?://[^\s,;]+""")
private val listRegex = Regex("""[?&]list=([A-Za-z0-9_-]+)""")
private val videoParam = Regex("""[?&]v=""")

/** Every http(s) link found in the text (one per line, or separated by spaces/commas). */
fun parseLinks(text: String): List<String> =
    urlRegex.findAll(text).map { it.value.trimEnd(')', '.', ']') }.distinct().toList()

/** 0 = single video / other site, 1 = playlist link, 2 = video link that also carries a playlist id */
fun playlistKind(url: String): Int {
    val yt = url.contains("youtube.com") || url.contains("youtu.be")
    if (!yt || !listRegex.containsMatchIn(url)) return 0
    val hasVideo = videoParam.containsMatchIn(url) || url.contains("youtu.be/")
    return if (hasVideo) 2 else 1
}

fun isPlaylistRequest(url: String, wholePlaylist: Boolean): Boolean {
    val k = playlistKind(url)
    return k == 1 || (k == 2 && wholePlaylist)
}

fun playlistUrl(url: String): String {
    val id = listRegex.find(url)?.groupValues?.get(1) ?: return url
    return "https://www.youtube.com/playlist?list=$id"
}
