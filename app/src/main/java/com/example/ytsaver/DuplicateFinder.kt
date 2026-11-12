package com.example.ytsaver

/**
 * Pure logic (no Android classes) so it can be unit-tested.
 * Two saved files are duplicates when they came from the same source and have the same format, or - for files that have
 * no source address - when their names, formats and sizes are all identical. Imported device files are never touched.
 */
object DuplicateFinder {
    /** Each group has 2 or more files, the newest first. */
    fun groups(items: List<HistoryItem>): List<List<HistoryItem>> {
        val own = items.filterNot { it.isDeviceFile }
        val bySource = own.filter { it.sourceUrl.isNotBlank() }.groupBy { sourceId(it.sourceUrl) + "|" + it.format }
        val rest = own.filter { it.sourceUrl.isBlank() }.groupBy { it.name.lowercase().trim() + "|" + it.format + "|" + it.sizeBytes }
        return (bySource.values + rest.values).filter { it.size > 1 }.map { g -> g.sortedByDescending { it.timeMillis } }
    }

    /** The files to remove: everything but the newest of each group. */
    fun removable(items: List<HistoryItem>): List<HistoryItem> = groups(items).flatMap { it.drop(1) }

    fun reclaimableBytes(items: List<HistoryItem>): Long = removable(items).sumOf { it.sizeBytes }
}

/** Smart playlists: lists the app builds by itself from what is saved. */
object SmartPlaylists {
    class Smart(val id: String, val en: String, val ar: String, val items: List<HistoryItem>)

    private const val DAY = 24 * 60 * 60 * 1000L

    fun build(all: List<HistoryItem>, now: Long = System.currentTimeMillis()): List<Smart> {
        val newest = all.sortedByDescending { it.timeMillis }
        return listOf(
            Smart("new", "Added this week", "أُضيف هذا الأسبوع", newest.filter { now - it.timeMillis <= 7 * DAY }),
            Smart("music", "All music", "كل الموسيقى", newest.filter { it.format == "MP3" }),
            Smart("video", "All videos", "كل الفيديوهات", newest.filter { it.format == "MP4" }),
            Smart("big", "Biggest files", "أكبر الملفات", all.sortedByDescending { it.sizeBytes }.take(25)),
        ).filter { it.items.isNotEmpty() }
    }
}
