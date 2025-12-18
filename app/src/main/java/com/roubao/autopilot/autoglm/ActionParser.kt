package com.roubao.autopilot.autoglm

/**
 * AutoGLM 动作解析器
 * 解析函数式动作语法: do(action="Tap", element=[x,y]) 或 finish(message="完成")
 */
object ActionParser {

    /**
     * 解析后的动作
     */
    sealed class ParsedAction {
        /**
         * 执行动作
         */
        data class Do(
            val action: String,
            val params: Map<String, Any>
        ) : ParsedAction()

        /**
         * 结束任务
         */
        data class Finish(
            val message: String
        ) : ParsedAction()

        /**
         * 解析失败
         */
        data class Error(
            val reason: String,
            val raw: String
        ) : ParsedAction()
    }

    /**
     * 支持的动作类型
     */
    object Actions {
        const val LAUNCH = "Launch"
        const val TAP = "Tap"
        const val TYPE = "Type"
        const val TYPE_NAME = "Type_Name"
        const val SWIPE = "Swipe"
        const val BACK = "Back"
        const val HOME = "Home"
        const val LONG_PRESS = "Long Press"
        const val DOUBLE_TAP = "Double Tap"
        const val WAIT = "Wait"
        const val TAKE_OVER = "Take_over"
        const val NOTE = "Note"
        const val CALL_API = "Call_API"
        const val INTERACT = "Interact"
    }

    /**
     * 从模型响应中解析动作
     * @param response 原始响应字符串
     * @return 解析后的动作
     */
    fun parse(response: String): ParsedAction {
        val trimmed = response.trim()

        return try {
            when {
                // Type 动作特殊处理 (文本可能包含特殊字符)
                trimmed.startsWith("do(action=\"Type\"") ||
                trimmed.startsWith("do(action=\"Type_Name\"") -> {
                    parseTypeAction(trimmed)
                }

                // do() 动作
                trimmed.startsWith("do(") -> {
                    parseDoAction(trimmed)
                }

                // finish() 动作
                trimmed.startsWith("finish(") -> {
                    parseFinishAction(trimmed)
                }

                else -> {
                    ParsedAction.Error("未知的动作格式", trimmed)
                }
            }
        } catch (e: Exception) {
            ParsedAction.Error("解析异常: ${e.message}", trimmed)
        }
    }

    /**
     * 解析 Type 动作 (特殊处理文本参数)
     */
    private fun parseTypeAction(response: String): ParsedAction {
        // 提取 action 类型
        val actionType = if (response.contains("\"Type_Name\"")) "Type_Name" else "Type"

        // 提取 text 参数
        val textStart = response.indexOf("text=")
        if (textStart == -1) {
            return ParsedAction.Error("Type 动作缺少 text 参数", response)
        }

        // 找到 text=" 后的内容
        val textValueStart = response.indexOf('"', textStart + 5)
        if (textValueStart == -1) {
            return ParsedAction.Error("Type 动作 text 参数格式错误", response)
        }

        // 找到结束引号 (考虑转义)
        var textValueEnd = textValueStart + 1
        while (textValueEnd < response.length) {
            if (response[textValueEnd] == '"' && response[textValueEnd - 1] != '\\') {
                break
            }
            textValueEnd++
        }

        val text = response.substring(textValueStart + 1, textValueEnd)
            .replace("\\\"", "\"")
            .replace("\\n", "\n")
            .replace("\\t", "\t")

        return ParsedAction.Do(
            action = actionType,
            params = mapOf("text" to text)
        )
    }

