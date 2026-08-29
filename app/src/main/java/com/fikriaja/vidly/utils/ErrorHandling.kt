/*
 * Vidly Project Original (2026)
 * fikriaja1028 (GitHub.com/fikriaja1028)
 * Licenced Under GPL-3.0+
*/
package com.fikriaja.vidly.utils

import java.io.IOException
import java.net.UnknownHostException

sealed class VidlyError {
    object Network : VidlyError()
    data class Extraction(val errorMessage: String) : VidlyError()
    data class Unknown(val errorMessage: String) : VidlyError()
    object AuthError : VidlyError()
    object ApiThrottled : VidlyError()
    object StorageFull : VidlyError()
    data class UnsupportedFormat(val format: String) : VidlyError()

    fun getMessage(): String {
        return when (this) {
            is Network -> "No internet connection"
            is Extraction -> errorMessage
            is Unknown -> errorMessage
            is AuthError -> "Authentication required"
            is ApiThrottled -> "Service busy, try again later"
            is StorageFull -> "Storage is full"
            is UnsupportedFormat -> "Format $format is not supported"
        }
    }

    companion object {
        fun fromThrowable(t: Throwable): VidlyError {
            return when (t) {
                is UnknownHostException, is IOException -> Network
                is java.lang.SecurityException -> AuthError
                else -> {
                    val message = t.message ?: "An unexpected error occurred"
                    if (message.contains("429") || message.contains("Too Many Requests")) ApiThrottled
                    else Extraction(message)
                }
            }
        }
    }
}
