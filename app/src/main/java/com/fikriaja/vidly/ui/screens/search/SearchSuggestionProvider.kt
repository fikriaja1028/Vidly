/*
 * Vidly Project Original (2026)
 * fikriaja1028 (GitHub.com/fikriaja1028)
 * Licenced Under GPL-3.0+
*/
package com.fikriaja.vidly.ui.screens.search

import com.fikriaja.vidly.domain.repository.SearchRepository
import javax.inject.Inject

class SearchSuggestionProvider @Inject constructor(
    private val searchRepository: SearchRepository
) {
    // This could be used for search suggestions as the user types
    suspend fun getSuggestions(query: String): List<String> {
        // NewPipe Extractor doesn't have a direct suggestion API easily accessible 
        // without more deep extraction, but we could implement it later.
        return emptyList()
    }
}
