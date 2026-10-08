package ru.domru.technics.data

import java.net.URI

/** Сохраняет выданный сервером адрес и определяет контейнер, не меняя подпись ссылки. */
internal object CameraStreamFormat {
    fun validUrl(value: String): String? {
        val uri = runCatching { URI(value) }.getOrNull() ?: return null
        return value.takeIf { uri.scheme?.lowercase() in setOf("http", "https", "rtsp") && !uri.host.isNullOrBlank() }
    }

    fun mimeType(url: String, declared: String): String? {
        val value = declared.substringBefore(';').trim().lowercase()
        return when (value) {
            "video/x-flv", "video/flv" -> "video/x-flv"
            "application/x-mpegurl", "application/vnd.apple.mpegurl" -> "application/x-mpegURL"
            "video/mp4" -> "video/mp4"
            else -> {
                val path = runCatching { URI(url).path }.getOrNull().orEmpty()
                when {
                    path.endsWith(".m3u8", true) -> "application/x-mpegURL"
                    path.endsWith(".flv", true) -> "video/x-flv"
                    path.endsWith(".mp4", true) -> "video/mp4"
                    else -> null
                }
            }
        }
    }
}
