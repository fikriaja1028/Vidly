
package com.fikriaja.vidly.data.network

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class GitHubRelease(
    @SerialName("tag_name")
    val tagName: String,
    // FIX(LOW): GitHub returns null for body/html_url on some releases (rare, but
    // it happens for empty releases) â€” declared nullable so deserialization of
    // such payloads doesn't crash; consumers coerce to empty string.
    @SerialName("body")
    val body: String? = null,
    @SerialName("html_url")
    val htmlUrl: String? = null
)
