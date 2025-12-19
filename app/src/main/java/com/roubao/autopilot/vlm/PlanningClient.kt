package com.roubao.autopilot.vlm

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import okhttp3.ConnectionPool
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/**
 * Planning Client - 用于任务规划和决策验证
 * 使用 Claude 等文本模型，不需要视觉能力
 */
class PlanningClient(
    private val apiKey: String,
    baseUrl: String,
    private val model: String = "claude-3-5-sonnet-20241022"
) {
    private val baseUrl: String = normalizeUrl(baseUrl)

    private val client = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(120, TimeUnit.SECONDS)
        .writeTimeout(60, TimeUnit.SECONDS)
        .retryOnConnectionFailure(true)
        .connectionPool(ConnectionPool(5, 1, TimeUnit.MINUTES))
        .build()

    companion object {
        private const val MAX_RETRIES = 3
        private const val RETRY_DELAY_MS = 1000L

        private fun normalizeUrl(url: String): String {
            var normalized = url.trim().removeSuffix("/")
            if (!normalized.startsWith("http://") && !normalized.startsWith("https://")) {
                normalized = "https://$normalized"
            }
            return normalized
        }
    }

    /**
     * 任务规划结果
     */
    data class PlanResult(
        val steps: List<String>,       // 分解的步骤
        val reasoning: String,         // 推理过程
        val estimatedSteps: Int        // 预估步数
    )

    /**
     * 验证结果
     */
    data class VerificationResult(
        val isOnTrack: Boolean,        // 是否在正确轨道
        val progress: Int,             // 进度 0-100
        val suggestion: String?,       // 建议
        val shouldContinue: Boolean    // 是否继续
    )

    /**
     * 规划任务 - 将用户任务分解为子步骤
     */
    suspend fun planTask(task: String): Result<PlanResult> = withContext(Dispatchers.IO) {
        val systemPrompt = """你是一个 Android 手机操作任务规划专家。用户会给你一个任务，你需要将其分解为具体的操作步骤。

重要规则：
1. 每个步骤应该是一个具体的操作，如"打开浏览器"、"点击搜索框"、"输入xxx"
2. 步骤要简洁明了，便于执行
3. 考虑实际的 Android 手机操作流程
4. 严格区分不同的应用：
   - "百度" 指 baidu.com 搜索引擎网站，需要通过浏览器访问
   - "百度地图" 是一个独立的地图 App
   - "浏览器" 指 Chrome、Edge、系统浏览器等
   - "微信"、"支付宝" 等是独立 App
5. 如果任务涉及访问网站，步骤应该是：打开浏览器 → 输入网址或搜索
6. 返回 JSON 格式

返回格式：
{
  "reasoning": "你的推理过程，解释为什么这样规划",
  "steps": ["步骤1", "步骤2", "步骤3"],
  "estimated_steps": 预估的操作步数
}"""

        val messages = JSONArray().apply {
            put(JSONObject().apply {
                put("role", "system")
                put("content", systemPrompt)
            })
            put(JSONObject().apply {
                put("role", "user")
                put("content", "任务: $task")
            })
        }

        val response = chat(messages)
        if (response.isFailure) {
            return@withContext Result.failure(response.exceptionOrNull()!!)
        }

        try {
            val content = response.getOrNull()!!
            // 提取 JSON
            val jsonStr = extractJson(content)
            val json = JSONObject(jsonStr)

            val stepsArray = json.getJSONArray("steps")
            val steps = mutableListOf<String>()
            for (i in 0 until stepsArray.length()) {
                steps.add(stepsArray.getString(i))
            }

            Result.success(PlanResult(
                steps = steps,
                reasoning = json.optString("reasoning", ""),
                estimatedSteps = json.optInt("estimated_steps", steps.size)
            ))
        } catch (e: Exception) {
            Result.failure(Exception("解析规划结果失败: ${e.message}"))
        }
    }

    /**
     * 验证进度 - 检查当前执行状态是否正确
     */
    suspend fun verifyProgress(
        task: String,
        currentStep: Int,
        totalSteps: Int,
        recentActions: List<String>,
        currentScreenDescription: String
    ): Result<VerificationResult> = withContext(Dispatchers.IO) {
        val systemPrompt = """你是一个手机操作任务验证专家。根据任务目标和当前状态，判断执行是否在正确轨道上。

返回 JSON 格式：
{
  "is_on_track": true/false,
  "progress": 0-100 的进度百分比,
  "suggestion": "如果偏离轨道，给出修正建议",
  "should_continue": true/false
}"""

        val userPrompt = """任务目标: $task

当前进度: 第 $currentStep 步 / 共 $totalSteps 步

最近操作:
${recentActions.takeLast(5).joinToString("\n") { "- $it" }}

当前屏幕描述:
$currentScreenDescription

请验证当前执行状态。"""

        val messages = JSONArray().apply {
            put(JSONObject().apply {
                put("role", "system")
                put("content", systemPrompt)
            })
            put(JSONObject().apply {
                put("role", "user")
                put("content", userPrompt)
            })
        }

        val response = chat(messages)
        if (response.isFailure) {
            return@withContext Result.failure(response.exceptionOrNull()!!)
        }

        try {
            val content = response.getOrNull()!!
            val jsonStr = extractJson(content)
            val json = JSONObject(jsonStr)

            Result.success(VerificationResult(
                isOnTrack = json.optBoolean("is_on_track", true),
                progress = json.optInt("progress", (currentStep * 100 / totalSteps)),
                suggestion = json.optString("suggestion", null),
                shouldContinue = json.optBoolean("should_continue", true)
            ))
        } catch (e: Exception) {
            // 解析失败时返回默认继续
            Result.success(VerificationResult(
                isOnTrack = true,
                progress = currentStep * 100 / totalSteps,
                suggestion = null,
                shouldContinue = true
            ))
        }
    }

    /**
     * 决策 - 基于当前状态做出高级决策
     */
    suspend fun makeDecision(
        task: String,
        currentScreen: String,
        options: List<String>,
        context: String = ""
    ): Result<String> = withContext(Dispatchers.IO) {
        val systemPrompt = """你是一个手机操作决策专家。根据任务目标和当前屏幕状态，选择最佳操作。

只返回选项编号（如 1、2、3），不要其他内容。"""

        val optionsStr = options.mapIndexed { i, opt -> "${i + 1}. $opt" }.joinToString("\n")

        val userPrompt = """任务: $task

当前屏幕: $currentScreen
${if (context.isNotEmpty()) "\n上下文: $context" else ""}

可选操作:
$optionsStr

选择最佳操作（只返回数字）:"""

        val messages = JSONArray().apply {
            put(JSONObject().apply {
                put("role", "system")
                put("content", systemPrompt)
            })
            put(JSONObject().apply {
                put("role", "user")
                put("content", userPrompt)
            })
        }

        val response = chat(messages)
        if (response.isFailure) {
            return@withContext Result.failure(response.exceptionOrNull()!!)
        }

        val content = response.getOrNull()!!.trim()
        val choice = content.filter { it.isDigit() }.toIntOrNull()
        if (choice != null && choice in 1..options.size) {
            Result.success(options[choice - 1])
        } else {
            Result.success(options.firstOrNull() ?: "")
        }
    }

    /**
     * 通用聊天接口
     */
    suspend fun chat(messages: JSONArray): Result<String> = withContext(Dispatchers.IO) {
        var lastException: Exception? = null

        for (attempt in 1..MAX_RETRIES) {
            try {
                val requestBody = JSONObject().apply {
                    put("model", model)
                    put("messages", messages)
                    put("max_tokens", 2048)
                    put("temperature", 0.3)
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
                        return@withContext Result.success(message.getString("content"))
                    } else {
                        lastException = Exception("No response from model")
                    }
                } else {
                    lastException = Exception("API error: ${response.code} - $responseBody")
                }
            } catch (e: Exception) {
                println("[PlanningClient] 请求失败 ($attempt/$MAX_RETRIES): ${e.message}")
                lastException = e
                if (attempt < MAX_RETRIES) {
                    delay(RETRY_DELAY_MS * attempt)
                }
            }
        }

        Result.failure(lastException ?: Exception("Unknown error"))
    }

    /**
     * 从文本中提取 JSON
     */
    private fun extractJson(text: String): String {
        // 尝试找到 JSON 块
        val jsonPattern = Regex("""\{[\s\S]*\}""")
        val match = jsonPattern.find(text)
        return match?.value ?: text
    }
}
