/*
 * Vidly Project Original (2026)
 * fikriaja1028 (GitHub.com/fikriaja1028)
 * Licenced Under GPL-3.0+
*/
package com.fikriaja.vidly.domain.repository

import com.fikriaja.vidly.domain.model.PaginatedList
import com.fikriaja.vidly.domain.model.SearchSort
import com.fikriaja.vidly.domain.model.SearchItem
import com.fikriaja.vidly.domain.model.UploadDateFilter
import com.fikriaja.vidly.domain.model.DurationFilter
import org.schabi.newpipe.extractor.Page

interface SearchRepository {
    suspend fun search(
        query: String,
        sort: SearchSort = SearchSort.RELEVANCE,
        uploadDate: UploadDateFilter = UploadDateFilter.ALL,
        duration: DurationFilter = DurationFilter.ALL
    ): PaginatedList<SearchItem>

    suspend fun fetchNextPage(
        query: String,
        sort: SearchSort,
        uploadDate: UploadDateFilter = UploadDateFilter.ALL,
        duration: DurationFilter = DurationFilter.ALL,
        page: Page
    ): PaginatedList<SearchItem>

    suspend fun getSearchSuggestions(query: String): List<String>
}
