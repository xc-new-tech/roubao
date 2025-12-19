package com.roubao.autopilot.script

import com.roubao.autopilot.autoglm.ActionParser
import com.roubao.autopilot.data.*

/**
 * 脚本转换器
 * 将执行记录转换为可回放的脚本
 */
object ScriptConverter {

    /**
     * 从执行记录创建脚本
     */
    fun fromExecutionRecord(
        record: ExecutionRecord,
        scriptName: String? = null,
        defaultDelay: Long = 1000
    ): Script {
        // 优先从 steps 提取（MobileAgent 模式）
        var actions = record.steps
            .filter { it.outcome != "C" }  // 跳过失败的步骤
            .mapNotNull { step ->
                parseStepToAction(step, defaultDelay)
            }

        // 如果 steps 为空，尝试从 logs 提取（AutoGLM 模式）
        if (actions.isEmpty() && record.logs.isNotEmpty()) {
            actions = parseActionsFromLogs(record.logs, defaultDelay)
        }

        return Script(
            name = scriptName ?: record.title.take(20),
            description = "从执行记录「${record.title}」转换",
            actions = actions,
            sourceRecordId = record.id
        )
    }

    /**
     * 从日志列表中提取动作
     * AutoGLM 模式的日志格式: "动作: Tap {element=[500, 800]}"
     */
    private fun parseActionsFromLogs(logs: List<String>, defaultDelay: Long): List<ScriptAction> {
        val actions = mutableListOf<ScriptAction>()

        for (log in logs) {
            // 跳过非动作日志
            if (!log.startsWith("动作:")) continue

            val action = parseFromLogFormat(log, "", defaultDelay)
            if (action != null) {
                actions.add(action)
            }
        }

        return actions
    }

    /**
     * 解析执行步骤为脚本动作
     */
    private fun parseStepToAction(step: ExecutionStep, defaultDelay: Long): ScriptAction? {
        val actionStr = step.action.trim()

        // 跳过空动作
        if (actionStr.isEmpty()) return null

        // 尝试使用 ActionParser 解析
        val rawAction = ActionParser.extractAction(actionStr)
        val parsed = ActionParser.parse(rawAction)

        return when (parsed) {
            is ActionParser.ParsedAction.Do -> {
                // 跳过不适合回放的动作
                if (parsed.action in listOf(
                    ActionParser.Actions.TAKE_OVER,
                    ActionParser.Actions.NOTE,
                    ActionParser.Actions.CALL_API,
                    ActionParser.Actions.INTERACT
                )) {
                    return null
                }

                ScriptAction(
                    actionType = parsed.action,
                    params = parsed.params,
                    delayAfterMs = defaultDelay,
                    description = step.description.ifEmpty { formatActionDescription(parsed.action, parsed.params) }
                )
            }
            is ActionParser.ParsedAction.Finish -> null  // 结束动作不需要录入
            is ActionParser.ParsedAction.Error -> {
                // 尝试从日志格式解析 (动作: Tap {element=[x,y]})
                parseFromLogFormat(actionStr, step.description, defaultDelay)
            }
        }
    }

    /**
     * 从日志格式解析动作
     * 格式: "动作: Tap {element=[500, 800]}" 或 "动作: Long Press {element=[500, 800]}"
     */
    private fun parseFromLogFormat(log: String, description: String, defaultDelay: Long): ScriptAction? {
        // 匹配 "动作: ActionType {params}" - 动作名可能包含空格
        val actionMatch = Regex("""动作:\s*([A-Za-z_][A-Za-z0-9_ ]*?)\s*\{(.+)\}""").find(log)
        if (actionMatch != null) {
            val actionType = actionMatch.groupValues[1].trim()
            val paramsStr = actionMatch.groupValues[2]
            val params = parseParamsFromLog(paramsStr)

            return ScriptAction(
                actionType = actionType,
                params = params,
                delayAfterMs = defaultDelay,
                description = description.ifEmpty { formatActionDescription(actionType, params) }
            )
        }

        // 匹配简单动作 "动作: Back" 或 "动作: Home" (没有参数)
        val simpleMatch = Regex("""动作:\s*([A-Za-z_][A-Za-z0-9_ ]*?)\s*(\{.*\})?$""").find(log.trim())
        if (simpleMatch != null) {
            val actionType = simpleMatch.groupValues[1].trim()
            // 检查是否有空参数 {}
            val paramsGroup = simpleMatch.groupValues.getOrNull(2)?.trim()
            if (paramsGroup.isNullOrEmpty() || paramsGroup == "{}") {
                return ScriptAction(
                    actionType = actionType,
                    params = emptyMap(),
                    delayAfterMs = defaultDelay,
                    description = description.ifEmpty { actionType }
                )
            }
        }

        return null
    }

