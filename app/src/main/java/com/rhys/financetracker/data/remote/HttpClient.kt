package com.rhys.financetracker.data.remote

import com.rhys.financetracker.core.result.AppResult
import com.rhys.financetracker.core.result.runCatchingApp
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.withContext
import java.net.HttpURLConnection
import java.net.URL
import javax.inject.Inject
import javax.inject.Singleton
import javax.net.ssl.HttpsURLConnection

/**
 * A deliberately tiny HTTP client.
 *
 * The app makes at most two optional GET requests a day, so pulling in a
 * networking library would add build weight and another thing to keep updated
 * for no benefit.  `HttpsURLConnection` is part of the platform and enforces
 * TLS certificate validation by default.
 */
@Singleton
class HttpClient @Inject constructor(
    @com.rhys.financetracker.di.IoDispatcher private val ioDispatcher: CoroutineDispatcher,
) {

    private companion object {
        const val CONNECT_TIMEOUT_MS = 10_000
        const val READ_TIMEOUT_MS = 15_000
        const val USER_AGENT = "FinanceTracker/1.0 (Android)"
        const val MAX_RESPONSE_BYTES = 512 * 1024
    }

    /**
     * Fetches [url] and returns the body as text.
     *
     * Only `https` is accepted: financial figures should not arrive over a
     * connection that anything on the network could rewrite.
     */
    suspend fun getText(url: String): AppResult<String> = withContext(ioDispatcher) {
        runCatchingApp("Could not reach $url") {
            require(url.startsWith("https://")) { "Only secure (https) addresses are allowed" }

            val connection = (URL(url).openConnection() as HttpURLConnection).apply {
                requestMethod = "GET"
                connectTimeout = CONNECT_TIMEOUT_MS
                readTimeout = READ_TIMEOUT_MS
                instanceFollowRedirects = true
                setRequestProperty("Accept", "application/json")
                setRequestProperty("User-Agent", USER_AGENT)
            }
            check(connection is HttpsURLConnection) { "The connection was not secure" }

            try {
                val code = connection.responseCode
                if (code !in 200..299) {
                    error("The service replied with error $code")
                }
                connection.inputStream.bufferedReader().use { reader ->
                    val body = CharArray(MAX_RESPONSE_BYTES)
                    val read = reader.read(body, 0, MAX_RESPONSE_BYTES)
                    if (read <= 0) "" else String(body, 0, read)
                }
            } finally {
                connection.disconnect()
            }
        }
    }
}
