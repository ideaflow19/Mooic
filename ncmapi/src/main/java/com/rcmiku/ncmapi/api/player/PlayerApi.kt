package com.rcmiku.ncmapi.api.player

import com.rcmiku.ncmapi.api.API_BASE_URL
import com.rcmiku.ncmapi.api.UNBLOCK_BASE_URL
import com.rcmiku.ncmapi.api.apiClient
import com.rcmiku.ncmapi.api.apiGet
import com.rcmiku.ncmapi.model.LyricResponse
import com.rcmiku.ncmapi.model.SongUrl
import com.rcmiku.ncmapi.model.SongUrlResponse
import io.ktor.client.request.parameter
import io.ktor.client.request.request
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpMethod
import io.ktor.http.isSuccess
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull

object PlayerApi {
    private val unblockSources = listOf(
        "bikonoo",
        "qijieya",
        "msls",
        "unm",
        "gdmusic",
        "byfuns",
        "whitisnot",
        "baka"
    )

    suspend fun songPlayUrlV1(songId: String, songLevel: SongLevel = SongLevel.STANDARD): Result<SongUrlResponse> {
        val parsed = parseSongId(songId)
        val realId = parsed.id

        return if (parsed.shouldUseApiUnblock()) {
            // VIP/restricted songs are resolved by api-enhanced before ExoPlayer starts playback.
            val apiMatchResult = tryApiEnhancedMatchUrl(realId)
            if (apiMatchResult.hasPlayableUrl()) {
                return apiMatchResult
            }

            val unblockResult = tryUnblockUrl(realId)
            if (unblockResult.hasPlayableUrl()) {
                return unblockResult
            }

            Result.failure(Exception("No full unlocked URL found"))
        } else {
            val apiResult = apiGet<SongUrlResponse>(
                "/song/url/v1",
                mapOf("id" to realId, "level" to songLevel.value)
            )
            if (apiResult.hasPlayableUrl()) {
                apiResult
            } else {
                val apiUnblockResult = tryApiEnhancedUnblockUrl(realId, songLevel)
                if (apiUnblockResult.hasPlayableUrl()) {
                    apiUnblockResult
                } else {
                    tryUnblockUrl(realId)
                }
            }
        }
    }

    private data class ParsedSongId(
        val id: String,
        val fee: Int = 0,
        val pl: Int? = null,
        val dl: Int? = null,
        val fl: Int? = null,
        val st: Int? = null
    ) {
        fun shouldUseApiUnblock(): Boolean =
            fee == 1 || fee == 4 || fee == 8 || pl == 0 || (st != null && st < 0)
    }

    private fun parseSongId(raw: String): ParsedSongId {
        val queryIndex = raw.indexOf('?')
        if (queryIndex == -1) return ParsedSongId(raw)
        val id = raw.substring(0, queryIndex)
        val query = raw.substring(queryIndex + 1)
        val params = query.split("&").associate {
            val eq = it.indexOf('=')
            if (eq > 0) it.substring(0, eq) to it.substring(eq + 1) else it to ""
        }
        val fee = params["fee"]?.toIntOrNull() ?: 0
        val pl = params["pl"]?.toIntOrNull()
        val dl = params["dl"]?.toIntOrNull()
        val fl = params["fl"]?.toIntOrNull()
        val st = params["st"]?.toIntOrNull()
        return ParsedSongId(id = id, fee = fee, pl = pl, dl = dl, fl = fl, st = st)
    }

    private fun Result<SongUrlResponse>.hasPlayableUrl(): Boolean =
        getOrNull()?.data?.any { it.hasFullPlayableUrl() } == true

    private fun SongUrl.hasFullPlayableUrl(): Boolean {
        val playableUrl = url?.takeIf { it.isNotBlank() } ?: return false
        if (isLikelyTrialUrl(playableUrl)) return false
        if (freeTrialInfo != null && freeTrialInfo !is JsonNull) return false
        if (time in 1..60_000) return false
        return true
    }

    private suspend fun tryApiEnhancedMatchUrl(songId: String): Result<SongUrlResponse> =
        tryMatchUrl("${API_BASE_URL.trimEnd('/')}/song/url/match", songId)

