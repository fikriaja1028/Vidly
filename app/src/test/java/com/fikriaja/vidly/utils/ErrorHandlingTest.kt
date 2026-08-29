/*
 * Vidly Project Original (2026)
 * fikriaja1028 (GitHub.com/fikriaja1028)
 * Licenced Under GPL-3.0+
*/
package com.fikriaja.vidly.utils

import org.junit.Assert.*
import org.junit.Test
import java.io.IOException
import java.net.UnknownHostException

class ErrorHandlingTest {

    @Test
    fun `fromThrowable maps Network errors correctly`() {
        assertTrue(VidlyError.fromThrowable(UnknownHostException()) is VidlyError.Network)
        assertTrue(VidlyError.fromThrowable(IOException()) is VidlyError.Network)
    }

    @Test
    fun `fromThrowable maps AuthError correctly`() {
        assertTrue(VidlyError.fromThrowable(SecurityException()) is VidlyError.AuthError)
    }

    @Test
    fun `fromThrowable maps ApiThrottled correctly`() {
        val throttledException = Exception("HTTP 429 Too Many Requests")
        assertTrue(VidlyError.fromThrowable(throttledException) is VidlyError.ApiThrottled)
    }

    @Test
    fun `getMessage returns human readable strings`() {
        assertEquals("No internet connection", VidlyError.Network.getMessage())
        assertEquals("Authentication required", VidlyError.AuthError.getMessage())
        assertEquals("Custom Error", VidlyError.Unknown("Custom Error").getMessage())
    }
}
