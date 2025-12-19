package com.roubao.autopilot.data

import org.json.JSONArray
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.*

/**
 * 脚本动作
 */
data class ScriptAction(
    val actionType: String,           // Tap, Type, Swipe, Launch, Back, Home, etc.
    val params: Map<String, Any>,     // 动作参数
    val delayAfterMs: Long = 1000,    // 执行后延时
    val description: String = ""      // 动作描述
) {
    fun toJson(): JSONObject = JSONObject().apply {
        put("actionType", actionType)
        put("delayAfterMs", delayAfterMs)
        put("description", description)
        put("params", JSONObject().apply {
            params.forEach { (key, value) ->
                when (value) {
                    is List<*> -> put(key, JSONArray().apply {
                        value.forEach { put(it) }
                    })
                    else -> put(key, value)
                }
            }
        })
    }

    companion object {
        fun fromJson(json: JSONObject): ScriptAction {
            val paramsJson = json.optJSONObject("params") ?: JSONObject()
            val params = mutableMapOf<String, Any>()
            paramsJson.keys().forEach { key ->
                val value = paramsJson.get(key)
                params[key] = when (value) {
                    is JSONArray -> {
                        val list = mutableListOf<Any>()
                        for (i in 0 until value.length()) {
                            list.add(value.get(i))
                        }
                        list
                    }
                    else -> value
                }
            }
            return ScriptAction(
                actionType = json.optString("actionType", ""),
                params = params,
                delayAfterMs = json.optLong("delayAfterMs", 1000),
                description = json.optString("description", "")
            )
        }
    }
}

/**
 * 脚本参数定义
 * 用于参数化脚本，允许在执行时动态替换值
 */
data class ScriptParam(
    val name: String,                 // 参数名 (如 "food_item")
    val label: String,                // 显示标签 (如 "要点的食物")
    val originalValue: String = "",   // 脚本中的原始值 (要被替换的值，如 "猪脚饭")
    val defaultValue: String = "",    // 默认输入值 (显示给用户的默认值)
    val type: ParamType = ParamType.TEXT,
    val description: String = ""      // 参数说明
) {
    fun toJson(): JSONObject = JSONObject().apply {
        put("name", name)
        put("label", label)
        put("originalValue", originalValue)
        put("defaultValue", defaultValue)
        put("type", type.name)
        put("description", description)
    }

    companion object {
        fun fromJson(json: JSONObject): ScriptParam = ScriptParam(
            name = json.optString("name", ""),
            label = json.optString("label", ""),
            originalValue = json.optString("originalValue", ""),
            defaultValue = json.optString("defaultValue", ""),
            type = try { ParamType.valueOf(json.optString("type", "TEXT")) } catch (e: Exception) { ParamType.TEXT },
            description = json.optString("description", "")
        )
    }
}

/**
 * 参数类型
 */
enum class ParamType {
    TEXT,       // 文本输入
    NUMBER,     // 数字
    APP_NAME    // 应用名称
}

/**
 * 循环配置
 */
data class LoopConfig(
    val enabled: Boolean = false,
    val loopCount: Int = 1,           // 0 = 无限循环
    val loopDelayMs: Long = 2000,     // 每轮循环间延时
    val stopOnError: Boolean = true   // 出错时停止
) {
    fun toJson(): JSONObject = JSONObject().apply {
        put("enabled", enabled)
        put("loopCount", loopCount)
        put("loopDelayMs", loopDelayMs)
        put("stopOnError", stopOnError)
    }

    companion object {
        fun fromJson(json: JSONObject): LoopConfig = LoopConfig(
            enabled = json.optBoolean("enabled", false),
            loopCount = json.optInt("loopCount", 1),
            loopDelayMs = json.optLong("loopDelayMs", 2000),
            stopOnError = json.optBoolean("stopOnError", true)
        )
    }
}

/**
 * 脚本实体
 */
data class Script(
    val id: String = UUID.randomUUID().toString(),
    val name: String,
    val description: String = "",
    val createdAt: Long = System.currentTimeMillis(),
    val updatedAt: Long = System.currentTimeMillis(),
    val actions: List<ScriptAction> = emptyList(),
    val sourceRecordId: String? = null,  // 来源执行记录 ID
    val loopConfig: LoopConfig = LoopConfig(),
    val params: List<ScriptParam> = emptyList()  // 参数定义
) {
    val formattedCreatedAt: String
        get() {
            val sdf = SimpleDateFormat("MM-dd HH:mm", Locale.getDefault())
            return sdf.format(Date(createdAt))
        }

    val actionCount: Int get() = actions.size

    fun toJson(): JSONObject = JSONObject().apply {
        put("id", id)
        put("name", name)
        put("description", description)
        put("createdAt", createdAt)
        put("updatedAt", updatedAt)
        put("sourceRecordId", sourceRecordId ?: "")
        put("loopConfig", loopConfig.toJson())
        put("actions", JSONArray().apply {
            actions.forEach { put(it.toJson()) }
        })
        put("params", JSONArray().apply {
            params.forEach { put(it.toJson()) }
        })
    }

    companion object {
        fun fromJson(json: JSONObject): Script {
            val actionsArray = json.optJSONArray("actions") ?: JSONArray()
            val actions = mutableListOf<ScriptAction>()
            for (i in 0 until actionsArray.length()) {
                actions.add(ScriptAction.fromJson(actionsArray.getJSONObject(i)))
            }
            val paramsArray = json.optJSONArray("params") ?: JSONArray()
            val params = mutableListOf<ScriptParam>()
            for (i in 0 until paramsArray.length()) {
                params.add(ScriptParam.fromJson(paramsArray.getJSONObject(i)))
            }
            return Script(
                id = json.optString("id", UUID.randomUUID().toString()),
                name = json.optString("name", ""),
                description = json.optString("description", ""),
                createdAt = json.optLong("createdAt", System.currentTimeMillis()),
                updatedAt = json.optLong("updatedAt", System.currentTimeMillis()),
                sourceRecordId = json.optString("sourceRecordId", "").ifEmpty { null },
                loopConfig = json.optJSONObject("loopConfig")?.let { LoopConfig.fromJson(it) } ?: LoopConfig(),
                actions = actions,
                params = params
            )
        }
    }
}

/**
 * 回放状态
 */
data class PlaybackState(
    val isPlaying: Boolean = false,
    val currentScript: Script? = null,
    val currentActionIndex: Int = 0,
    val currentLoop: Int = 1,
    val status: PlaybackStatus = PlaybackStatus.IDLE,
    val message: String = ""
)

/**
 * 回放状态枚举
 */
enum class PlaybackStatus {
    IDLE,           // 空闲
    PLAYING,        // 播放中
    PAUSED,         // 暂停
    WAITING,        // 等待延时
    COMPLETED,      // 完成
    ERROR,          // 出错
    STOPPED         // 已停止
}
