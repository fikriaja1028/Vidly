/*
 * Vidly Project Original (2026)
 * fikriaja1028 (GitHub.com/fikriaja1028)
 * Licenced Under GPL-3.0+
*/
package com.fikriaja.vidly.di

import android.content.Context
import com.fikriaja.vidly.data.local.PreferencesManager
import com.fikriaja.vidly.data.network.DynamicProxySelector
import com.fikriaja.vidly.data.network.IPv4OnlyDns
import com.fikriaja.vidly.utils.Constants
import com.google.gson.Gson
import com.google.gson.GsonBuilder
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import io.ktor.client.HttpClient
import io.ktor.client.engine.okhttp.OkHttp
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.serialization.kotlinx.json.json
import kotlinx.serialization.json.Json
import okhttp3.Cache
import okhttp3.Cookie
import okhttp3.CookieJar
import okhttp3.HttpUrl
import okhttp3.OkHttpClient
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object NetworkModule {

    @Provides
    @Singleton
    fun provideOkHttpClient(
        @ApplicationContext context: Context,
        preferencesManager: PreferencesManager
    ): OkHttpClient {
        val cacheSize = 50L * 1024L * 1024L // 50MB
        val cacheDirectory = File(context.cacheDir, "http_cache")
        val cache = Cache(cacheDirectory, cacheSize)

        val dispatcher = okhttp3.Dispatcher().apply {
            maxRequests = 128
            maxRequestsPerHost = 40
        }

        val pool = okhttp3.ConnectionPool(30, 5, TimeUnit.MINUTES)

        // FIX(BUG #10): ConcurrentHashMap â€” this jar is read/written from multiple OkHttp
        // network threads concurrently; a plain mutableMapOf can corrupt or throw CME.
        val cookieJar = object : CookieJar {
            private val cookieStore = ConcurrentHashMap<String, List<Cookie>>()

            override fun saveFromResponse(url: HttpUrl, cookies: List<Cookie>) {
                cookieStore[url.host] = cookies
            }

            override fun loadForRequest(url: HttpUrl): List<Cookie> {
                return cookieStore[url.host] ?: emptyList()
            }
        }

        return OkHttpClient.Builder()
            .cache(cache)
            .cookieJar(cookieJar)
            .connectionPool(pool)
            .dispatcher(dispatcher)
            .addInterceptor { chain ->
                // FIX(BUG #1): Removed the fake X-Goog-Po-Token header. The previous
                // implementation sent a timestamp string that YouTube rejects, actively
                // causing 403s on media requests. Sending NO PoToken is the correct
                // behaviour until a real BotGuard attestation integration exists.
                val requestBuilder = chain.request().newBuilder()
                    .header("User-Agent", Constants.DEFAULT_USER_AGENT)
                chain.proceed(requestBuilder.build())
            }
            .connectTimeout(60, TimeUnit.SECONDS)
            .readTimeout(60, TimeUnit.SECONDS)
            .writeTimeout(60, TimeUnit.SECONDS)
            .retryOnConnectionFailure(true)
            .fastFallback(true)
            .dns(IPv4OnlyDns())
            .proxySelector(DynamicProxySelector(preferencesManager))
            .build()
    }

    @Provides
    @Singleton
    fun provideHttpClient(okHttpClient: OkHttpClient): HttpClient {
        return HttpClient(OkHttp) {
            engine {
                preconfigured = okHttpClient
            }
            install(ContentNegotiation) {
                json(Json {
                    ignoreUnknownKeys = true
                    coerceInputValues = true
                })
            }
        }
    }

    @Provides
    @Singleton
    fun provideGson(): Gson {
        return GsonBuilder()
            .create()
    }
}
