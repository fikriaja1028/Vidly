/*
 * Vidly Project Original (2026)
 * fikriaja1028 (GitHub.com/fikriaja1028)
 * Licenced Under GPL-3.0+
*/
package com.fikriaja.vidly.ui.screens.player

import android.net.Uri
import androidx.media3.common.C
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.TransferListener
import androidx.media3.exoplayer.upstream.BandwidthMeter
import com.fikriaja.vidly.utils.VidlyLog

/**
 * High-performance Chunked Adaptive Streaming DataSource (SABR Proxy)
 * Intercepts requests and loads media in small byte-range chunks for resilience.
 */
@UnstableApi
class SABRDataSource(
    private val upstream: DataSource,
    private val bandwidthMeter: BandwidthMeter
) : DataSource {

    private var currentDataSpec: DataSpec? = null
    private var opened = false
    private var bytesRemaining = 0L
    private var currentPosition = 0L
    private var useChunking = true

    // Optimized chunk size for 1080p streaming (512KB to 1MB)
    private val CHUNK_SIZE = 768 * 1024L 

    override fun addTransferListener(transferListener: TransferListener) {
        upstream.addTransferListener(transferListener)
    }

    override fun open(dataSpec: DataSpec): Long {
        currentDataSpec = dataSpec
        currentPosition = dataSpec.position
        bytesRemaining = if (dataSpec.length != C.LENGTH_UNSET.toLong()) dataSpec.length else C.LENGTH_UNSET.toLong()
        
        opened = true

        // FIX(subtitle mojibake & small files): Don't chunk very small requests
        // (subtitles, manifests ~ <300KB). Chunking splits UTF-8 multi-byte sequences
        // across Range requests and some servers return different Content-Encoding per
        // chunk causing garbled • → •. Let upstream handle small files directly.
        val uri = dataSpec.uri.toString()
        val isSmallTextRequest = uri.contains("timedtext") || uri.contains("caption") || uri.contains(".vtt") || uri.contains(".ttml")
        useChunking = !(isSmallTextRequest || (bytesRemaining != C.LENGTH_UNSET.toLong() && bytesRemaining < 512 * 1024))
        if (!useChunking) {
            VidlyLog.d("SABRDataSource", "Bypassing chunking for small/text request: $uri length=$bytesRemaining")
            upstream.open(dataSpec)
            return bytesRemaining
        }
        
        // Initial chunk open
        openNextChunk()
        
        return bytesRemaining
    }

    private fun openNextChunk() {
        val dataSpec = currentDataSpec ?: return
        
        // Resilience: Do not attempt to open a chunk if no bytes are remaining
        if (bytesRemaining != C.LENGTH_UNSET.toLong() && bytesRemaining <= 0) {
            return
        }

        val chunkLength = if (bytesRemaining == C.LENGTH_UNSET.toLong()) {
            CHUNK_SIZE
        } else {
            CHUNK_SIZE.coerceAtMost(bytesRemaining)
        }

        if (chunkLength <= 0 && bytesRemaining != C.LENGTH_UNSET.toLong()) return

        val chunkSpec = dataSpec.buildUpon()
            .setPosition(currentPosition)
            .setLength(chunkLength)
            .build()

        try {
            upstream.close()
            upstream.open(chunkSpec)
            VidlyLog.d("SABRDataSource", "Opening chunk at $currentPosition, length $chunkLength. Bitrate: ${bandwidthMeter.bitrateEstimate}")
        } catch (e: Exception) {
            VidlyLog.e("SABRDataSource", "Failed to open chunk", e)
            throw e
        }
    }

    override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
        if (!opened) return C.RESULT_END_OF_INPUT

        // If we bypassed chunking (small/text request), delegate directly to upstream
        if (!useChunking) {
            return upstream.read(buffer, offset, length)
        }

        var bytesRead = upstream.read(buffer, offset, length)
        
        if (bytesRead == C.RESULT_END_OF_INPUT) {
            // Chunk finished, check if we need to open next one
            if (bytesRemaining != C.LENGTH_UNSET.toLong() && bytesRemaining <= 0) {
                return C.RESULT_END_OF_INPUT
            }
            // LENGTH_UNSET with chunking is only for media streams where we know
            // there is more data – open next chunk. For safety, if we are at position 0
            // and got EOF immediately, treat as real EOF (empty response).
            if (bytesRemaining == C.LENGTH_UNSET.toLong() && currentPosition == 0L) {
                return C.RESULT_END_OF_INPUT
            }
            
            openNextChunk()
            bytesRead = upstream.read(buffer, offset, length)
        }

        if (bytesRead != C.RESULT_END_OF_INPUT) {
            currentPosition += bytesRead
            if (bytesRemaining != C.LENGTH_UNSET.toLong()) {
                bytesRemaining -= bytesRead
            }
        }

        return bytesRead
    }

    override fun getUri(): Uri? = upstream.getUri()

    override fun close() {
        if (opened) {
            opened = false
            upstream.close()
        }
    }
}

@UnstableApi
class SABRDataSourceFactory(
    private val baseFactory: DataSource.Factory,
    private val bandwidthMeter: BandwidthMeter
) : DataSource.Factory {
    override fun createDataSource(): DataSource {
        return SABRDataSource(baseFactory.createDataSource(), bandwidthMeter)
    }
}