    private suspend fun tryApiEnhancedUnblockUrl(
        songId: String,
        songLevel: SongLevel
    ): Result<SongUrlResponse> {
        return apiGet(
            "/song/url/v1",
            mapOf(
                "id" to songId,
                "level" to songLevel.value,
                "unblock" to "true"
            )
        )
    }

    private suspend fun tryUnblockUrl(songId: String): Result<SongUrlResponse> =
        tryMatchUrl("${UNBLOCK_BASE_URL.trimEnd('/')}/match", songId)

    private suspend fun tryMatchUrl(endpoint: String, songId: String): Result<SongUrlResponse> {
        var lastError: Exception? = null
        unblockSources.forEach { source ->
            try {
                requestMatchedUrl(endpoint, songId, source)?.let { data ->
                    return Result.success(
                        SongUrlResponse(
                            data = listOf(
                                SongUrl(
                                    id = songId.toLongOrNull() ?: 0L,
                                    url = data.url,
                                    br = data.br
                                )
                            )
                        )
                    )
                }
            } catch (e: Exception) {
                lastError = e
            }
        }
        return Result.failure(lastError ?: Exception("No full unlocked URL found"))
    }

    private suspend fun requestMatchedUrl(
        endpoint: String,
        songId: String,
        source: String
    ): UnblockData? {
        return try {
            val response = apiClient.request(endpoint) {
                method = HttpMethod.Get
                parameter("id", songId)
                parameter("source", source)
            }
            if (response.status.isSuccess()) {
                parseUnblockResponse(response.bodyAsText())
            } else {
                null
            }
        } catch (e: Exception) {
            null
        }
    }

    private data class UnblockData(val url: String, val br: Int)

    private fun parseUnblockResponse(body: String): UnblockData? {
        return try {
            val json = kotlinx.serialization.json.Json {
                ignoreUnknownKeys = true
                isLenient = true
            }
            val jsonObj = json.parseToJsonElement(body).jsonObject
            val code = jsonObj["code"]?.jsonPrimitive?.intOrNull
            if (code != null && code != 200) return null

            val proxyUrl = jsonObj["proxyUrl"]?.jsonPrimitive?.contentOrNull
                ?.takeIf { it.isNotBlank() }
            val data = extractMatchedData(jsonObj["data"])
            val playableUrl = proxyUrl ?: data?.url?.takeIf { it.isNotBlank() }
            playableUrl?.let { UnblockData(it, data?.br ?: 320000) }
        } catch (e: Exception) {
            null
        }
    }

    private fun extractMatchedData(dataElement: JsonElement?): UnblockData? {
        val dataObj = dataElement as? JsonObject
        val dataPrimitive = dataElement as? JsonPrimitive
        val dataArray = dataElement as? JsonArray

        dataObj?.let { return extractUrlFromObject(it) }

        dataPrimitive?.contentOrNull
            ?.takeIf { it.isNotBlank() && !isLikelyTrialUrl(it) }
            ?.let { return UnblockData(it, 320000) }

        dataArray?.forEach { element ->
            when (element) {
                is JsonObject -> extractUrlFromObject(element)?.let { return it }
                is JsonPrimitive -> element.contentOrNull
                    ?.takeIf { it.isNotBlank() && !isLikelyTrialUrl(it) }
                    ?.let { return UnblockData(it, 320000) }
                else -> Unit
            }
        }

        return null
    }

    private fun extractUrlFromObject(dataObj: JsonObject): UnblockData? {
        if (dataObj["freeTrialInfo"] != null && dataObj["freeTrialInfo"] !is JsonNull) return null

        val url = dataObj["url"]?.jsonPrimitive?.contentOrNull
            ?.takeIf { it.isNotBlank() && !isLikelyTrialUrl(it) }
        val time = dataObj["time"]?.jsonPrimitive?.longOrNull
        val br = dataObj["br"]?.jsonPrimitive?.intOrNull
            ?: dataObj["bitrate"]?.jsonPrimitive?.intOrNull
            ?: 320000

        if (time != null && time in 1..60_000) return null
        return url?.let { UnblockData(it, br) }
    }

    private fun isLikelyTrialUrl(url: String): Boolean =
        url.contains("musicrep-ts", ignoreCase = true)

    suspend fun songLyric(musicId: Long): Result<LyricResponse> =
        apiGet("/lyric", mapOf("id" to musicId))
}
