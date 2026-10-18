package com.taptrail.app.network

import org.json.JSONArray
import org.json.JSONObject

data class GuidanceRequest(
    val requestId: String,
    val instruction: String,
    val stepIndex: Int,
    val imageWidth: Int,
    val imageHeight: Int
)

data class GuidanceResponse(
    val status: String, // "answer", "guide", "clarify", "complete", "error"
    val message: String,
    val targetLabel: String? = null,
    val targetBboxNorm: List<Float>? = null, // [x1, y1, x2, y2] normalized
    val interaction: String? = null, // "tap", "swipe", "long_press"
    val isFallback: Boolean = false
) {
    companion object {
        fun fromJson(jsonStr: String): GuidanceResponse {
            val json = JSONObject(jsonStr)
            val status = json.optString("status", "answer")
            val message = json.optString("message", "")
            val targetLabel = if (json.has("target_label") && !json.isNull("target_label")) {
                json.getString("target_label")
            } else if (json.has("targetLabel") && !json.isNull("targetLabel")) {
                json.getString("targetLabel")
            } else null

            val targetBboxNorm = parseBbox(json)
            val interaction = if (json.has("interaction") && !json.isNull("interaction")) {
                json.getString("interaction")
            } else null
            val isFallback = json.optBoolean("is_fallback", json.optBoolean("isFallback", false))

            return GuidanceResponse(
                status = status,
                message = message,
                targetLabel = targetLabel,
                targetBboxNorm = targetBboxNorm,
                interaction = interaction,
                isFallback = isFallback
            )
        }

        private fun parseBbox(json: JSONObject): List<Float>? {
            val key = if (json.has("target_bbox_norm")) "target_bbox_norm" else if (json.has("targetBboxNorm")) "targetBboxNorm" else null
            if (key == null || json.isNull(key)) return null

            val arr: JSONArray = json.optJSONArray(key) ?: return null
            if (arr.length() != 4) return null

            val list = mutableListOf<Float>()
            for (i in 0 until 4) {
                list.add(arr.getDouble(i).toFloat())
            }
            return list
        }
    }
}

sealed class ApiResult<out T> {
    data class Success<out T>(val data: T) : ApiResult<T>()
    data class Error(val message: String, val throwable: Throwable? = null) : ApiResult<Nothing>()
}
