/*
 * Vidly Project Original (2026)
 * fikriaja1028 (GitHub.com/fikriaja1028)
 * Licenced Under GPL-3.0+
*/
package com.fikriaja.vidly.domain.model

enum class SearchSort(val label: String, val value: String) {
    RELEVANCE("All", "relevance"),
    UPLOAD_DATE("Upload Date", "upload_date"),
    VIEW_COUNT("View Count", "view_count"),
    RATING("Rating", "rating")
}

enum class UploadDateFilter(val label: String, val value: String) {
    ALL("All time", "all"),
    LAST_HOUR("Last hour", "last_hour"),
    TODAY("Today", "today"),
    THIS_WEEK("This week", "this_week"),
    THIS_MONTH("This month", "this_month"),
    THIS_YEAR("This year", "this_year")
}

enum class DurationFilter(val label: String, val value: String) {
    ALL("Any duration", "all"),
    SHORT("Short (< 4 min)", "short"),
    MEDIUM("Medium (4 - 20 min)", "medium"),
    LONG("Long (> 20 min)", "long")
}