    /**
     * 从日志字符串解析参数
     * Map.toString() 格式: {key1=value1, key2=value2}
     * List.toString() 格式: [item1, item2]
     * 注意: 字符串值不带引号
     */
    private fun parseParamsFromLog(paramsStr: String): Map<String, Any> {
        val params = mutableMapOf<String, Any>()

        // 解析 element=[x, y] - 坐标数组
        Regex("""element\s*=\s*\[\s*(\d+)\s*,\s*(\d+)\s*]""").find(paramsStr)?.let {
            params["element"] = listOf(it.groupValues[1].toInt(), it.groupValues[2].toInt())
        }

        // 解析 start=[x, y] - 滑动起点
        Regex("""start\s*=\s*\[\s*(\d+)\s*,\s*(\d+)\s*]""").find(paramsStr)?.let {
            params["start"] = listOf(it.groupValues[1].toInt(), it.groupValues[2].toInt())
        }

        // 解析 end=[x, y] - 滑动终点
        Regex("""end\s*=\s*\[\s*(\d+)\s*,\s*(\d+)\s*]""").find(paramsStr)?.let {
            params["end"] = listOf(it.groupValues[1].toInt(), it.groupValues[2].toInt())
        }

        // 解析 text=xxx - 文本参数 (不带引号，匹配到逗号或结尾)
        // 支持两种格式: text="xxx" 或 text=xxx
        Regex("""text\s*=\s*"([^"]+)"""").find(paramsStr)?.let {
            params["text"] = it.groupValues[1]
        } ?: Regex("""text\s*=\s*([^,}\]]+)""").find(paramsStr)?.let {
            params["text"] = it.groupValues[1].trim()
        }

        // 解析 app=xxx - 应用名
        Regex("""app\s*=\s*"([^"]+)"""").find(paramsStr)?.let {
            params["app"] = it.groupValues[1]
        } ?: Regex("""app\s*=\s*([^,}\]]+)""").find(paramsStr)?.let {
            params["app"] = it.groupValues[1].trim()
        }

        // 解析 duration=xxx - 等待时长
        Regex("""duration\s*=\s*"([^"]+)"""").find(paramsStr)?.let {
            params["duration"] = it.groupValues[1]
        } ?: Regex("""duration\s*=\s*([^,}\]]+)""").find(paramsStr)?.let {
            params["duration"] = it.groupValues[1].trim()
        }

        return params
    }

    /**
     * 格式化动作描述
     */
    private fun formatActionDescription(actionType: String, params: Map<String, Any>): String {
        return when (actionType) {
            ActionParser.Actions.TAP -> {
                val element = params["element"] as? List<*>
                if (element != null) "点击 (${element[0]}, ${element[1]})" else "点击"
            }
            ActionParser.Actions.LONG_PRESS -> {
                val element = params["element"] as? List<*>
                if (element != null) "长按 (${element[0]}, ${element[1]})" else "长按"
            }
            ActionParser.Actions.DOUBLE_TAP -> {
                val element = params["element"] as? List<*>
                if (element != null) "双击 (${element[0]}, ${element[1]})" else "双击"
            }
            ActionParser.Actions.TYPE, ActionParser.Actions.TYPE_NAME -> {
                val text = params["text"] as? String ?: ""
                "输入: ${text.take(20)}${if (text.length > 20) "..." else ""}"
            }
            ActionParser.Actions.SWIPE -> {
                val start = params["start"] as? List<*>
                val end = params["end"] as? List<*>
                if (start != null && end != null) {
                    "滑动 (${start[0]},${start[1]}) → (${end[0]},${end[1]})"
                } else "滑动"
            }
            ActionParser.Actions.LAUNCH -> {
                val app = params["app"] as? String ?: ""
                "启动: $app"
            }
            ActionParser.Actions.BACK -> "返回"
            ActionParser.Actions.HOME -> "主屏幕"
            ActionParser.Actions.WAIT -> {
                val duration = params["duration"] as? String ?: "1s"
                "等待 $duration"
            }
            else -> actionType
        }
    }

    /**
     * 验证脚本是否可回放
     */
    fun validateScript(script: Script): ValidationResult {
        if (script.actions.isEmpty()) {
            return ValidationResult(false, "脚本没有任何动作")
        }

        val invalidActions = script.actions.filter { action ->
            when (action.actionType) {
                ActionParser.Actions.TAP,
                ActionParser.Actions.LONG_PRESS,
                ActionParser.Actions.DOUBLE_TAP -> {
                    val element = action.params["element"] as? List<*>
                    element == null || element.size != 2
                }
                ActionParser.Actions.SWIPE -> {
                    val start = action.params["start"] as? List<*>
                    val end = action.params["end"] as? List<*>
                    start == null || end == null || start.size != 2 || end.size != 2
                }
                ActionParser.Actions.TYPE, ActionParser.Actions.TYPE_NAME -> {
                    action.params["text"] == null
                }
                else -> false
            }
        }

        return if (invalidActions.isEmpty()) {
            ValidationResult(true, "脚本有效")
        } else {
            ValidationResult(false, "存在 ${invalidActions.size} 个无效动作")
        }
    }

    data class ValidationResult(
        val isValid: Boolean,
        val message: String
    )
}
