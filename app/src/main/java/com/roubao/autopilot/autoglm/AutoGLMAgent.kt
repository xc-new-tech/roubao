package com.roubao.autopilot.autoglm

import android.app.ActivityManager
import android.content.Context
import android.graphics.Bitmap
import android.util.Log
import com.roubao.autopilot.controller.DeviceController
import com.roubao.autopilot.controller.DeviceController.ExecutionMethod
import com.roubao.autopilot.data.Script
import com.roubao.autopilot.data.ScriptAction
import com.roubao.autopilot.ui.OverlayService
import com.roubao.autopilot.vlm.PlanningClient
import com.roubao.autopilot.vlm.VLMClient
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext
import org.json.JSONArray
import kotlin.coroutines.coroutineContext

/**
 * AutoGLM Agent - 基于 Open-AutoGLM 的单循环 Agent 实现
 * 支持双模型架构：
 * - visionClient (AutoGLM): 截图分析 + 动作提取
 * - planningClient (Claude): 任务规划 + 决策验证 (可选)
 */
class AutoGLMAgent(
    private val visionClient: VLMClient,           // 视觉模型 (AutoGLM)
    private val deviceController: DeviceController,
    private val appPackages: AppPackages? = null,
    private val planningClient: PlanningClient? = null,  // 规划模型 (Claude, 可选)
    private val config: AgentConfig = AgentConfig(),
    private val appContext: Context? = null        // 用于获取前台应用等系统信息
) {
    companion object {
        private const val TAG = "AutoGLMAgent"
    }
    // 兼容旧构造函数
    constructor(
        vlmClient: VLMClient,
        deviceController: DeviceController,
        appPackages: AppPackages?,
        config: AgentConfig
    ) : this(vlmClient, deviceController, appPackages, null, config, null)

    /**
     * 便捷构造函数 - 使用 Context 自动创建 AppPackages
     */
    constructor(
        vlmClient: VLMClient,
        deviceController: DeviceController,
        context: Context,
        config: AgentConfig = AgentConfig()
    ) : this(vlmClient, deviceController, AppPackages.getInstance(context), null, config, context.applicationContext)

    /**
     * 完整构造函数 - 双模型 + Context
     */
    constructor(
        visionClient: VLMClient,
        deviceController: DeviceController,
        context: Context,
        planningClient: PlanningClient?,
        config: AgentConfig = AgentConfig()
    ) : this(visionClient, deviceController, AppPackages.getInstance(context), planningClient, config, context.applicationContext)

    /**
     * Agent 配置
     */
    data class AgentConfig(
        val maxSteps: Int = 50,                    // 最大步数
        val stepDelayMs: Long = 1500,              // 每步后延迟 (等待界面变化)
        val firstStepDelayMs: Long = 3000,         // 首步延迟 (等待应用启动)
        val verbose: Boolean = true,               // 是否输出调试信息
        val systemPrompt: String? = null,          // 自定义系统提示词
        val useStreaming: Boolean = true,          // 是否使用流式输出
        val usePlanning: Boolean = true,           // 是否使用规划模型 (需要 planningClient)
        val verifyInterval: Int = 5                // 每隔多少步进行验证 (0=不验证)
    )

    /**
     * 步骤结果
     */
    data class StepResult(
        val success: Boolean,
        val finished: Boolean,
        val action: ActionParser.ParsedAction?,
        val thinking: String,
        val message: String? = null,
        val executionMethod: String? = null  // "A11y" 或 "Shizuku"
    )

    /**
     * Agent 运行结果
     */
    data class AgentResult(
        val success: Boolean,
        val message: String,
        val stepCount: Int
    )

    /**
     * 步骤回调 (用于 UI 更新)
     */
    interface StepCallback {
        /** 规划完成 (使用 planningClient 时调用) */
        fun onPlanReady(steps: List<String>) {}

        /** 步骤开始 */
        fun onStepStart(stepNumber: Int)

        /** 实时思考内容片段 (流式输出时调用) */
        fun onThinkingChunk(chunk: String) {}

        /** 完整思考内容 (非流式或流式完成后调用) */
        fun onThinking(thinking: String)

        /** 解析到的动作 */
        fun onAction(action: ActionParser.ParsedAction)

        /** 步骤完成 */
        fun onStepComplete(result: StepResult)

        /** 敏感操作确认，返回 true 继续，false 取消 */
        fun onSensitiveAction(message: String): Boolean

        /** 人工接管请求 */
        fun onTakeOver(message: String)

        /** 性能指标 (流式输出时调用) */
        fun onPerformanceMetrics(timeToFirstTokenMs: Long?, totalTimeMs: Long) {}

        /** 验证结果 (使用 planningClient 时调用) */
        fun onVerification(progress: Int, isOnTrack: Boolean, suggestion: String?) {}
    }

    // 对话上下文
    private val context = mutableListOf<org.json.JSONObject>()
    private var stepCount = 0

    // 规划相关
    private var taskPlan: PlanningClient.PlanResult? = null
    private val recentActions = mutableListOf<String>()  // 最近的操作记录
    private var currentTask: String = ""

    /**
     * 运行 Agent 完成任务
     * @param task 用户任务描述
     * @param callback 可选的步骤回调
     * @return 运行结果
     */
    suspend fun run(
        task: String,
        callback: StepCallback? = null
    ): AgentResult = withContext(Dispatchers.Default) {
        // 重置状态
        context.clear()
        stepCount = 0
        taskPlan = null
        recentActions.clear()
        currentTask = task

        try {
            // === 规划阶段 (如果有 planningClient) ===
            if (planningClient != null && config.usePlanning) {
                if (config.verbose) {
                    Log.d(TAG, "使用 Claude 进行任务规划...")
                }
                val planResult = planningClient.planTask(task)
                if (planResult.isSuccess) {
                    taskPlan = planResult.getOrNull()
                    if (config.verbose) {
                        Log.d(TAG, "规划完成: ${taskPlan?.steps?.size} 步")
                        taskPlan?.steps?.forEachIndexed { i, step ->
                            Log.d(TAG, "  ${i + 1}. $step")
                        }
                    }
                    callback?.onPlanReady(taskPlan?.steps ?: emptyList())
                } else {
                    if (config.verbose) {
                        Log.w(TAG, "规划失败: ${planResult.exceptionOrNull()?.message}")
                    }
                    // 规划失败不影响执行，继续使用视觉模型
                }
            }

            // === 执行阶段 ===
            // 首步执行
            var result = executeStep(task, isFirst = true, callback = callback)

            if (result.finished) {
                return@withContext AgentResult(
                    success = result.success,
                    message = result.message ?: "任务完成",
                    stepCount = stepCount
                )
            }

            // 循环执行直到完成或达到最大步数
            while (stepCount < config.maxSteps && coroutineContext.isActive) {
                // === 定期验证 (如果有 planningClient) ===
                if (planningClient != null && config.verifyInterval > 0 &&
                    stepCount > 0 && stepCount % config.verifyInterval == 0) {
                    verifyProgress(callback)
                }

                result = executeStep(isFirst = false, callback = callback)

                if (result.finished) {
                    return@withContext AgentResult(
                        success = result.success,
                        message = result.message ?: "任务完成",
                        stepCount = stepCount
                    )
                }
            }

            // 达到最大步数
            AgentResult(
                success = false,
                message = "达到最大步数限制 (${config.maxSteps})",
                stepCount = stepCount
            )
        } catch (e: CancellationException) {
            AgentResult(
                success = false,
                message = "任务被取消",
                stepCount = stepCount
            )
        } catch (e: Exception) {
            e.printStackTrace()
            AgentResult(
                success = false,
                message = "执行出错: ${e.message}",
                stepCount = stepCount
            )
        }
    }

    /**
     * 验证当前进度 (使用 planningClient)
     */
    private suspend fun verifyProgress(callback: StepCallback?) {
        val planner = planningClient ?: return
        val plan = taskPlan ?: return

        if (config.verbose) {
            Log.d(TAG, "验证进度 (步骤 $stepCount)...")
        }

        val verifyResult = planner.verifyProgress(
            task = currentTask,
            currentStep = stepCount,
            totalSteps = plan.estimatedSteps.coerceAtLeast(config.maxSteps),
            recentActions = recentActions.takeLast(5),
            currentScreenDescription = "执行中"  // TODO: 可以添加更详细的屏幕描述
        )

        if (verifyResult.isSuccess) {
            val result = verifyResult.getOrNull()!!
            if (config.verbose) {
                Log.d(TAG, "验证结果: ${result.progress}% on_track=${result.isOnTrack}")
                result.suggestion?.let { Log.d(TAG, "建议: $it") }
            }
            callback?.onVerification(result.progress, result.isOnTrack, result.suggestion)
        }
    }

    /**
     * 执行单步
     */
    private suspend fun executeStep(
        userPrompt: String? = null,
        isFirst: Boolean = false,
        callback: StepCallback? = null
    ): StepResult = withContext(Dispatchers.IO) {
        stepCount++
        callback?.onStepStart(stepCount)

        if (config.verbose) {
            Log.d(TAG, "===== Step $stepCount =====")
        }

        // 1. 截图获取当前屏幕 (先隐藏悬浮窗)
        OverlayService.setVisible(false)
        delay(100)  // 等待悬浮窗隐藏
        val screenshotResult = deviceController.screenshotWithFallback()
        OverlayService.setVisible(true)
        val screenshot = screenshotResult.bitmap
        val (screenWidth, screenHeight) = deviceController.getScreenSize()

        // 检查敏感页面
        if (screenshotResult.isSensitive) {
            if (config.verbose) {
                Log.w(TAG, "检测到敏感页面，自动停止")
            }
            return@withContext StepResult(
                success = false,
                finished = true,
                action = null,
                thinking = "",
                message = "检测到敏感页面 (支付/密码)，自动停止"
            )
        }

        // 2. 获取当前应用信息
        val currentApp = getCurrentApp()

        // 3. 构建消息
        if (isFirst) {
            // 添加系统消息
            val systemPrompt = config.systemPrompt ?: MessageBuilder.getSystemPrompt()
            context.add(MessageBuilder.createSystemMessage(systemPrompt))

            // 添加首次用户消息 (包含规划步骤)
            context.add(MessageBuilder.buildFirstUserMessage(
                userPrompt!!,
                screenshot,
                currentApp,
                taskPlan?.steps  // 传递规划步骤
            ))
        } else {
            // 添加后续用户消息
            context.add(MessageBuilder.buildFollowUpUserMessage(screenshot, currentApp))
        }

        // 4. 调用视觉模型获取响应
        if (config.verbose) {
            Log.d(TAG, "调用视觉模型 (streaming=${config.useStreaming})...")
        }

        val messagesJson = JSONArray().apply {
            context.forEach { put(it) }
        }

        // 根据配置选择流式或非流式调用
        val thinking: String
        val actionStr: String
        val parsedAction: ActionParser.ParsedAction

        if (config.useStreaming) {
            // 流式调用
            val streamResult = visionClient.predictWithContextStream(
                messagesJson,
                object : com.roubao.autopilot.vlm.VLMClient.StreamCallback {
                    override fun onFirstToken(timeToFirstTokenMs: Long) {
                        if (config.verbose) {
                            Log.d(TAG, "首 token: ${timeToFirstTokenMs}ms")
                        }
                    }

                    override fun onThinking(chunk: String) {
                        // 实时回调思考内容
                        callback?.onThinkingChunk(chunk)
                    }

                    override fun onActionStart() {
                        if (config.verbose) {
                            Log.d(TAG, "检测到动作...")
                        }
                    }

                    override fun onComplete(response: com.roubao.autopilot.vlm.VLMClient.StreamResponse) {
                        if (config.verbose) {
                            Log.d(TAG, "流式完成: TTFT=${response.timeToFirstTokenMs}ms, Total=${response.totalTimeMs}ms")
                        }
                        callback?.onPerformanceMetrics(response.timeToFirstTokenMs, response.totalTimeMs)
                    }

                    override fun onError(error: Exception) {
                        if (config.verbose) {
                            Log.e(TAG, "流式错误: ${error.message}")
                        }
                    }
                }
            )

            if (streamResult.isFailure) {
                val error = streamResult.exceptionOrNull()?.message ?: "未知错误"
                if (config.verbose) {
                    Log.e(TAG, "VLM 调用失败: $error")
                }
                return@withContext StepResult(
                    success = false,
                    finished = true,
                    action = null,
                    thinking = "",
                    message = "VLM 调用失败: $error"
                )
            }

            val streamResponse = streamResult.getOrNull()!!
            thinking = streamResponse.thinking
            actionStr = streamResponse.action.ifEmpty {
                // 如果流式没有解析到动作，从原始内容提取
                ActionParser.extractAction(streamResponse.rawContent)
            }
            parsedAction = ActionParser.parse(actionStr)

        } else {
            // 非流式调用 (原有逻辑)
            val vlmResult = visionClient.predictWithContext(messagesJson)

            if (vlmResult.isFailure) {
                val error = vlmResult.exceptionOrNull()?.message ?: "未知错误"
                if (config.verbose) {
                    Log.e(TAG, "VLM 调用失败: $error")
                }
                return@withContext StepResult(
                    success = false,
                    finished = true,
                    action = null,
                    thinking = "",
                    message = "VLM 调用失败: $error"
                )
            }

            val rawResponse = vlmResult.getOrNull()!!
            thinking = ActionParser.extractThinking(rawResponse)
            actionStr = ActionParser.extractAction(rawResponse)
            parsedAction = ActionParser.parse(actionStr)
        }

        // 5. 回调完整思考内容
        callback?.onThinking(thinking)

        if (config.verbose) {
            Log.d(TAG, "思考: $thinking")
            Log.d(TAG, "动作: $parsedAction")
        }

        // 6. 从上下文中移除图片 (节省 Token)
        if (context.isNotEmpty()) {
            val lastIndex = context.lastIndex
            context[lastIndex] = MessageBuilder.removeImagesFromMessage(context[lastIndex])
        }

        // 7. 添加助手响应到上下文
        context.add(MessageBuilder.createAssistantMessage(
            "<think>$thinking</think><answer>$actionStr</answer>"
        ))

        // 8. 执行动作
        val actionResult = when (parsedAction) {
            is ActionParser.ParsedAction.Finish -> {
                callback?.onAction(parsedAction)
                StepResult(
                    success = true,
                    finished = true,
                    action = parsedAction,
                    thinking = thinking,
                    message = parsedAction.message
                )
            }

            is ActionParser.ParsedAction.Do -> {
                callback?.onAction(parsedAction)
                val result = executeAction(parsedAction, screenWidth, screenHeight, callback)
                // 记录操作用于验证
                recentActions.add("${parsedAction.action}: ${parsedAction.params}")
                result
            }

            is ActionParser.ParsedAction.Error -> {
                if (config.verbose) {
                    Log.w(TAG, "动作解析失败: ${parsedAction.reason}")
                }
                StepResult(
                    success = false,
                    finished = true,
                    action = parsedAction,
                    thinking = thinking,
                    message = "动作解析失败: ${parsedAction.reason}"
                )
            }
        }

        callback?.onStepComplete(actionResult)

        // 步骤延迟
        val delayMs = if (isFirst) config.firstStepDelayMs else config.stepDelayMs
        delay(delayMs)

        actionResult
    }

    /**
     * 执行具体动作
     */
    private suspend fun executeAction(
        action: ActionParser.ParsedAction.Do,
        screenWidth: Int,
        screenHeight: Int,
        callback: StepCallback?
    ): StepResult = withContext(Dispatchers.IO) {
        try {
            when (action.action) {
                ActionParser.Actions.LAUNCH -> {
                    val appName = action.params["app"] as? String ?: ""
                    val lowerAppName = appName.lowercase()

                    // 检查是否是浏览器相关的请求 - 直接传给 DeviceController 处理
                    val isBrowserRequest = lowerAppName.contains("浏览器") ||
                            lowerAppName.contains("browser") ||
                            lowerAppName == "chrome"

                    if (isBrowserRequest) {
                        if (config.verbose) Log.d(TAG, "Launch Browser: $appName")
                        // 直接传 "浏览器"，让 DeviceController 尝试多个浏览器包名
                        deviceController.openApp("浏览器")
                    } else {
                        val packageName = appPackages?.smartMatch(appName) ?: appName
                        if (config.verbose) Log.d(TAG, "Launch: $appName -> $packageName")
                        deviceController.openApp(packageName)
                    }
                    delay(2000)
                    StepResult(true, false, action, "", null, "Shizuku")  // Launch 只能通过 Shizuku
                }

                ActionParser.Actions.TAP -> {
                    val message = action.params["message"] as? String
                    if (message != null) {
                        val proceed = callback?.onSensitiveAction(message) ?: true
                        if (!proceed) {
                            return@withContext StepResult(false, true, action, "", "用户取消敏感操作")
                        }
                    }
                    val element = action.params["element"] as? List<*>
                    if (element != null && element.size >= 2) {
                        val (x, y) = convertRelativeToAbsolute(
                            (element[0] as Number).toInt(),
                            (element[1] as Number).toInt(),
                            screenWidth, screenHeight
                        )
                        if (config.verbose) Log.d(TAG, "Tap: ($x, $y)")
                        deviceController.tap(x, y)
                    }
                    StepResult(true, false, action, "", null, getExecutionMethodName())
                }

                ActionParser.Actions.TYPE, ActionParser.Actions.TYPE_NAME -> {
                    val text = action.params["text"] as? String ?: ""
                    if (config.verbose) Log.d(TAG, "Type: $text")
                    deviceController.type(text)
                    StepResult(true, false, action, "", null, getExecutionMethodName())
                }

                ActionParser.Actions.SWIPE -> {
                    val start = action.params["start"] as? List<*>
                    val end = action.params["end"] as? List<*>
                    if (start != null && end != null && start.size >= 2 && end.size >= 2) {
                        val (x1, y1) = convertRelativeToAbsolute(
                            (start[0] as Number).toInt(),
                            (start[1] as Number).toInt(),
                            screenWidth, screenHeight
                        )
                        val (x2, y2) = convertRelativeToAbsolute(
                            (end[0] as Number).toInt(),
                            (end[1] as Number).toInt(),
                            screenWidth, screenHeight
                        )
                        if (config.verbose) Log.d(TAG, "Swipe: ($x1,$y1) -> ($x2,$y2)")
                        deviceController.swipe(x1, y1, x2, y2)
                    }
                    StepResult(true, false, action, "", null, getExecutionMethodName())
                }

                ActionParser.Actions.BACK -> {
                    if (config.verbose) Log.d(TAG, "Back")
                    deviceController.back()
                    StepResult(true, false, action, "", null, getExecutionMethodName())
                }

                ActionParser.Actions.HOME -> {
                    if (config.verbose) Log.d(TAG, "Home")
                    deviceController.home()
                    StepResult(true, false, action, "", null, getExecutionMethodName())
                }

                ActionParser.Actions.LONG_PRESS -> {
                    val element = action.params["element"] as? List<*>
                    if (element != null && element.size >= 2) {
                        val (x, y) = convertRelativeToAbsolute(
                            (element[0] as Number).toInt(),
                            (element[1] as Number).toInt(),
                            screenWidth, screenHeight
                        )
                        if (config.verbose) Log.d(TAG, "Long Press: ($x, $y)")
                        deviceController.longPress(x, y)
                    }
                    StepResult(true, false, action, "", null, getExecutionMethodName())
                }

                ActionParser.Actions.DOUBLE_TAP -> {
                    val element = action.params["element"] as? List<*>
                    if (element != null && element.size >= 2) {
                        val (x, y) = convertRelativeToAbsolute(
                            (element[0] as Number).toInt(),
                            (element[1] as Number).toInt(),
                            screenWidth, screenHeight
                        )
                        if (config.verbose) Log.d(TAG, "Double Tap: ($x, $y)")
                        deviceController.doubleTap(x, y)
                    }
                    StepResult(true, false, action, "", null, getExecutionMethodName())
                }

                ActionParser.Actions.WAIT -> {
                    val duration = action.params["duration"] as? String ?: "1 seconds"
                    val seconds = duration.replace(Regex("[^0-9.]"), "").toFloatOrNull() ?: 1f
                    if (config.verbose) Log.d(TAG, "Wait: ${seconds}s")
                    delay((seconds * 1000).toLong())
                    StepResult(true, false, action, "", null)
                }

                ActionParser.Actions.TAKE_OVER -> {
                    val message = action.params["message"] as? String ?: "需要用户协助"
                    if (config.verbose) Log.d(TAG, "Take Over: $message")
                    callback?.onTakeOver(message)
                    StepResult(true, false, action, "", null)
                }

                ActionParser.Actions.NOTE, ActionParser.Actions.CALL_API -> {
                    if (config.verbose) Log.d(TAG, "${action.action} (placeholder)")
                    StepResult(true, false, action, "", null)
                }

                ActionParser.Actions.INTERACT -> {
                    if (config.verbose) Log.d(TAG, "Interact (placeholder)")
                    StepResult(true, false, action, "", null)
                }

                else -> {
                    if (config.verbose) Log.w(TAG, "未知动作: ${action.action}")
                    StepResult(false, false, action, "", "未知动作: ${action.action}")
                }
            }
        } catch (e: Exception) {
            e.printStackTrace()
            StepResult(false, false, action, "", "执行动作失败: ${e.message}")
        }
    }

    /** 获取执行方式名称 */
    private fun getExecutionMethodName(): String {
        return when (deviceController.lastExecutionMethod) {
            ExecutionMethod.A11Y -> "A11y"
            ExecutionMethod.SHIZUKU -> "Shizuku"
        }
    }

    /**
     * 相对坐标 (0-999) 转绝对坐标
     */
    private fun convertRelativeToAbsolute(
        relX: Int, relY: Int,
        screenWidth: Int, screenHeight: Int
    ): Pair<Int, Int> {
        val x = (relX / 1000.0 * screenWidth).toInt()
        val y = (relY / 1000.0 * screenHeight).toInt()
        return Pair(x, y)
    }

    /**
     * 获取当前前台应用
     * 优先使用 Shizuku shell 命令获取，回退到 ActivityManager
     */
    private fun getCurrentApp(): String {
        // 方法1: 使用 Shizuku dumpsys 获取前台应用 (更可靠)
        try {
            val result = deviceController.exec("dumpsys activity activities | grep mResumedActivity | head -1")
            if (result.isNotEmpty()) {
                // 解析格式: mResumedActivity: ActivityRecord{xxx com.example.app/.MainActivity t123}
                val packageMatch = Regex("""(\S+)/\.""").find(result)
                if (packageMatch != null) {
                    val packageName = packageMatch.groupValues[1]
                    // 尝试获取应用名称
                    val appName = appPackages?.getAppName(packageName) ?: packageName
                    if (config.verbose) Log.d(TAG, "当前应用: $appName ($packageName)")
                    return appName
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "通过 dumpsys 获取前台应用失败: ${e.message}")
        }

        // 方法2: 使用 ActivityManager (需要 QUERY_ALL_PACKAGES 权限)
        try {
            val ctx = appContext ?: return "unknown"
            val activityManager = ctx.getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager
            if (activityManager != null) {
                @Suppress("DEPRECATION")
                val runningTasks = activityManager.getRunningTasks(1)
                if (runningTasks.isNotEmpty()) {
                    val topActivity = runningTasks[0].topActivity
                    if (topActivity != null) {
                        val packageName = topActivity.packageName
                        val appName = appPackages?.getAppName(packageName) ?: packageName
                        if (config.verbose) Log.d(TAG, "当前应用 (AM): $appName ($packageName)")
                        return appName
                    }
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "通过 ActivityManager 获取前台应用失败: ${e.message}")
        }

        return "unknown"
    }

    /**
     * 获取当前步数
     */
    fun getStepCount(): Int = stepCount

    /**
     * 获取上下文消息数
     */
    fun getContextSize(): Int = context.size

    // ===================== 脚本模式 =====================

    /**
     * 脚本模式运行
     * 优先按脚本动作执行，VLM 负责监督和异常处理
     *
     * @param script 要执行的脚本
     * @param paramValues 参数值映射
     * @param callback 步骤回调
     */
    suspend fun runWithScript(
        script: Script,
        paramValues: Map<String, String> = emptyMap(),
        callback: StepCallback? = null
    ): AgentResult = withContext(Dispatchers.Default) {
        // 重置状态
        context.clear()
        stepCount = 0
        recentActions.clear()
        currentTask = "执行脚本: ${script.name}"

        if (script.actions.isEmpty()) {
            return@withContext AgentResult(false, "脚本没有任何动作", 0)
        }

        try {
            val loopConfig = script.loopConfig
            val totalLoops = when {
                loopConfig.enabled && loopConfig.loopCount > 0 -> loopConfig.loopCount
                loopConfig.enabled && loopConfig.loopCount == 0 -> Int.MAX_VALUE
                else -> 1
            }

            // 回调规划步骤（脚本动作作为预设计划）
            callback?.onPlanReady(script.actions.map { "${it.actionType}: ${it.description}" })

            var loop = 1
            var consecutiveFailures = 0
            val maxConsecutiveFailures = 3  // 连续失败次数阈值
            val verifyInterval = 3  // 每隔 N 步进行 VLM 验证

            while (loop <= totalLoops && coroutineContext.isActive) {
                if (config.verbose) {
                    Log.d(TAG, "===== 脚本循环 $loop/$totalLoops =====")
                }

                for ((index, scriptAction) in script.actions.withIndex()) {
                    if (!coroutineContext.isActive) break

                    stepCount++
                    callback?.onStepStart(stepCount)

                    // 替换参数
                    val substitutedAction = substituteActionParams(scriptAction, paramValues, script)

                    if (config.verbose) {
                        Log.d(TAG, "执行脚本动作 ${index + 1}/${script.actions.size}: ${substitutedAction.actionType}")
                    }

                    // 执行脚本动作
                    val result = executeScriptAction(substitutedAction, callback)

                    if (!result.success) {
                        consecutiveFailures++
                        Log.w(TAG, "动作执行失败 (连续失败: $consecutiveFailures)")

                        if (consecutiveFailures >= maxConsecutiveFailures) {
                            // 连续失败过多，尝试 VLM 介入
                            if (config.verbose) {
                                Log.d(TAG, "连续失败过多，VLM 介入调整...")
                            }

                            val recoveryResult = attemptVLMRecovery(
                                script.name,
                                substitutedAction,
                                index,
                                script.actions.size,
                                callback
                            )

                            if (!recoveryResult.success) {
                                if (loopConfig.stopOnError) {
                                    return@withContext AgentResult(
                                        false,
                                        "脚本执行失败: ${result.message ?: substitutedAction.actionType}",
                                        stepCount
                                    )
                                }
                            } else {
                                consecutiveFailures = 0  // VLM 恢复成功，重置计数
                            }
                        }
                    } else {
                        consecutiveFailures = 0  // 成功执行，重置计数
                    }

                    callback?.onStepComplete(result)

                    // 动作后延时
                    if (substitutedAction.delayAfterMs > 0) {
                        delay(substitutedAction.delayAfterMs)
                    }

                    // === 阶段性 VLM 验证 ===
                    // 每隔 N 步检查执行结果是否符合预期
                    if ((index + 1) % verifyInterval == 0 && index < script.actions.size - 1) {
                        val verifyResult = verifyScriptProgress(
                            script.name,
                            index + 1,
                            script.actions.size,
                            script.actions.getOrNull(index + 1),
                            callback
                        )
                        if (!verifyResult.onTrack) {
                            if (config.verbose) {
                                Log.w(TAG, "VLM 验证: 执行偏离预期 - ${verifyResult.message}")
                            }
                            // 如果严重偏离，尝试恢复
                            if (verifyResult.needsRecovery) {
                                val recoveryResult = attemptVLMRecovery(
                                    script.name,
                                    script.actions.getOrNull(index + 1) ?: substitutedAction,
                                    index + 1,
                                    script.actions.size,
                                    callback
                                )
                                if (!recoveryResult.success && loopConfig.stopOnError) {
                                    return@withContext AgentResult(
                                        false,
                                        "脚本执行偏离: ${verifyResult.message}",
                                        stepCount
                                    )
                                }
                            }
                        } else if (config.verbose) {
                            Log.d(TAG, "VLM 验证: 执行正常")
                        }
                    }
                }

                // 循环间延时
                if (loopConfig.enabled && loop < totalLoops && loopConfig.loopDelayMs > 0) {
                    delay(loopConfig.loopDelayMs)
                }

                loop++
            }

            AgentResult(true, "脚本执行完成", stepCount)

        } catch (e: CancellationException) {
            AgentResult(false, "脚本被取消", stepCount)
        } catch (e: Exception) {
            e.printStackTrace()
            AgentResult(false, "脚本执行出错: ${e.message}", stepCount)
        }
    }

    /**
     * 替换脚本动作中的参数
     */
    private fun substituteActionParams(
        action: ScriptAction,
        paramValues: Map<String, String>,
        script: Script
    ): ScriptAction {
        if (paramValues.isEmpty()) return action

        val newParams = action.params.toMutableMap()

        // 替换 text 参数
        (newParams["text"] as? String)?.let { text ->
            newParams["text"] = substituteParamValue(text, paramValues, script)
        }

        // 替换 app 参数
        (newParams["app"] as? String)?.let { app ->
            newParams["app"] = substituteParamValue(app, paramValues, script)
        }

        return action.copy(params = newParams)
    }

    /**
     * 替换单个字符串中的参数值
     */
    private fun substituteParamValue(
        text: String,
        paramValues: Map<String, String>,
        script: Script
    ): String {
        var result = text

        // 方式1: 替换 ${param_name} 占位符
        val placeholderRegex = Regex("""\$\{(\w+)\}""")
        result = placeholderRegex.replace(result) { match ->
            val paramName = match.groupValues[1]
            paramValues[paramName] ?: match.value
        }

        // 方式2: 用 originalValue 匹配并替换
        for ((paramName, paramValue) in paramValues) {
            if (paramValue.isNotEmpty()) {
                val param = script.params.find { it.name == paramName }
                val originalValue = param?.originalValue
                if (!originalValue.isNullOrEmpty()) {
                    // 用原始值匹配，替换为用户输入的新值
                    result = result.replace(originalValue, paramValue)
                }
            }
        }

        return result
    }

    /**
     * 执行单个脚本动作
     */
    private suspend fun executeScriptAction(
        action: ScriptAction,
        callback: StepCallback?
    ): StepResult = withContext(Dispatchers.IO) {
        try {
            val (screenWidth, screenHeight) = deviceController.getScreenSize()

            // 构造 ParsedAction.Do
            val parsedAction = ActionParser.ParsedAction.Do(
                action = action.actionType,
                params = action.params
            )

            callback?.onAction(parsedAction)

            // 使用现有的 executeAction 方法
            executeAction(parsedAction, screenWidth, screenHeight, callback)

        } catch (e: Exception) {
            Log.e(TAG, "执行脚本动作失败: ${action.actionType}", e)
            StepResult(false, false, null, "", "执行失败: ${e.message}")
        }
    }

    /**
     * VLM 介入尝试恢复
     * 当脚本动作连续失败时，让 VLM 分析当前屏幕并决定如何继续
     */
    private suspend fun attemptVLMRecovery(
        scriptName: String,
        failedAction: ScriptAction,
        actionIndex: Int,
        totalActions: Int,
        callback: StepCallback?
    ): StepResult = withContext(Dispatchers.IO) {
        try {
            // 截图分析当前状态
            OverlayService.setVisible(false)
            delay(100)
            val screenshotResult = deviceController.screenshotWithFallback()
            OverlayService.setVisible(true)
            val screenshot = screenshotResult.bitmap
            val (screenWidth, screenHeight) = deviceController.getScreenSize()

            if (screenshotResult.isSensitive) {
                return@withContext StepResult(
                    false, true, null, "",
                    "检测到敏感页面，停止执行"
                )
            }

            // 构建恢复提示
            val recoveryPrompt = """
脚本「$scriptName」在执行第 ${actionIndex + 1}/$totalActions 步时遇到问题。
预期动作: ${failedAction.actionType} - ${failedAction.description}
请分析当前屏幕，判断：
1. 如果能继续执行原动作，请执行
2. 如果需要调整（如元素位置变化），请给出正确的动作
3. 如果无法继续，请返回 Finish{message="无法继续执行"}
""".trim()

            // 初始化上下文（如果是首次）
            if (context.isEmpty()) {
                val systemPrompt = config.systemPrompt ?: MessageBuilder.getSystemPrompt()
                context.add(MessageBuilder.createSystemMessage(systemPrompt))
            }

            // 添加恢复请求
            val currentApp = getCurrentApp()
            context.add(MessageBuilder.buildFirstUserMessage(
                recoveryPrompt,
                screenshot,
                currentApp,
                null
            ))

            // 调用 VLM
            val messagesJson = JSONArray().apply {
                context.forEach { put(it) }
            }

            val vlmResult = visionClient.predictWithContext(messagesJson)
            if (vlmResult.isFailure) {
                return@withContext StepResult(false, false, null, "", "VLM 调用失败")
            }

            val rawResponse = vlmResult.getOrNull()!!
            val thinking = ActionParser.extractThinking(rawResponse)
            val actionStr = ActionParser.extractAction(rawResponse)
            val parsedAction = ActionParser.parse(actionStr)

            callback?.onThinking(thinking)

            // 移除图片
            if (context.isNotEmpty()) {
                val lastIndex = context.lastIndex
                context[lastIndex] = MessageBuilder.removeImagesFromMessage(context[lastIndex])
            }

            // 添加助手响应
            context.add(MessageBuilder.createAssistantMessage(
                "<think>$thinking</think><answer>$actionStr</answer>"
            ))

            // 执行 VLM 建议的动作
            when (parsedAction) {
                is ActionParser.ParsedAction.Finish -> {
                    callback?.onAction(parsedAction)
                    StepResult(false, true, parsedAction, thinking, parsedAction.message)
                }
                is ActionParser.ParsedAction.Do -> {
                    callback?.onAction(parsedAction)
                    val result = executeAction(parsedAction, screenWidth, screenHeight, callback)
                    recentActions.add("${parsedAction.action}: ${parsedAction.params}")
                    result
                }
                is ActionParser.ParsedAction.Error -> {
                    StepResult(false, false, parsedAction, thinking, "VLM 动作解析失败")
                }
            }

        } catch (e: Exception) {
            Log.e(TAG, "VLM 恢复失败", e)
            StepResult(false, false, null, "", "VLM 恢复失败: ${e.message}")
        }
    }

    /**
     * 验证结果
     */
    data class VerifyResult(
        val onTrack: Boolean,        // 是否在正确轨道上
        val message: String,         // 验证消息
        val needsRecovery: Boolean   // 是否需要恢复操作
    )

    /**
     * 验证脚本执行进度
     * 截图并让 VLM 判断当前状态是否符合预期
     */
    private suspend fun verifyScriptProgress(
        scriptName: String,
        completedSteps: Int,
        totalSteps: Int,
        nextAction: ScriptAction?,
        callback: StepCallback?
    ): VerifyResult = withContext(Dispatchers.IO) {
        try {
            // 截图
            OverlayService.setVisible(false)
            delay(100)
            val screenshotResult = deviceController.screenshotWithFallback()
            OverlayService.setVisible(true)

            if (screenshotResult.isSensitive) {
                return@withContext VerifyResult(false, "检测到敏感页面", true)
            }

            val screenshot = screenshotResult.bitmap
            val currentApp = getCurrentApp()

            // 构建验证提示
            val nextActionDesc = nextAction?.let {
                "${it.actionType}: ${it.description.ifEmpty { it.params.toString() }}"
            } ?: "完成"

            val verifyPrompt = """
你正在验证脚本「$scriptName」的执行进度。
已完成: $completedSteps/$totalSteps 步
下一步预期动作: $nextActionDesc

请分析当前屏幕，判断：
1. 当前状态是否正常（之前的动作是否都执行成功了）？
2. 下一步动作是否可以正常执行？

请回答 "正常" 或 "异常: [原因]"
""".trim()

            // 构建消息（简化版，不保存上下文）
            val systemPrompt = "你是一个脚本执行验证助手。请简洁地判断当前屏幕状态。"
            val messages = JSONArray().apply {
                put(MessageBuilder.createSystemMessage(systemPrompt))
                put(MessageBuilder.buildFirstUserMessage(verifyPrompt, screenshot, currentApp, null))
            }

            val vlmResult = visionClient.predictWithContext(messages)
            if (vlmResult.isFailure) {
                // VLM 调用失败，默认认为正常（不阻塞执行）
                return@withContext VerifyResult(true, "验证跳过: VLM 不可用", false)
            }

            val response = vlmResult.getOrNull()!!.lowercase()
            callback?.onThinking("验证: $response")

            // 解析验证结果
            val isOnTrack = response.contains("正常") && !response.contains("异常")
            val needsRecovery = response.contains("异常") || response.contains("错误") || response.contains("失败")

            VerifyResult(
                onTrack = isOnTrack,
                message = response.take(100),
                needsRecovery = needsRecovery
            )

        } catch (e: Exception) {
            Log.e(TAG, "验证失败", e)
            // 验证失败不阻塞执行
            VerifyResult(true, "验证跳过: ${e.message}", false)
        }
    }
}
