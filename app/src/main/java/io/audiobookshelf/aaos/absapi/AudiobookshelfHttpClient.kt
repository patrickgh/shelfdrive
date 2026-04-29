package io.audiobookshelf.aaos.absapi

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL

class AudiobookshelfHttpClient {

    suspend fun execute(baseUrl: String, request: HttpRequest): HttpResponse = withContext(Dispatchers.IO) {
        val normalizedBaseUrl = baseUrl.trim().removeSuffix("/")
        executeWithRetries(normalizedBaseUrl, request)
    }

    private suspend fun executeWithRetries(baseUrl: String, request: HttpRequest): HttpResponse {
        val retryDelays = NetworkPolicy.retryDelays(request.retryProfile)
        var attempt = 0

        while (true) {
            try {
                val connection = openConnection(baseUrl, request)
                val statusCode = connection.responseCode
                val response = HttpResponse(
                    statusCode = statusCode,
                    body = readBody(connection),
                    headers = connection.headerFields.entries
                        .mapNotNull { entry -> entry.key?.let { it to entry.value } }
                        .toMap(),
                )

                if (NetworkPolicy.shouldRetryHttpStatus(statusCode, request.retryProfile) && attempt < retryDelays.size) {
                    delay(retryDelays[attempt])
                    attempt += 1
                    continue
                }

                return response
            } catch (exception: IOException) {
                if (!NetworkPolicy.shouldRetry(exception) || attempt >= retryDelays.size) {
                    throw exception
                }
                delay(retryDelays[attempt])
                attempt += 1
            }
        }
    }

    private fun openConnection(baseUrl: String, request: HttpRequest): HttpURLConnection {
        val connection = (URL("$baseUrl${request.path}").openConnection() as HttpURLConnection).apply {
            requestMethod = request.method
            connectTimeout = NetworkPolicy.CONNECT_TIMEOUT_MS
            readTimeout = NetworkPolicy.READ_TIMEOUT_MS
            doInput = true
            request.headers.forEach { (name, value) ->
                setRequestProperty(name, value)
            }
        }

        val body = request.body
        if (body != null) {
            connection.doOutput = true
            connection.outputStream.bufferedWriter().use { writer ->
                writer.write(body)
            }
        }

        return connection
    }

    private fun readBody(connection: HttpURLConnection): String {
        val stream = connection.errorStream ?: connection.inputStream
        return stream?.bufferedReader()?.use { it.readText() }.orEmpty()
    }
}

data class HttpRequest(
    val path: String,
    val method: String,
    val headers: Map<String, String> = emptyMap(),
    val body: String? = null,
    val retryProfile: RetryProfile = RetryProfile.NONE,
)

data class HttpResponse(
    val statusCode: Int,
    val body: String,
    val headers: Map<String, List<String>>,
)
