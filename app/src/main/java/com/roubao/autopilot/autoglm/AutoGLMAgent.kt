package com.roubao.autopilot.autoglm

import android.content.Context
import android.graphics.Bitmap
import com.roubao.autopilot.controller.DeviceController
import com.roubao.autopilot.controller.DeviceController.ExecutionMethod
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
    private val config: AgentConfig = AgentConfig()
) {
    // 兼容旧构造函数
    constructor(
        vlmClient: VLMClient,
        deviceController: DeviceController,
        appPackages: AppPackages?,
        config: AgentConfig
    ) : this(vlmClient, deviceController, appPackages, null, config)

    /**
     * 便捷构造函数 - 使用 Context 自动创建 AppPackages
     */
    constructor(
        vlmClient: VLMClient,
        deviceController: DeviceController,
        context: Context,
        config: AgentConfig = AgentConfig()
    ) : this(vlmClient, deviceController, AppPackages.getInstance(context), null, config)

    /**
     * 完整构造函数 - 双模型 + Context
     */
    constructor(
        visionClient: VLMClient,
        deviceController: DeviceController,
        context: Context,
        planningClient: PlanningClient?,
        config: AgentConfig = AgentConfig()
    ) : this(visionClient, deviceController, AppPackages.getInstance(context), planningClient, config)

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
                    println("[AutoGLMAgent] 使用 Claude 进行任务规划...")
                }
                val planResult = planningClient.planTask(task)
                if (planResult.isSuccess) {
                    taskPlan = planResult.getOrNull()
                    if (config.verbose) {
                        println("[AutoGLMAgent] 规划完成: ${taskPlan?.steps?.size} 步")
                        taskPlan?.steps?.forEachIndexed { i, step ->
                            println("  ${i + 1}. $step")
                        }
                    }
                    callback?.onPlanReady(taskPlan?.steps ?: emptyList())
                } else {
                    if (config.verbose) {
                        println("[AutoGLMAgent] 规划失败: ${planResult.exceptionOrNull()?.message}")
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
            println("[AutoGLMAgent] 验证进度 (步骤 $stepCount)...")
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
                println("[AutoGLMAgent] 验证结果: ${result.progress}% on_track=${result.isOnTrack}")
                result.suggestion?.let { println("[AutoGLMAgent] 建议: $it") }
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
            println("[AutoGLMAgent] ===== Step $stepCount =====")
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
                println("[AutoGLMAgent] 检测到敏感页面，自动停止")
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
            println("[AutoGLMAgent] 调用视觉模型 (streaming=${config.useStreaming})...")
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
                            println("[AutoGLMAgent] 首 token: ${timeToFirstTokenMs}ms")
                        }
                    }

                    override fun onThinking(chunk: String) {
                        // 实时回调思考内容
                        callback?.onThinkingChunk(chunk)
                    }

                    override fun onActionStart() {
                        if (config.verbose) {
                            println("[AutoGLMAgent] 检测到动作...")
                        }
                    }

                    override fun onComplete(response: com.roubao.autopilot.vlm.VLMClient.StreamResponse) {
                        if (config.verbose) {
                            println("[AutoGLMAgent] 流式完成: TTFT=${response.timeToFirstTokenMs}ms, Total=${response.totalTimeMs}ms")
                        }
                        callback?.onPerformanceMetrics(response.timeToFirstTokenMs, response.totalTimeMs)
                    }

                    override fun onError(error: Exception) {
                        if (config.verbose) {
                            println("[AutoGLMAgent] 流式错误: ${error.message}")
                        }
                    }
                }
            )

            if (streamResult.isFailure) {
                val error = streamResult.exceptionOrNull()?.message ?: "未知错误"
                if (config.verbose) {
                    println("[AutoGLMAgent] VLM 调用失败: $error")
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
                    println("[AutoGLMAgent] VLM 调用失败: $error")
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
            println("[AutoGLMAgent] 思考: $thinking")
            println("[AutoGLMAgent] 动作: $parsedAction")
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
                    println("[AutoGLMAgent] 动作解析失败: ${parsedAction.reason}")
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
                        if (config.verbose) println("[AutoGLMAgent] Launch Browser: $appName")
                        // 直接传 "浏览器"，让 DeviceController 尝试多个浏览器包名
                        deviceController.openApp("浏览器")
                    } else {
                        val packageName = appPackages?.smartMatch(appName) ?: appName
                        if (config.verbose) println("[AutoGLMAgent] Launch: $appName -> $packageName")
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
                        if (config.verbose) println("[AutoGLMAgent] Tap: ($x, $y)")
                        deviceController.tap(x, y)
                    }
                    StepResult(true, false, action, "", null, getExecutionMethodName())
                }

                ActionParser.Actions.TYPE, ActionParser.Actions.TYPE_NAME -> {
                    val text = action.params["text"] as? String ?: ""
                    if (config.verbose) println("[AutoGLMAgent] Type: $text")
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
                        if (config.verbose) println("[AutoGLMAgent] Swipe: ($x1,$y1) -> ($x2,$y2)")
                        deviceController.swipe(x1, y1, x2, y2)
                    }
                    StepResult(true, false, action, "", null, getExecutionMethodName())
                }

                ActionParser.Actions.BACK -> {
                    if (config.verbose) println("[AutoGLMAgent] Back")
                    deviceController.back()
                    StepResult(true, false, action, "", null, getExecutionMethodName())
                }

                ActionParser.Actions.HOME -> {
                    if (config.verbose) println("[AutoGLMAgent] Home")
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
                        if (config.verbose) println("[AutoGLMAgent] Long Press: ($x, $y)")
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
                        if (config.verbose) println("[AutoGLMAgent] Double Tap: ($x, $y)")
                        deviceController.doubleTap(x, y)
                    }
                    StepResult(true, false, action, "", null, getExecutionMethodName())
                }

                ActionParser.Actions.WAIT -> {
                    val duration = action.params["duration"] as? String ?: "1 seconds"
                    val seconds = duration.replace(Regex("[^0-9.]"), "").toFloatOrNull() ?: 1f
                    if (config.verbose) println("[AutoGLMAgent] Wait: ${seconds}s")
                    delay((seconds * 1000).toLong())
                    StepResult(true, false, action, "", null)
                }

                ActionParser.Actions.TAKE_OVER -> {
                    val message = action.params["message"] as? String ?: "需要用户协助"
                    if (config.verbose) println("[AutoGLMAgent] Take Over: $message")
                    callback?.onTakeOver(message)
                    StepResult(true, false, action, "", null)
                }

                ActionParser.Actions.NOTE, ActionParser.Actions.CALL_API -> {
                    if (config.verbose) println("[AutoGLMAgent] ${action.action} (placeholder)")
                    StepResult(true, false, action, "", null)
                }

                ActionParser.Actions.INTERACT -> {
                    if (config.verbose) println("[AutoGLMAgent] Interact (placeholder)")
                    StepResult(true, false, action, "", null)
                }

                else -> {
                    if (config.verbose) println("[AutoGLMAgent] 未知动作: ${action.action}")
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
     * 获取当前应用
     */
    private fun getCurrentApp(): String {
        // TODO: 实现获取当前前台应用的逻辑
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
}
