package ru.daybook.app.data

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.net.URLEncoder
import java.nio.charset.StandardCharsets
import java.util.UUID

class GoogleDriveApi(
    private val client: OkHttpClient = OkHttpClient()
) {
    data class DriveFile(val id: String, val name: String, val version: Long, val etag: String?)

    suspend fun getOrCreateDaybookFolder(accessToken: String): String = withContext(Dispatchers.IO) {
        val query = "name = 'Daybook' and mimeType = 'application/vnd.google-apps.folder' and trashed = false"
        val result = requestJson(
            accessToken,
            "https://www.googleapis.com/drive/v3/files?q=${encode(query)}&spaces=drive&fields=files(id,name)&pageSize=100"
        )
        val files = result.jsonObject["files"]?.jsonArray.orEmpty()
        if (files.isNotEmpty()) return@withContext files.first().jsonObject["id"]!!.jsonPrimitive.content

        val body = """{"name":"Daybook","mimeType":"application/vnd.google-apps.folder"}"""
            .toRequestBody("application/json; charset=utf-8".toMediaType())
        val created = requestJson(
            accessToken,
            "https://www.googleapis.com/drive/v3/files?fields=id,name",
            method = "POST",
            body = body
        )
        created.jsonObject["id"]!!.jsonPrimitive.content
    }

    suspend fun findDataFile(folderId: String, accessToken: String): DriveFile? = withContext(Dispatchers.IO) {
        val query = "'$folderId' in parents and name = 'daybook-data.json' and trashed = false"
        val result = requestJson(
            accessToken,
            "https://www.googleapis.com/drive/v3/files?q=${encode(query)}&spaces=drive&fields=files(id,name,version)&pageSize=100"
        )
        val files = result.jsonObject["files"]?.jsonArray.orEmpty()
        if (files.isEmpty()) return@withContext null

        val item = files.first().jsonObject
        val id = item["id"]!!.jsonPrimitive.content
        val metadataRequest = Request.Builder()
            .url("https://www.googleapis.com/drive/v3/files/$id?fields=id,name,version")
            .header("Authorization", "Bearer $accessToken")
            .get()
            .build()
        client.newCall(metadataRequest).execute().use { response ->
            if (!response.isSuccessful) throw DriveApiException(response.code, response.body?.string().orEmpty())
            val metadata = Json.parseToJsonElement(response.body?.string().orEmpty()).jsonObject
            DriveFile(
                id = id,
                name = "daybook-data.json",
                version = metadata["version"]?.jsonPrimitive?.content?.toLongOrNull() ?: 0L,
                etag = response.header("ETag")
            )
        }
    }

    /** Generic file lookup used for independent Daybook data streams. */
    suspend fun findFile(folderId: String, fileName: String, accessToken: String): DriveFile? = withContext(Dispatchers.IO) {
        val query = "'$folderId' in parents and name = '$fileName' and trashed = false"
        val result = requestJson(
            accessToken,
            "https://www.googleapis.com/drive/v3/files?q=${encode(query)}&spaces=drive&fields=files(id,name,version)&pageSize=100"
        )
        val files = result.jsonObject["files"]?.jsonArray.orEmpty()
        if (files.isEmpty()) return@withContext null
        val item = files.first().jsonObject
        val id = item["id"]!!.jsonPrimitive.content
        val metadataRequest = Request.Builder()
            .url("https://www.googleapis.com/drive/v3/files/$id?fields=id,name,version")
            .header("Authorization", "Bearer $accessToken")
            .get()
            .build()
        client.newCall(metadataRequest).execute().use { response ->
            if (!response.isSuccessful) throw DriveApiException(response.code, response.body?.string().orEmpty())
            val metadata = Json.parseToJsonElement(response.body?.string().orEmpty()).jsonObject
            DriveFile(
                id = id,
                name = fileName,
                version = metadata["version"]?.jsonPrimitive?.content?.toLongOrNull() ?: 0L,
                etag = response.header("ETag")
            )
        }
    }

    suspend fun downloadText(file: DriveFile, accessToken: String): String = withContext(Dispatchers.IO) {
        val request = Request.Builder()
            .url("https://www.googleapis.com/drive/v3/files/${file.id}?alt=media")
            .header("Authorization", "Bearer $accessToken")
            .get()
            .build()
        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) throw DriveApiException(response.code, response.body?.string().orEmpty())
            response.body?.string().orEmpty()
        }
    }

    suspend fun uploadText(
        fileId: String?,
        folderId: String,
        fileName: String,
        payload: String,
        accessToken: String,
        expectedEtag: String? = null
    ): DriveFile = withContext(Dispatchers.IO) {
        if (fileId != null && (expectedEtag.isNullOrBlank() || expectedEtag.startsWith("W/"))) {
            throw DriveApiException(412, "Google Drive не вернул сильный ETag; обновление остановлено ради защиты от перезаписи.")
        }
        val metadata = if (fileId == null) {
            """{"name":"$fileName","mimeType":"application/json","parents":["$folderId"]}"""
        } else {
            """{"name":"$fileName","mimeType":"application/json"}"""
        }
        val multipart = MultipartBody.Builder("daybook_${UUID.randomUUID().toString().replace("-", "")}")
            .setType("multipart/related".toMediaType())
            .addPart(
                okhttp3.Headers.Builder().add("Content-Type", "application/json; charset=UTF-8").build(),
                metadata.toRequestBody("application/json; charset=UTF-8".toMediaType())
            )
            .addPart(
                okhttp3.Headers.Builder().add("Content-Type", "application/json; charset=UTF-8").build(),
                payload.toRequestBody("application/json; charset=UTF-8".toMediaType())
            )
            .build()
        val url = if (fileId == null) {
            "https://www.googleapis.com/upload/drive/v3/files?uploadType=multipart&fields=id,name,version"
        } else {
            "https://www.googleapis.com/upload/drive/v3/files/$fileId?uploadType=multipart&fields=id,name,version"
        }
        val builder = Request.Builder()
            .url(url)
            .header("Authorization", "Bearer $accessToken")
            .method(if (fileId == null) "POST" else "PATCH", multipart)
        if (!expectedEtag.isNullOrBlank()) builder.header("If-Match", expectedEtag)
        client.newCall(builder.build()).execute().use { response ->
            if (!response.isSuccessful) throw DriveApiException(response.code, response.body?.string().orEmpty())
            val json = Json.parseToJsonElement(response.body?.string().orEmpty()).jsonObject
            DriveFile(
                id = json["id"]!!.jsonPrimitive.content,
                name = fileName,
                version = json["version"]?.jsonPrimitive?.content?.toLongOrNull() ?: 0L,
                etag = response.header("ETag")
            )
        }
    }

    suspend fun downloadDocument(file: DriveFile, accessToken: String): DaybookDocument =
        withContext(Dispatchers.IO) {
            val request = Request.Builder()
                .url("https://www.googleapis.com/drive/v3/files/${file.id}?alt=media")
                .header("Authorization", "Bearer $accessToken")
                .get()
                .build()
            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) throw DriveApiException(response.code, response.body?.string().orEmpty())
                DaybookJson.decode(response.body?.string().orEmpty())
            }
        }

    suspend fun uploadDocument(
        fileId: String?,
        folderId: String,
        document: DaybookDocument,
        accessToken: String,
        expectedEtag: String? = null
    ): DriveFile = withContext(Dispatchers.IO) {
        if (fileId != null && (expectedEtag.isNullOrBlank() || expectedEtag.startsWith("W/"))) {
            throw DriveApiException(412, "Google Drive не вернул сильный ETag; обновление остановлено ради защиты от перезаписи.")
        }
        val metadata = if (fileId == null) {
            """{"name":"daybook-data.json","mimeType":"application/json","parents":["$folderId"]}"""
        } else {
            """{"name":"daybook-data.json","mimeType":"application/json"}"""
        }
        val payload = DaybookJson.encode(document)
        val multipart = MultipartBody.Builder("daybook_${UUID.randomUUID().toString().replace("-", "")}")
            .setType("multipart/related".toMediaType())
            .addPart(
                okhttp3.Headers.Builder().add("Content-Type", "application/json; charset=UTF-8").build(),
                metadata.toRequestBody("application/json; charset=UTF-8".toMediaType())
            )
            .addPart(
                okhttp3.Headers.Builder().add("Content-Type", "application/json; charset=UTF-8").build(),
                payload.toRequestBody("application/json; charset=UTF-8".toMediaType())
            )
            .build()

        val url = if (fileId == null) {
            "https://www.googleapis.com/upload/drive/v3/files?uploadType=multipart&fields=id,name,version"
        } else {
            "https://www.googleapis.com/upload/drive/v3/files/$fileId?uploadType=multipart&fields=id,name,version"
        }
        val builder = Request.Builder()
            .url(url)
            .header("Authorization", "Bearer $accessToken")
            .method(if (fileId == null) "POST" else "PATCH", multipart)
        if (!expectedEtag.isNullOrBlank()) builder.header("If-Match", expectedEtag)

        client.newCall(builder.build()).execute().use { response ->
            if (!response.isSuccessful) throw DriveApiException(response.code, response.body?.string().orEmpty())
            val json = Json.parseToJsonElement(response.body?.string().orEmpty()).jsonObject
            DriveFile(
                id = json["id"]!!.jsonPrimitive.content,
                name = "daybook-data.json",
                version = json["version"]?.jsonPrimitive?.content?.toLongOrNull() ?: 0L,
                etag = response.header("ETag")
            )
        }
    }

    private suspend fun requestJson(
        accessToken: String,
        url: String,
        method: String = "GET",
        body: okhttp3.RequestBody? = null
    ) = runBlockingRequest(accessToken, url, method, body)

    private suspend fun runBlockingRequest(
        accessToken: String,
        url: String,
        method: String,
        body: okhttp3.RequestBody?
    ) = withContext(Dispatchers.IO) {
        val builder = Request.Builder().url(url).header("Authorization", "Bearer $accessToken")
        if (body == null) builder.method(method, null) else builder.method(method, body)
        client.newCall(builder.build()).execute().use { response ->
            if (!response.isSuccessful) throw DriveApiException(response.code, response.body?.string().orEmpty())
            Json.parseToJsonElement(response.body?.string().orEmpty())
        }
    }

    private fun encode(value: String): String =
        URLEncoder.encode(value, StandardCharsets.UTF_8.name())
}

class DriveApiException(val statusCode: Int, detail: String) :
    Exception("Google Drive HTTP $statusCode: $detail")
