/*
 * Vidly Project Original (2026)
 * fikriaja1028 (GitHub.com/fikriaja1028)
 * Licenced Under GPL-3.0+
*/
package com.fikriaja.vidly.domain.model

import org.schabi.newpipe.extractor.Page
import androidx.annotation.Keep

@Keep
data class PaginatedList<T>(
    val items: List<T>,
    val nextPage: Page?
)
