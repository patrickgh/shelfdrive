package io.audiobookshelf.aaos.absapi

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.IOException
import java.util.concurrent.TimeUnit

class AudiobookshelfHttpClient(
    private val client: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(NetworkPolicy.CONNECT_TIMEOUT_MS.toLong(), TimeUnit.MILLISECONDS)
        .readTimeout(NetworkPolicy.READ_TIMEOUT_MS.toLong(), TimeUnit.MILLISECONDS)
        .writeTimeout(NetworkPolicy.WRITE_TIMEOUT_MS.toLong(), TimeUnit.MILLISECONDS)
        .build()
) {

    suspend fun execute(baseUrl: String, request: HttpRequest): HttpResponse = withContext(Dispatchers.IO) {
        val normalizedBaseUrl = baseUrl.trim().removeSuffix("/")
        executeWithRetries(normalizedBaseUrl, request)
    }

    private suspend fun executeWithRetries(baseUrl: String, request: HttpRequest): HttpResponse {
        val retryDelays = NetworkPolicy.retryDelays(request.retryProfile)
        var attempt = 0
        val okHttpRequest = buildOkHttpRequest(baseUrl, request)

        while (true) {
            try {
                val response = client.newCall(okHttpRequest).execute()
                val statusCode = response.code
                val responseBody = response.body?.string().orEmpty()
                val responseHeaders = response.headers.toMultimap()

                val result = response.use {
                    val httpResponse = HttpResponse(
                        statusCode = statusCode,
                        body = responseBody,
                        headers = responseHeaders,
                    )

                    if (NetworkPolicy.shouldRetryHttpStatus(statusCode, request.retryProfile) && attempt < retryDelays.size) {
                        null
                    } else {
                        httpResponse
                    }
                }

                if (result != null) {
                    return result
                }

                delay(retryDelays[attempt])
                attempt += 1
            } catch (exception: IOException) {
                if (!NetworkPolicy.shouldRetry(exception) || attempt >= retryDelays.size) {
                    throw exception
                }
                delay(retryDelays[attempt])
                attempt += 1
            }
        }
    }

    private fun buildOkHttpRequest(baseUrl: String, request: HttpRequest): okhttp3.Request {
        val builder = okhttp3.Request.Builder()
            .url("$baseUrl${request.path}")

        request.headers.forEach { (name, value) ->
            builder.addHeader(name, value)
        }

        val body = request.body
        if (body != null) {
            val mediaType = request.headers["Content-Type"]?.toMediaTypeOrNull()
            builder.method(request.method, body.toRequestBody(mediaType))
        } else {
            val method = request.method
            if (method == "POST" || method == "PUT" || method == "PATCH") {
                builder.method(method, "".toRequestBody(null))
            } else {
                builder.method(method, null)
            }
        }

        return builder.build()
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
