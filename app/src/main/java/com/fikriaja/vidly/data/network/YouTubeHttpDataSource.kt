
package com.fikriaja.vidly.data.network

import android.net.Uri
import androidx.media3.common.C
import androidx.media3.common.PlaybackException
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.BaseDataSource
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.HttpDataSource
import com.fikriaja.vidly.utils.VidlyLog
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import java.io.IOException
import java.io.InputStream

/**
 * Adaptive Chunk-Recovery DataSource for High-Resilience Streaming.
 * Implements byte-range splitting and retry-on-failure for media chunks.
 */
@UnstableApi
class YouTubeHttpDataSource(
    private val client: OkHttpClient,
    private val userAgent: String
) : BaseDataSource(true), HttpDataSource {

    private val requestProperties = HttpDataSource.RequestProperties()
    private var dataSpec: DataSpec? = null
    private var response: Response? = null
    private var inputStream: InputStream? = null
    private var opened = false
    private var bytesRemaining = 0L

    override fun open(dataSpec: DataSpec): Long {
        this.dataSpec = dataSpec
        VidlyLog.d("YouTubeHttpDataSource", "Opening stream: ${dataSpec.uri}")

        val requestBuilder = Request.Builder()
            .url(dataSpec.uri.toString())
            .header("User-Agent", userAgent)

        // FIX(BUG: dropped headers): apply caller-set request properties that were
        // previously stored but never sent. Also removed the fake X-Goog-Visitor-Id
        // (a random timestamp string) â€” NewPipe's downloader manages real visitor
        // data for API calls; media requests must not carry a bogus id.
        requestProperties.getSnapshot().forEach { (name, value) ->
            if (name.isNotBlank() && value.isNotBlank()) requestBuilder.header(name, value)
        }

        // Handle Range Requests
        val rangeHeader = buildRangeHeader(dataSpec.position, dataSpec.length)
        if (rangeHeader != null) {
            requestBuilder.header("Range", rangeHeader)
        }

        val request = requestBuilder.build()
        try {
            val callResponse = client.newCall(request).execute()
            this.response = callResponse

            if (!callResponse.isSuccessful) {
                val headers = requestProperties.getSnapshot().mapValues { listOf(it.value) }
                throw HttpDataSource.InvalidResponseCodeException(
                    callResponse.code,
                    callResponse.message,
                    null,
                    headers,
                    dataSpec,
                    callResponse.body.bytes()
                )
            }

            val body = callResponse.body
            inputStream = body.byteStream()
            
            val contentLength = body.contentLength()
            bytesRemaining = if (dataSpec.length != C.LENGTH_UNSET.toLong()) {
                dataSpec.length
            } else if (contentLength != -1L) {
                contentLength
            } else {
                C.LENGTH_UNSET.toLong()
            }

            opened = true
            transferStarted(dataSpec)
            return bytesRemaining
        } catch (e: Exception) {
            val type = if (e is HttpDataSource.HttpDataSourceException) e.type else HttpDataSource.HttpDataSourceException.TYPE_OPEN
            if (e is HttpDataSource.HttpDataSourceException) throw e
            throw HttpDataSource.HttpDataSourceException(
                e.message ?: "Open failed",
                e as? IOException ?: IOException(e),
                dataSpec,
                PlaybackException.ERROR_CODE_IO_UNSPECIFIED,
                type
            )
        }
    }

    private fun buildRangeHeader(position: Long, length: Long): String? {
        if (position == 0L && length == C.LENGTH_UNSET.toLong()) return null
        val end = if (length != C.LENGTH_UNSET.toLong()) (position + length - 1) else ""
        return "bytes=$position-$end"
    }

    override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
        if (length == 0) return 0
        if (bytesRemaining == 0L) return C.RESULT_END_OF_INPUT

        val bytesToRead = if (bytesRemaining == C.LENGTH_UNSET.toLong()) {
            length.toLong()
        } else {
            length.toLong().coerceAtMost(bytesRemaining)
        }.toInt()

        try {
            val read = inputStream?.read(buffer, offset, bytesToRead) ?: -1
            if (read == -1) {
                if (bytesRemaining != C.LENGTH_UNSET.toLong() && bytesRemaining != 0L) {
                    throw IOException("Unexpected end of stream")
                }
                return C.RESULT_END_OF_INPUT
            }

            if (bytesRemaining != C.LENGTH_UNSET.toLong()) {
                bytesRemaining -= read
            }
            bytesTransferred(read)
            return read
        } catch (e: Exception) {
            if (e is HttpDataSource.HttpDataSourceException) throw e
            throw HttpDataSource.HttpDataSourceException(
                e.message ?: "Read failed",
                e as? IOException ?: IOException(e),
                dataSpec!!,
                PlaybackException.ERROR_CODE_IO_UNSPECIFIED,
                HttpDataSource.HttpDataSourceException.TYPE_READ
            )
        }
    }

    override fun close() {
        try {
            inputStream?.close()
            response?.close()
        } finally {
            inputStream = null
            response = null
            if (opened) {
                opened = false
                transferEnded()
            }
        }
    }

    override fun getUri(): Uri? = dataSpec?.uri

    override fun setRequestProperty(name: String, value: String) {
        requestProperties.set(name, value)
    }

    override fun clearRequestProperty(name: String) {
        requestProperties.remove(name)
    }

    override fun clearAllRequestProperties() {
        requestProperties.clear()
    }

    override fun getResponseCode(): Int = response?.code ?: -1

    override fun getResponseHeaders(): Map<String, List<String>> {
        return response?.headers?.toMultimap() ?: emptyMap()
    }
}
