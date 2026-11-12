package com.example.ytsaver

/** Small builders so the tests read clearly. */
fun item(
    name: String, format: String = "MP3", size: Long = 1_000_000, time: Long = 1_000L,
    source: String = "", location: String = "Downloads/MediaSaver", uri: String = "content://media/$name$time",
) = HistoryItem(name, format, 192, size, time, location, uri, source)
