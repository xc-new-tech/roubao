package com.roubao.autopilot.vlm

import android.graphics.Bitmap
import android.util.Base64
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext
import okhttp3.ConnectionPool
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import okhttp3.sse.EventSource
import okhttp3.sse.EventSourceListener
import okhttp3.sse.EventSources
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedReader
import java.io.ByteArrayOutputStream
import java.net.UnknownHostException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.coroutines.coroutineContext

/**
 * VLM (Vision Language Model) API 客户端
 * 支持 OpenAI 兼容接口 (GPT-4V, Qwen-VL, Claude, etc.)
 */
class VLMClient(
    private val apiKey: String,
    baseUrl: String = "https://api.openai.com/v1",
    private val model: String = "gpt-4-vision-preview"
) {
    // 规范化 URL：自动添加 https:// 前缀，移除末尾斜杠
    private val baseUrl: String = normalizeUrl(baseUrl)

    private val client = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(90, TimeUnit.SECONDS)
        .writeTimeout(60, TimeUnit.SECONDS)
        .retryOnConnectionFailure(true)
        .connectionPool(ConnectionPool(5, 1, TimeUnit.MINUTES))
        .build()

    companion object {
        private const val MAX_RETRIES = 3
        private const val RETRY_DELAY_MS = 1000L

        /** 规范化 URL：自动添加 https:// 前缀，移除末尾斜杠 */
        private fun normalizeUrl(url: String): String {
            var normalized = url.trim().removeSuffix("/")
            if (!normalized.startsWith("http://") && !normalized.startsWith("https://")) {
                normalized = "https://$normalized"
            }
            return normalized
        }

        /**
         * 从 API 获取可用模型列表
         * @param baseUrl API 基础地址
         * @param apiKey API 密钥
         * @return 模型 ID 列表
         */
        suspend fun fetchModels(baseUrl: String, apiKey: String): Result<List<String>> = withContext(Dispatchers.IO) {
            // 验证 baseUrl 是否为空
            if (baseUrl.isBlank()) {
                return@withContext Result.failure(Exception("Base URL 不能为空"))
            }

            val client = OkHttpClient.Builder()
                .connectTimeout(10, TimeUnit.SECONDS)
                .readTimeout(10, TimeUnit.SECONDS)
                .build()

            // 清理 URL，确保正确拼接
            val cleanBaseUrl = normalizeUrl(baseUrl.removeSuffix("/chat/completions"))

            val request = try {
                Request.Builder()
                    .url("$cleanBaseUrl/models")
                    .addHeader("Authorization", "Bearer $apiKey")
                    .get()
                    .build()
            } catch (e: IllegalArgumentException) {
                return@withContext Result.failure(Exception("Base URL 格式无效: ${e.message}"))
            }

            try {
                client.newCall(request).execute().use { response ->
                    val responseBody = response.body?.string() ?: ""

                    if (response.isSuccessful) {
                        val json = JSONObject(responseBody)
                        val data = json.optJSONArray("data") ?: JSONArray()
                        val models = mutableListOf<String>()
                        for (i in 0 until data.length()) {
                            val item = data.optJSONObject(i)
                            if (item != null) {
                                val id = item.optString("id", "").trim()
                                if (id.isNotEmpty()) {
                                    models.add(id)
                                }
                            }
                        }
                        Result.success(models)
                    } else {
                        Result.failure(Exception("HTTP ${response.code}: $responseBody"))
                    }
                }
            } catch (e: Exception) {
                Result.failure(e)
            }
        }
    }

    /**
     * 调用 VLM 进行多模态推理 (带重试)
     */
    suspend fun predict(
        prompt: String,
        images: List<Bitmap> = emptyList()
    ): Result<String> = withContext(Dispatchers.IO) {
        var lastException: Exception? = null

        // 预先编码图片 (避免重试时重复编码)
        val encodedImages = images.map { bitmapToBase64Url(it) }

        for (attempt in 1..MAX_RETRIES) {
            try {
                val content = JSONArray().apply {
                    put(JSONObject().apply {
                        put("type", "text")
                        put("text", prompt)
                    })
                    encodedImages.forEach { imageUrl ->
                        put(JSONObject().apply {
                            put("type", "image_url")
                            put("image_url", JSONObject().apply {
                                put("url", imageUrl)
                            })
                        })
                    }
                }

                val messages = JSONArray().apply {
                    put(JSONObject().apply {
                        put("role", "user")
                        put("content", content)
                    })
                }

                val requestBody = JSONObject().apply {
                    put("model", model)
                    put("messages", messages)
                    put("max_tokens", 4096)
                    put("temperature", 0.0)
                    put("top_p", 0.85)
                    put("frequency_penalty", 0.2)  // 减少重复输出
                }

                val request = Request.Builder()
                    .url("$baseUrl/chat/completions")
                    .addHeader("Authorization", "Bearer $apiKey")
                    .addHeader("Content-Type", "application/json")
                    .post(requestBody.toString().toRequestBody("application/json".toMediaType()))
                    .build()

                val response = client.newCall(request).execute()
                val responseBody = response.body?.string() ?: ""

                if (response.isSuccessful) {
                    val json = JSONObject(responseBody)
                    val choices = json.getJSONArray("choices")
                    if (choices.length() > 0) {
                        val message = choices.getJSONObject(0).getJSONObject("message")
                        val responseContent = message.getString("content")
                        return@withContext Result.success(responseContent)
                    } else {
                        lastException = Exception("No response from model")
                    }
                } else {
                    lastException = Exception("API error: ${response.code} - $responseBody")
                }
            } catch (e: UnknownHostException) {
                // DNS 解析失败，重试
                println("[VLMClient] DNS 解析失败，重试 $attempt/$MAX_RETRIES...")
                lastException = e
                if (attempt < MAX_RETRIES) {
                    delay(RETRY_DELAY_MS * attempt)
                }
            } catch (e: java.net.SocketTimeoutException) {
                // 超时，重试
                println("[VLMClient] 请求超时，重试 $attempt/$MAX_RETRIES...")
                lastException = e
                if (attempt < MAX_RETRIES) {
                    delay(RETRY_DELAY_MS * attempt)
                }
            } catch (e: java.io.IOException) {
                // IO 错误，重试
                println("[VLMClient] IO 错误: ${e.message}，重试 $attempt/$MAX_RETRIES...")
                lastException = e
                if (attempt < MAX_RETRIES) {
                    delay(RETRY_DELAY_MS * attempt)
                }
            } catch (e: Exception) {
                // 其他错误，不重试
                return@withContext Result.failure(e)
            }
        }

        Result.failure(lastException ?: Exception("Unknown error"))
    }

    /**
     * 调用 VLM 进行多模态推理 (使用完整对话历史)
     * @param messagesJson OpenAI 兼容的 messages JSON 数组
     */
    suspend fun predictWithContext(
        messagesJson: JSONArray
    ): Result<String> = withContext(Dispatchers.IO) {
        var lastException: Exception? = null

        for (attempt in 1..MAX_RETRIES) {
            try {
                val requestBody = JSONObject().apply {
                    put("model", model)
                    put("messages", messagesJson)
                    put("max_tokens", 4096)
                    put("temperature", 0.0)
                }

                val request = Request.Builder()
                    .url("$baseUrl/chat/completions")
                    .addHeader("Authorization", "Bearer $apiKey")
                    .addHeader("Content-Type", "application/json")
                    .post(requestBody.toString().toRequestBody("application/json".toMediaType()))
                    .build()

                val response = client.newCall(request).execute()
                val responseBody = response.body?.string() ?: ""

                if (response.isSuccessful) {
                    val json = JSONObject(responseBody)
                    val choices = json.getJSONArray("choices")
                    if (choices.length() > 0) {
                        val message = choices.getJSONObject(0).getJSONObject("message")
                        val responseContent = message.getString("content")
                        return@withContext Result.success(responseContent)
                    } else {
                        lastException = Exception("No response from model")
                    }
                } else {
                    lastException = Exception("API error: ${response.code} - $responseBody")
                }
            } catch (e: UnknownHostException) {
                println("[VLMClient] DNS 解析失败，重试 $attempt/$MAX_RETRIES...")
                lastException = e
                if (attempt < MAX_RETRIES) {
                    delay(RETRY_DELAY_MS * attempt)
                }
            } catch (e: java.net.SocketTimeoutException) {
                println("[VLMClient] 请求超时，重试 $attempt/$MAX_RETRIES...")
                lastException = e
                if (attempt < MAX_RETRIES) {
                    delay(RETRY_DELAY_MS * attempt)
                }
            } catch (e: java.io.IOException) {
                println("[VLMClient] IO 错误: ${e.message}，重试 $attempt/$MAX_RETRIES...")
                lastException = e
                if (attempt < MAX_RETRIES) {
                    delay(RETRY_DELAY_MS * attempt)
                }
            } catch (e: Exception) {
                return@withContext Result.failure(e)
            }
        }

        Result.failure(lastException ?: Exception("Unknown error"))
    }

    /**
     * 流式输出回调接口
     */
    interface StreamCallback {
        /** 收到第一个 token */
        fun onFirstToken(timeToFirstTokenMs: Long) {}

        /** 收到思考内容 (do/finish 之前的部分) */
        fun onThinking(chunk: String)

        /** 检测到动作开始 (do/finish 标记) */
        fun onActionStart() {}

        /** 收到动作内容 */
        fun onAction(chunk: String) {}

        /** 流式输出完成 */
        fun onComplete(response: StreamResponse)

        /** 发生错误 */
        fun onError(error: Exception)
    }

    /**
     * 流式响应结果
     */
    data class StreamResponse(
        val thinking: String,              // 思考部分
        val action: String,                // 动作部分
        val rawContent: String,            // 原始完整内容
        val timeToFirstTokenMs: Long?,     // 首 token 时间 (毫秒)
        val timeToActionMs: Long?,         // 到达动作的时间 (毫秒)
        val totalTimeMs: Long              // 总耗时 (毫秒)
    )

    /**
     * 流式调用 VLM (使用完整对话历史)
     * @param messagesJson OpenAI 兼容的 messages JSON 数组
     * @param callback 流式回调
     */
    suspend fun predictWithContextStream(
        messagesJson: JSONArray,
        callback: StreamCallback
    ): Result<StreamResponse> = withContext(Dispatchers.IO) {
        val startTime = System.currentTimeMillis()
        var timeToFirstToken: Long? = null
        var timeToAction: Long? = null

        val rawContent = StringBuilder()
        val thinkingContent = StringBuilder()
        val actionContent = StringBuilder()

        var inActionPhase = false
        var buffer = StringBuilder()
        val actionMarkers = listOf("do(action=", "finish(message=")

        try {
            val requestBody = JSONObject().apply {
                put("model", model)
                put("messages", messagesJson)
                put("max_tokens", 4096)
                put("temperature", 0.0)
                put("top_p", 0.85)
                put("frequency_penalty", 0.2)
                put("stream", true)  // 启用流式输出
            }

            val request = Request.Builder()
                .url("$baseUrl/chat/completions")
                .addHeader("Authorization", "Bearer $apiKey")
                .addHeader("Content-Type", "application/json")
                .addHeader("Accept", "text/event-stream")
                .post(requestBody.toString().toRequestBody("application/json".toMediaType()))
                .build()

            val response = client.newCall(request).execute()

            if (!response.isSuccessful) {
                val errorBody = response.body?.string() ?: ""
                val error = Exception("API error: ${response.code} - $errorBody")
                callback.onError(error)
                return@withContext Result.failure(error)
            }

            val reader = response.body?.source()?.inputStream()?.bufferedReader()
                ?: return@withContext Result.failure(Exception("Empty response body"))

            reader.useLines { lines ->
                for (line in lines) {
                    // 检查协程是否被取消
                    if (!coroutineContext.isActive) {
                        break
                    }

                    // SSE 格式: data: {...}
                    if (!line.startsWith("data: ")) continue
                    val data = line.removePrefix("data: ").trim()

                    // 流结束标记
                    if (data == "[DONE]") break

                    try {
                        val json = JSONObject(data)
                        val choices = json.optJSONArray("choices") ?: continue
                        if (choices.length() == 0) continue

                        val delta = choices.getJSONObject(0).optJSONObject("delta") ?: continue
                        val content = delta.optString("content", "")

                        if (content.isEmpty()) continue

                        // 记录首 token 时间
                        if (timeToFirstToken == null) {
                            timeToFirstToken = System.currentTimeMillis() - startTime
                            callback.onFirstToken(timeToFirstToken!!)
                        }

                        rawContent.append(content)

                        // 如果已经在动作阶段，直接累积
                        if (inActionPhase) {
                            actionContent.append(content)
                            callback.onAction(content)
                            continue
                        }

                        // 否则检测是否进入动作阶段
                        buffer.append(content)

                        // 检查是否包含动作标记
                        var markerFound = false
                        for (marker in actionMarkers) {
                            if (buffer.contains(marker)) {
                                // 找到标记，分离思考和动作
                                val parts = buffer.toString().split(marker, limit = 2)
                                val thinking = parts[0]
                                val action = marker + (if (parts.size > 1) parts[1] else "")

                                // 输出剩余的思考内容
                                if (thinking.isNotEmpty()) {
                                    thinkingContent.append(thinking)
                                    callback.onThinking(thinking)
                                }

                                // 进入动作阶段
                                inActionPhase = true
                                timeToAction = System.currentTimeMillis() - startTime
                                callback.onActionStart()

                                actionContent.append(action)
                                callback.onAction(action)

                                markerFound = true
                                break
                            }
                        }

                        if (markerFound) continue

                        // 检查 buffer 是否可能是标记的前缀
                        var isPotentialMarker = false
                        for (marker in actionMarkers) {
                            for (i in 1 until marker.length) {
                                if (buffer.endsWith(marker.substring(0, i))) {
                                    isPotentialMarker = true
                                    break
                                }
                            }
                            if (isPotentialMarker) break
                        }

                        // 如果不是潜在标记前缀，安全输出
                        if (!isPotentialMarker) {
                            val text = buffer.toString()
                            thinkingContent.append(text)
                            callback.onThinking(text)
                            buffer.clear()
                        }

                    } catch (e: Exception) {
                        // JSON 解析错误，跳过这一行
                        println("[VLMClient] Stream parse error: ${e.message}")
                    }
                }
            }

            // 处理剩余的 buffer
            if (buffer.isNotEmpty() && !inActionPhase) {
                thinkingContent.append(buffer)
                callback.onThinking(buffer.toString())
            }

            val totalTime = System.currentTimeMillis() - startTime

            val streamResponse = StreamResponse(
                thinking = thinkingContent.toString().trim(),
                action = actionContent.toString().trim(),
                rawContent = rawContent.toString(),
                timeToFirstTokenMs = timeToFirstToken,
                timeToActionMs = timeToAction,
                totalTimeMs = totalTime
            )

            callback.onComplete(streamResponse)
            Result.success(streamResponse)

        } catch (e: Exception) {
            callback.onError(e)
            Result.failure(e)
        }
    }

    /**
     * 简化的流式调用 (返回解析后的思考和动作)
     */
    suspend fun predictWithContextStreamSimple(
        messagesJson: JSONArray,
        onThinking: ((String) -> Unit)? = null
    ): Result<StreamResponse> = withContext(Dispatchers.IO) {
        val thinkingBuilder = StringBuilder()

        predictWithContextStream(messagesJson, object : StreamCallback {
            override fun onThinking(chunk: String) {
                thinkingBuilder.append(chunk)
                onThinking?.invoke(chunk)
            }

            override fun onComplete(response: StreamResponse) {
                // 完成时不需要额外处理
            }

            override fun onError(error: Exception) {
                // 错误会通过 Result 返回
            }
        })
    }

    /**
     * Bitmap 转 Base64 URL (只压缩质量，不压缩分辨率)
     * 保持原始分辨率以确保坐标准确
     */
    private fun bitmapToBase64Url(bitmap: Bitmap): String {
        val outputStream = ByteArrayOutputStream()
        // 使用 JPEG 格式，质量 70%，保持原始分辨率
        bitmap.compress(Bitmap.CompressFormat.JPEG, 70, outputStream)
        val bytes = outputStream.toByteArray()
        println("[VLMClient] 图片压缩: ${bitmap.width}x${bitmap.height}, ${bytes.size / 1024}KB")
        val base64 = Base64.encodeToString(bytes, Base64.NO_WRAP)
        return "data:image/jpeg;base64,$base64"
    }

    /**
     * 调整图片大小
     */
    private fun resizeBitmap(bitmap: Bitmap, maxWidth: Int, maxHeight: Int): Bitmap {
        val width = bitmap.width
        val height = bitmap.height

        if (width <= maxWidth && height <= maxHeight) {
            return bitmap
        }

        val ratio = minOf(maxWidth.toFloat() / width, maxHeight.toFloat() / height)
        val newWidth = (width * ratio).toInt()
        val newHeight = (height * ratio).toInt()

        return Bitmap.createScaledBitmap(bitmap, newWidth, newHeight, true)
    }
}

/**
 * 常用 VLM 配置
 */
object VLMConfigs {
    // OpenAI GPT-4V
    fun gpt4v(apiKey: String) = VLMClient(
        apiKey = apiKey,
        baseUrl = "https://api.openai.com/v1",
        model = "gpt-4-vision-preview"
    )

    // Qwen-VL (阿里云)
    fun qwenVL(apiKey: String) = VLMClient(
        apiKey = apiKey,
        baseUrl = "https://dashscope.aliyuncs.com/compatible-mode/v1",
        model = "qwen-vl-max"
    )

    // Claude (Anthropic)
    fun claude(apiKey: String) = VLMClient(
        apiKey = apiKey,
        baseUrl = "https://api.anthropic.com/v1",
        model = "claude-3-5-sonnet-20241022"
    )

    // 自定义 (vLLM / Ollama / LocalAI)
    fun custom(apiKey: String, baseUrl: String, model: String) = VLMClient(
        apiKey = apiKey,
        baseUrl = baseUrl,
        model = model
    )
}
