package com.taptrail.app.network

import android.content.Context
import android.util.Log
import com.taptrail.app.BuildConfig
import com.taptrail.app.TapTrailApplication
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.IOException
import java.util.concurrent.TimeUnit

class BackendClient(
    private val context: Context? = null,
    baseUrlOverride: String? = null
) {
    private val client: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(25, TimeUnit.SECONDS)
        .writeTimeout(25, TimeUnit.SECONDS)
        .build()

    var baseUrl: String = sanitizeUrl(baseUrlOverride ?: getSavedBaseUrl())
        set(value) {
            field = sanitizeUrl(value)
            saveBaseUrl(field)
        }

    private val authToken: String
        get() = BuildConfig.AUTH_TOKEN

    companion object {
        private const val TAG = "BackendClient"
        const val DEFAULT_EMULATOR_URL = "http://10.0.2.2:8000"

        fun sanitizeUrl(raw: String): String {
            var clean = raw.trim().trimEnd('/')
            if (clean.isBlank()) return DEFAULT_EMULATOR_URL
            if (!clean.startsWith("http://") && !clean.startsWith("https://")) {
                clean = "http://$clean"
            }
            // Fix any mangled 10.0.2.2 emulator IP typos like 1110.0.2.2, 0.0.2.2, 110.0.2.2
            if (clean.contains(".0.2.2")) {
                clean = clean.replace(Regex("""[0-9]*\.0\.2\.2"""), "10.0.2.2")
            }
            return clean
        }
    }

    private fun getSavedBaseUrl(): String {
        val app = context?.applicationContext as? TapTrailApplication
            ?: runCatching { TapTrailApplication.instance }.getOrNull()
        val prefs = app?.getSharedPreferences(TapTrailApplication.PREFS_NAME, Context.MODE_PRIVATE)
        val saved = prefs?.getString(TapTrailApplication.KEY_BACKEND_URL, BuildConfig.BACKEND_URL)
            ?: BuildConfig.BACKEND_URL
        return sanitizeUrl(saved)
    }

    private fun saveBaseUrl(url: String) {
        val app = context?.applicationContext as? TapTrailApplication
            ?: runCatching { TapTrailApplication.instance }.getOrNull()
        val prefs = app?.getSharedPreferences(TapTrailApplication.PREFS_NAME, Context.MODE_PRIVATE)
        prefs?.edit()?.putString(TapTrailApplication.KEY_BACKEND_URL, sanitizeUrl(url))?.apply()
    }

    suspend fun checkHealth(): ApiResult<String> = withContext(Dispatchers.IO) {
        val url = "$baseUrl/api/v1/health"
        val request = Request.Builder()
            .url(url)
            .addHeader("Authorization", "Bearer $authToken")
            .get()
            .build()

        try {
            client.newCall(request).execute().use { response ->
                if (response.isSuccessful) {
                    val body = response.body?.string().orEmpty()
                    val modelName = try {
                        val json = org.json.JSONObject(body)
                        json.optString("model", "gemma-4-26b-a4b-it")
                    } catch (e: Exception) {
                        "gemma-4-26b-a4b-it"
                    }
                    ApiResult.Success(modelName)
                } else {
                    ApiResult.Error("Health check failed with HTTP ${response.code}: ${response.message}")
                }
            }
        } catch (e: IOException) {
            Log.e(TAG, "Health check network error", e)
            ApiResult.Error("Network error: ${e.localizedMessage ?: "Connection refused"}", e)
        } catch (e: Exception) {
            Log.e(TAG, "Health check unexpected error", e)
            ApiResult.Error("Error: ${e.localizedMessage}", e)
        }
    }

    suspend fun updateApiKey(apiKey: String): ApiResult<String> = withContext(Dispatchers.IO) {
        val url = "$baseUrl/api/v1/config/apikey"
        val jsonPayload = org.json.JSONObject().put("api_key", apiKey.trim()).toString()
        val body = jsonPayload.toRequestBody("application/json".toMediaType())
        val request = Request.Builder()
            .url(url)
            .addHeader("Authorization", "Bearer $authToken")
            .post(body)
            .build()

        try {
            client.newCall(request).execute().use { response ->
                if (response.isSuccessful) {
                    ApiResult.Success("API key updated successfully")
                } else {
                    ApiResult.Error("Failed to update API key (${response.code})")
                }
            }
        } catch (e: Exception) {
            ApiResult.Error("Network error: ${e.localizedMessage}", e)
        }
    }

    suspend fun requestGuidance(
        request: GuidanceRequest,
        jpegBytes: ByteArray
    ): ApiResult<GuidanceResponse> = withContext(Dispatchers.IO) {
        val primaryResult = executeGuidancePost("$baseUrl/api/v1/guidance", request, jpegBytes)
        if (primaryResult is ApiResult.Success) {
            return@withContext primaryResult
        }

        Log.w(TAG, "Primary guidance endpoint failed: ${(primaryResult as ApiResult.Error).message}. Trying fallback...")
        val fallbackResult = executeGuidancePost("$baseUrl/api/v1/guidance/fallback", request, jpegBytes)
        if (fallbackResult is ApiResult.Success) {
            return@withContext ApiResult.Success(fallbackResult.data.copy(isFallback = true))
        }

        return@withContext primaryResult
    }

    private fun executeGuidancePost(
        endpointUrl: String,
        guidanceReq: GuidanceRequest,
        jpegBytes: ByteArray
    ): ApiResult<GuidanceResponse> {
        val mediaTypeJpeg = "image/jpeg".toMediaType()
        val imageRequestBody = jpegBytes.toRequestBody(mediaTypeJpeg)

        val multipartBuilder = MultipartBody.Builder()
            .setType(MultipartBody.FORM)
            .addFormDataPart("request_id", guidanceReq.requestId)
            .addFormDataPart("instruction", guidanceReq.instruction)
            .addFormDataPart("step_index", guidanceReq.stepIndex.toString())
            .addFormDataPart("image_width", guidanceReq.imageWidth.toString())
            .addFormDataPart("image_height", guidanceReq.imageHeight.toString())
            .addFormDataPart("image", "screenshot.jpg", imageRequestBody)
            .addFormDataPart("screenshot", "screenshot.jpg", imageRequestBody)

        val requestBody = multipartBuilder.build()
        val request = Request.Builder()
            .url(endpointUrl)
            .addHeader("Authorization", "Bearer $authToken")
            .post(requestBody)
            .build()

        try {
            client.newCall(request).execute().use { response ->
                val bodyString = response.body?.string().orEmpty()
                if (!response.isSuccessful) {
                    return ApiResult.Error("Backend error (${response.code}): $bodyString")
                }

                if (bodyString.isBlank()) {
                    return ApiResult.Error("Empty response body from backend")
                }

                val guidanceResponse = try {
                    GuidanceResponse.fromJson(bodyString)
                } catch (e: Exception) {
                    return ApiResult.Error("Failed to parse JSON response: ${e.localizedMessage}", e)
                }

                // Validate response fields
                val validated = validateGuidanceResponse(guidanceResponse)
                return ApiResult.Success(validated)
            }
        } catch (e: IOException) {
            Log.e(TAG, "Network error contacting $endpointUrl", e)
            return ApiResult.Error("Network failure: ${e.localizedMessage ?: "Could not reach backend"}", e)
        } catch (e: Exception) {
            Log.e(TAG, "Unexpected error in executeGuidancePost", e)
            return ApiResult.Error("Request failed: ${e.localizedMessage}", e)
        }
    }

    private fun validateGuidanceResponse(resp: GuidanceResponse): GuidanceResponse {
        val validStatuses = setOf("answer", "guide", "clarify", "complete", "error")
        val status = if (resp.status.lowercase() in validStatuses) resp.status.lowercase() else "guide"

        // Validate bbox bounds [x1, y1, x2, y2] if present
        val bbox = resp.targetBboxNorm?.let { list ->
            if (list.size == 4 &&
                list.all { it in 0.0f..1.0f } &&
                list[0] <= list[2] &&
                list[1] <= list[3]
            ) {
                list
            } else {
                Log.w(TAG, "Invalid bounding box coordinates received: $list")
                null
            }
        }

        return resp.copy(status = status, targetBboxNorm = bbox)
    }
}
