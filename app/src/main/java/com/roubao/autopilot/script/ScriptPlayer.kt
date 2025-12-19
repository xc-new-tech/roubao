package com.roubao.autopilot.script

import android.content.Context
import android.util.Log
import com.roubao.autopilot.autoglm.ActionParser
import com.roubao.autopilot.controller.DeviceController
import com.roubao.autopilot.data.*
import com.roubao.autopilot.ui.OverlayService
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * 脚本播放器
 * 负责脚本的回放控制
 */
class ScriptPlayer(
    private val context: Context,
    private val deviceController: DeviceController
) {
    companion object {
        private const val TAG = "ScriptPlayer"
    }

    private val _playbackState = MutableStateFlow(PlaybackState())
    val playbackState: StateFlow<PlaybackState> = _playbackState.asStateFlow()

    private var playJob: Job? = null
    private val scope = CoroutineScope(Dispatchers.Main + SupervisorJob())
    private var currentParamValues: Map<String, String> = emptyMap()

    /**
     * 播放脚本
     * @param script 要播放的脚本
     * @param paramValues 参数值映射 (参数名 -> 实际值)
     */
    fun play(script: Script, paramValues: Map<String, String> = emptyMap()) {
        if (_playbackState.value.isPlaying) {
            Log.w(TAG, "Already playing, stop first")
            return
        }

        currentParamValues = paramValues
        playJob = scope.launch {
            try {
                executeScript(script)
            } catch (e: CancellationException) {
                Log.d(TAG, "Playback cancelled")
                updateState { copy(status = PlaybackStatus.STOPPED, message = "已停止") }
            } catch (e: Exception) {
                Log.e(TAG, "Playback error", e)
                updateState { copy(status = PlaybackStatus.ERROR, message = e.message ?: "未知错误") }
            } finally {
                updateState { copy(isPlaying = false) }
                OverlayService.hide(context)
                currentParamValues = emptyMap()
            }
        }
    }

    /**
     * 停止播放
     */
    fun stop() {
        playJob?.cancel()
        playJob = null
        updateState { copy(isPlaying = false, status = PlaybackStatus.STOPPED, message = "已停止") }
        OverlayService.hide(context)
    }

    /**
     * 暂停播放
     */
    fun pause() {
        if (_playbackState.value.status == PlaybackStatus.PLAYING) {
            updateState { copy(status = PlaybackStatus.PAUSED, message = "已暂停") }
        }
    }

    /**
     * 恢复播放
     */
    fun resume() {
        if (_playbackState.value.status == PlaybackStatus.PAUSED) {
            updateState { copy(status = PlaybackStatus.PLAYING, message = "播放中") }
        }
    }

    /**
     * 执行脚本
     */
    private suspend fun executeScript(script: Script) {
        val loopConfig = script.loopConfig
        val totalLoops = if (loopConfig.enabled && loopConfig.loopCount > 0) {
            loopConfig.loopCount
        } else if (loopConfig.enabled && loopConfig.loopCount == 0) {
            Int.MAX_VALUE  // 无限循环
        } else {
            1
        }

        updateState {
            copy(
                isPlaying = true,
                currentScript = script,
                currentActionIndex = 0,
                currentLoop = 1,
                status = PlaybackStatus.PLAYING,
                message = "开始播放"
            )
        }

        // 显示悬浮窗
        OverlayService.show(context, "脚本运行中") {
            stop()
        }

        var loop = 1
        while (loop <= totalLoops) {
            updateState { copy(currentLoop = loop, message = "第 $loop 轮") }

            for ((index, action) in script.actions.withIndex()) {
                // 检查是否暂停
                while (_playbackState.value.status == PlaybackStatus.PAUSED) {
                    delay(100)
                }

                // 检查是否取消
                if (!_playbackState.value.isPlaying) {
                    return
                }

                updateState {
                    copy(
                        currentActionIndex = index,
                        status = PlaybackStatus.PLAYING,
                        message = "${action.description.ifEmpty { action.actionType }} (${index + 1}/${script.actions.size})"
                    )
                }

                // 执行动作
                val success = executeAction(action)
                if (!success && loopConfig.stopOnError) {
                    updateState {
                        copy(
                            status = PlaybackStatus.ERROR,
                            message = "动作执行失败: ${action.actionType}"
                        )
                    }
                    return
                }

                // 动作后延时
                if (action.delayAfterMs > 0) {
                    updateState { copy(status = PlaybackStatus.WAITING, message = "等待 ${action.delayAfterMs}ms") }
                    delay(action.delayAfterMs)
                }
            }

            // 循环间延时
            if (loopConfig.enabled && loop < totalLoops && loopConfig.loopDelayMs > 0) {
                updateState { copy(status = PlaybackStatus.WAITING, message = "循环间隔 ${loopConfig.loopDelayMs}ms") }
                delay(loopConfig.loopDelayMs)
            }

            loop++
        }

        updateState { copy(status = PlaybackStatus.COMPLETED, message = "播放完成") }
    }

    /**
     * 执行单个动作
     */
    private suspend fun executeAction(action: ScriptAction): Boolean {
        return try {
            when (action.actionType) {
                ActionParser.Actions.TAP -> {
                    val element = getCoordinates(action.params["element"])
                    if (element != null) {
                        val (x, y) = convertToAbsolute(element)
                        deviceController.tap(x, y)
                        true
                    } else false
                }

                ActionParser.Actions.LONG_PRESS -> {
                    val element = getCoordinates(action.params["element"])
                    if (element != null) {
                        val (x, y) = convertToAbsolute(element)
                        deviceController.longPress(x, y)
                        true
                    } else false
                }

                ActionParser.Actions.DOUBLE_TAP -> {
                    val element = getCoordinates(action.params["element"])
                    if (element != null) {
                        val (x, y) = convertToAbsolute(element)
                        deviceController.doubleTap(x, y)
                        true
                    } else false
                }

                ActionParser.Actions.SWIPE -> {
                    val start = getCoordinates(action.params["start"])
                    val end = getCoordinates(action.params["end"])
                    if (start != null && end != null) {
                        val (x1, y1) = convertToAbsolute(start)
                        val (x2, y2) = convertToAbsolute(end)
                        deviceController.swipe(x1, y1, x2, y2)
                        true
                    } else false
                }

                ActionParser.Actions.TYPE, ActionParser.Actions.TYPE_NAME -> {
                    val text = action.params["text"] as? String
                    if (text != null) {
                        // 替换参数占位符
                        val substitutedText = substituteParams(text)
                        deviceController.type(substitutedText)
                        true
                    } else false
                }

                ActionParser.Actions.LAUNCH -> {
                    val app = action.params["app"] as? String
                    if (app != null) {
                        // 替换参数占位符
                        val substitutedApp = substituteParams(app)
                        deviceController.openApp(substitutedApp)
                        true
                    } else false
                }

                ActionParser.Actions.BACK -> {
                    deviceController.back()
                    true
                }

                ActionParser.Actions.HOME -> {
                    deviceController.home()
                    true
                }

                ActionParser.Actions.WAIT -> {
                    val duration = action.params["duration"] as? String ?: "1s"
                    val ms = parseDuration(duration)
                    delay(ms)
                    true
                }

                else -> {
                    Log.w(TAG, "Unknown action type: ${action.actionType}")
                    true  // 跳过未知动作，不视为错误
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Execute action failed: ${action.actionType}", e)
            false
        }
    }

    /**
     * 获取坐标列表
     */
    @Suppress("UNCHECKED_CAST")
    private fun getCoordinates(value: Any?): List<Int>? {
        return when (value) {
            is List<*> -> value.mapNotNull { (it as? Number)?.toInt() }
            else -> null
        }
    }

    /**
     * 将相对坐标 (0-999) 转换为绝对坐标
     */
    private fun convertToAbsolute(coords: List<Int>): Pair<Int, Int> {
        if (coords.size < 2) return Pair(0, 0)

        val relX = coords[0]
        val relY = coords[1]

        // 如果坐标已经是绝对坐标 (>= 1000)，直接返回
        if (relX >= 1000 || relY >= 1000) {
            return Pair(relX, relY)
        }

        // 相对坐标 (0-999) 转绝对坐标
        val (screenWidth, screenHeight) = deviceController.getScreenSize()
        val absX = (relX * screenWidth / 1000)
        val absY = (relY * screenHeight / 1000)

        return Pair(absX, absY)
    }

    /**
     * 解析时长字符串
     */
    private fun parseDuration(duration: String): Long {
        val lower = duration.lowercase().trim()
        return when {
            lower.endsWith("s") -> {
                val value = lower.dropLast(1).toDoubleOrNull() ?: 1.0
                (value * 1000).toLong()
            }
            lower.endsWith("ms") -> {
                lower.dropLast(2).toLongOrNull() ?: 1000
            }
            else -> {
                lower.toLongOrNull() ?: 1000
            }
        }
    }

    /**
     * 替换参数占位符
     * 支持格式: ${param_name} 或使用 originalValue 进行匹配替换
     */
    private fun substituteParams(text: String): String {
        var result = text

        // 方式1: 替换 ${param_name} 格式的占位符
        val placeholderRegex = Regex("""\$\{(\w+)\}""")
        result = placeholderRegex.replace(result) { match ->
            val paramName = match.groupValues[1]
            currentParamValues[paramName] ?: match.value
        }

        // 方式2: 使用 originalValue 匹配并替换
        val script = _playbackState.value.currentScript
        for ((paramName, paramValue) in currentParamValues) {
            if (paramValue.isNotEmpty()) {
                val param = script?.params?.find { it.name == paramName }
                val originalValue = param?.originalValue
                if (!originalValue.isNullOrEmpty()) {
                    result = result.replace(originalValue, paramValue)
                }
            }
        }

        return result
    }

    /**
     * 更新状态
     */
    private fun updateState(update: PlaybackState.() -> PlaybackState) {
        _playbackState.value = _playbackState.value.update()
    }

    /**
     * 更新播放状态 (供 Agent 模式使用)
     */
    fun updatePlaybackState(script: Script?, isPlaying: Boolean) {
        _playbackState.value = _playbackState.value.copy(
            isPlaying = isPlaying,
            currentScript = script,
            currentActionIndex = 0,
            currentLoop = 1,
            status = if (isPlaying) PlaybackStatus.PLAYING else PlaybackStatus.IDLE,
            message = if (isPlaying) "执行中" else ""
        )
    }

    /**
     * 更新当前动作索引 (供 Agent 模式使用)
     */
    fun updateActionIndex(index: Int) {
        _playbackState.value = _playbackState.value.copy(
            currentActionIndex = index
        )
    }

    /**
     * 释放资源
     */
    fun release() {
        stop()
        scope.cancel()
    }
}
