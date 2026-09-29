package io.audiobookshelf.aaos.diagnostics

import android.util.Base64
import androidx.core.net.toUri
import io.audiobookshelf.aaos.BuildConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.RequestBody.Companion.asRequestBody
import okio.buffer
import okio.sink
import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL

class DiagnosticsUploader {
    suspend fun upload(uploadUrl: String, packageFile: File): DiagnosticsUploadResult = withContext(Dispatchers.IO) {
        if (!BuildConfig.DIAGNOSTICS_ENABLED) {
            throw IOException("Diagnostics are disabled.")
        }
        val normalizedUrl = validateUploadUrl(uploadUrl)
        val body = MultipartBody.Builder()
            .setType(MultipartBody.FORM)
            .addFormDataPart("file", packageFile.name, packageFile.asRequestBody("application/zip".toMediaType()))
            .build()
        val connection = (URL(normalizedUrl).openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            connectTimeout = CONNECT_TIMEOUT_MS
            readTimeout = READ_TIMEOUT_MS
            doInput = true
            doOutput = true
            setRequestProperty("Content-Type", body.contentType().toString())
            setRequestProperty("Accept", "application/json,text/plain,*/*")
            setRequestProperty("Authorization", basicAuthorizationHeader())
        }
        connection.outputStream.sink().buffer().use { body.writeTo(it) }

        val statusCode = connection.responseCode
        val responseText = readBody(connection).take(MAX_RESPONSE_LENGTH)
        if (statusCode in 200..299) {
            DiagnosticsUploadResult(statusCode, responseText.ifBlank { "Upload accepted." })
        } else {
            throw IOException("Upload failed with HTTP $statusCode: $responseText")
        }
    }

    private fun validateUploadUrl(uploadUrl: String): String {
        val trimmed = uploadUrl.trim()
        val uri = trimmed.toUri()
        val scheme = uri.scheme
        if (scheme != "http" && scheme != "https") {
            throw IOException("Upload URL must start with http:// or https://.")
        }
        if (uri.host.isNullOrBlank()) {
            throw IOException("Upload URL must contain a host.")
        }
        val path = uri.path.orEmpty()
        if (path.isBlank() || path == "/") {
            return uri.buildUpon()
                .path(DEFAULT_UPLOAD_PATH)
                .build()
                .toString()
        }
        return trimmed
    }

    private fun readBody(connection: HttpURLConnection): String {
        val stream = connection.errorStream ?: connection.inputStream
        return stream?.bufferedReader()?.use { it.readText() }.orEmpty()
    }

    companion object {
        private const val BASIC_USERNAME = "shelfdrive-upload"
        private const val DEFAULT_UPLOAD_PATH = "/upload"
        private const val CONNECT_TIMEOUT_MS = 10_000
        private const val READ_TIMEOUT_MS = 20_000
        private const val MAX_RESPONSE_LENGTH = 240
    }

    private fun basicAuthorizationHeader(): String {
        val password = BuildConfig.DIAGNOSTICS_UPLOAD_PASSWORD
        if (password.isBlank()) {
            throw IOException("Diagnostics upload password is not configured.")
        }
        val credentials = "$BASIC_USERNAME:$password"
        return "Basic " + Base64.encodeToString(credentials.toByteArray(Charsets.UTF_8), Base64.NO_WRAP)
    }
}

data class DiagnosticsUploadResult(
    val statusCode: Int,
    val message: String,
)
