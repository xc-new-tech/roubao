package com.roubao.autopilot.agent

import org.json.JSONObject

/**
 * Agent 状态池 - 保存所有执行过程中的信息
 */
data class InfoPool(
    // 用户指令
    var instruction: String = "",

    // 规划相关
    var plan: String = "",
    var completedPlan: String = "",
    var progressStatus: String = "",
    var currentSubgoal: String = "",

    // 动作历史
    val actionHistory: MutableList<Action> = mutableListOf(),
    val summaryHistory: MutableList<String> = mutableListOf(),
    val actionOutcomes: MutableList<String> = mutableListOf(),  // A=成功, B=错误页面, C=无变化
    val errorDescriptions: MutableList<String> = mutableListOf(),

    // 最近一次动作
    var lastAction: Action? = null,
    var lastActionThought: String = "",
    var lastSummary: String = "",

    // 笔记
    var importantNotes: String = "",

    // 错误处理
    var errorFlagPlan: Boolean = false,
    val errToManagerThresh: Int = 2,

    // 屏幕尺寸
    var screenWidth: Int = 1080,
    var screenHeight: Int = 2400,

    // 额外知识
    var additionalKnowledge: String = "",

    // Skill 上下文（从 SkillManager 获取的相关技能信息）
    var skillContext: String = ""
)

/**
 * 动作定义
 */
data class Action(
    val type: String,           // click, swipe, type, system_button, open_app, answer
    val x: Int? = null,
    val y: Int? = null,
    val x2: Int? = null,
    val y2: Int? = null,
    val text: String? = null,
    val button: String? = null  // Back, Home, Enter
) {
    companion object {
        fun fromJson(json: String): Action? {
            return try {
                val obj = JSONObject(json.trim()
                    .replace("```json", "")
                    .replace("```", "")
                    .trim())

                val type = obj.optString("action", "")

                Action(
                    type = type,
                    x = obj.optJSONArray("coordinate")?.optInt(0),
                    y = obj.optJSONArray("coordinate")?.optInt(1),
                    x2 = obj.optJSONArray("coordinate2")?.optInt(0),
                    y2 = obj.optJSONArray("coordinate2")?.optInt(1),
                    text = obj.optString("text", null),
                    button = obj.optString("button", null)
                )
            } catch (e: Exception) {
                null
            }
        }
    }

    fun toJson(): String {
        val obj = JSONObject()
        obj.put("action", type)

        if (x != null && y != null) {
            val coord = org.json.JSONArray()
            coord.put(x)
            coord.put(y)
            obj.put("coordinate", coord)
        }
        if (x2 != null && y2 != null) {
            val coord2 = org.json.JSONArray()
            coord2.put(x2)
            coord2.put(y2)
            obj.put("coordinate2", coord2)
        }
        text?.let { obj.put("text", it) }
        button?.let { obj.put("button", it) }

        return obj.toString()
    }

    override fun toString(): String = toJson()
}
