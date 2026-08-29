/*
 * Vidly Project Original (2026)
 * fikriaja1028 (GitHub.com/fikriaja1028)
 * Licenced Under GPL-3.0+
*/
package com.fikriaja.vidly.domain.usecase

import com.fikriaja.vidly.domain.model.PaginatedList
import com.fikriaja.vidly.domain.model.SearchSort
import com.fikriaja.vidly.domain.model.SearchItem
import com.fikriaja.vidly.domain.model.UploadDateFilter
import com.fikriaja.vidly.domain.model.DurationFilter
import com.fikriaja.vidly.domain.repository.SearchRepository
import kotlinx.coroutines.CancellationException
import org.schabi.newpipe.extractor.Page
import javax.inject.Inject

class SearchVideosUseCase @Inject constructor(
    private val repository: SearchRepository
) {
    suspend operator fun invoke(
        query: String,
        sort: SearchSort = SearchSort.RELEVANCE,
        uploadDate: UploadDateFilter = UploadDateFilter.ALL,
        duration: DurationFilter = DurationFilter.ALL
    ): Result<PaginatedList<SearchItem>> {
        return try {
            Result.success(repository.search(query, sort, uploadDate, duration))
        } catch (e: Exception) {
            if (e is CancellationException) throw e
            Result.failure(e)
        }
    }

    suspend fun fetchNextPage(
        query: String,
        sort: SearchSort,
        uploadDate: UploadDateFilter = UploadDateFilter.ALL,
        duration: DurationFilter = DurationFilter.ALL,
        page: Page
    ): Result<PaginatedList<SearchItem>> {
        return try {
            Result.success(repository.fetchNextPage(query, sort, uploadDate, duration, page))
        } catch (e: Exception) {
            if (e is CancellationException) throw e
            Result.failure(e)
        }
    }
}
