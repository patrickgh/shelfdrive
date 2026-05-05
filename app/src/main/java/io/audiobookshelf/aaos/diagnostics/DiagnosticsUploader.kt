package io.audiobookshelf.aaos.diagnostics

import android.net.Uri
import android.util.Base64
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.util.UUID

class DiagnosticsUploader {
    suspend fun upload(uploadUrl: String, packageFile: File): DiagnosticsUploadResult = withContext(Dispatchers.IO) {
        val normalizedUrl = validateUploadUrl(uploadUrl)
        val boundary = "ShelfDriveDiagnostics-${UUID.randomUUID()}"
        val connection = (URL(normalizedUrl).openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            connectTimeout = CONNECT_TIMEOUT_MS
            readTimeout = READ_TIMEOUT_MS
            doInput = true
            doOutput = true
            setRequestProperty("Content-Type", "multipart/form-data; boundary=$boundary")
            setRequestProperty("Accept", "application/json,text/plain,*/*")
            setRequestProperty("Authorization", basicAuthorizationHeader())
        }

        connection.outputStream.buffered().use { output ->
            output.write("--$boundary\r\n".toByteArray())
            output.write(
                "Content-Disposition: form-data; name=\"file\"; filename=\"${packageFile.name}\"\r\n"
                    .toByteArray(),
            )
            output.write("Content-Type: application/zip\r\n\r\n".toByteArray())
            packageFile.inputStream().use { input -> input.copyTo(output) }
            output.write("\r\n--$boundary--\r\n".toByteArray())
        }

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
        val uri = Uri.parse(trimmed)
        val scheme = uri.scheme
        if (scheme != "http" && scheme != "https") {
            throw IOException("Upload URL must start with http:// or https://.")
        }
        if (uri.host.isNullOrBlank()) {
            throw IOException("Upload URL must contain a host.")
        }
        return trimmed
    }

    private fun readBody(connection: HttpURLConnection): String {
        val stream = connection.errorStream ?: connection.inputStream
        return stream?.bufferedReader()?.use { it.readText() }.orEmpty()
    }

    companion object {
        private const val BASIC_USERNAME = "shelfdrive"
        private const val BASIC_PASSWORD = "diagnostics"
        private const val CONNECT_TIMEOUT_MS = 10_000
        private const val READ_TIMEOUT_MS = 20_000
        private const val MAX_RESPONSE_LENGTH = 240
    }

    private fun basicAuthorizationHeader(): String {
        val credentials = "$BASIC_USERNAME:$BASIC_PASSWORD"
        return "Basic " + Base64.encodeToString(credentials.toByteArray(Charsets.UTF_8), Base64.NO_WRAP)
    }
}

data class DiagnosticsUploadResult(
    val statusCode: Int,
    val message: String,
)