    /**
     * 解析 do() 动作
     */
    private fun parseDoAction(response: String): ParsedAction {
        // 处理转义字符
        val escaped = response
            .replace("\\n", "\\\\n")
            .replace("\\r", "\\\\r")
            .replace("\\t", "\\\\t")

        val params = mutableMapOf<String, Any>()

        // 提取 action 参数
        val actionMatch = Regex("""action\s*=\s*"([^"]+)"""").find(escaped)
        if (actionMatch == null) {
            return ParsedAction.Error("缺少 action 参数", response)
        }
        val actionType = actionMatch.groupValues[1]

        // 提取 app 参数 (Launch)
        Regex("""app\s*=\s*"([^"]+)"""").find(escaped)?.let {
            params["app"] = it.groupValues[1]
        }

        // 提取 element 参数 (Tap, Long Press, Double Tap)
        Regex("""element\s*=\s*\[\s*(\d+)\s*,\s*(\d+)\s*]""").find(escaped)?.let {
            params["element"] = listOf(it.groupValues[1].toInt(), it.groupValues[2].toInt())
        }

        // 提取 start/end 参数 (Swipe)
        Regex("""start\s*=\s*\[\s*(\d+)\s*,\s*(\d+)\s*]""").find(escaped)?.let {
            params["start"] = listOf(it.groupValues[1].toInt(), it.groupValues[2].toInt())
        }
        Regex("""end\s*=\s*\[\s*(\d+)\s*,\s*(\d+)\s*]""").find(escaped)?.let {
            params["end"] = listOf(it.groupValues[1].toInt(), it.groupValues[2].toInt())
        }

        // 提取 text 参数 (Type)
        Regex("""text\s*=\s*"([^"]+)"""").find(escaped)?.let {
            params["text"] = it.groupValues[1]
                .replace("\\\\n", "\n")
                .replace("\\\\t", "\t")
        }

        // 提取 message 参数 (敏感操作提示, Take_over)
        Regex("""message\s*=\s*"([^"]+)"""").find(escaped)?.let {
            params["message"] = it.groupValues[1]
        }

        // 提取 duration 参数 (Wait)
        Regex("""duration\s*=\s*"([^"]+)"""").find(escaped)?.let {
            params["duration"] = it.groupValues[1]
        }

        // 提取 instruction 参数 (Call_API)
        Regex("""instruction\s*=\s*"([^"]+)"""").find(escaped)?.let {
            params["instruction"] = it.groupValues[1]
        }

        return ParsedAction.Do(action = actionType, params = params)
    }

    /**
     * 解析 finish() 动作
     */
    private fun parseFinishAction(response: String): ParsedAction {
        // finish(message="xxx")
        val messageMatch = Regex("""message\s*=\s*"(.+)"""").find(response)
        val message = if (messageMatch != null) {
            // 提取引号内的内容，处理可能没有结束引号的情况
            val rawMessage = messageMatch.groupValues[1]
            // 移除可能的结尾 ) 和引号
            rawMessage.trimEnd(')', '"').trim()
        } else {
            "任务完成"
        }

        return ParsedAction.Finish(message = message)
    }

    /**
     * 从模型完整响应中提取动作部分
     * 支持多种格式:
     * 1. do(action=...) 或 finish(message=...)
     * 2. <answer>...</answer> 标签
     */
    fun extractAction(fullResponse: String): String {
        val content = fullResponse.trim()

        // 优先查找 finish(message=
        if (content.contains("finish(message=")) {
            val idx = content.indexOf("finish(message=")
            return content.substring(idx)
        }

        // 查找 do(action=
        if (content.contains("do(action=")) {
            val idx = content.indexOf("do(action=")
            return content.substring(idx)
        }

        // 查找 <answer> 标签
        if (content.contains("<answer>")) {
            val start = content.indexOf("<answer>") + 8
            val end = content.indexOf("</answer>", start)
            if (end > start) {
                return content.substring(start, end).trim()
            }
            // 没有结束标签，取到末尾
            return content.substring(start).trim()
        }

        // 无法识别格式，返回原内容
        return content
    }

    /**
     * 从模型完整响应中提取思考部分
     */
    fun extractThinking(fullResponse: String): String {
        val content = fullResponse.trim()

        // 查找 <think> 标签
        if (content.contains("<think>")) {
            val start = content.indexOf("<think>") + 7
            val end = content.indexOf("</think>", start)
            if (end > start) {
                return content.substring(start, end).trim()
            }
        }

        // 以 do(action= 或 finish(message= 分割
        val actionMarkers = listOf("do(action=", "finish(message=")
        for (marker in actionMarkers) {
            if (content.contains(marker)) {
                val idx = content.indexOf(marker)
                return content.substring(0, idx).trim()
            }
        }

        return ""
    }
}
