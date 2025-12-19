package com.roubao.autopilot.data

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import java.io.File

/**
 * 脚本仓库
 * 负责脚本的持久化存储
 */
class ScriptRepository(private val context: Context) {

    companion object {
        private const val MAX_SCRIPTS = 50  // 最多保留 50 个脚本
    }

    private val scriptsFile: File
        get() = File(context.filesDir, "scripts.json")

    /**
     * 获取所有脚本
     */
    suspend fun getAllScripts(): List<Script> = withContext(Dispatchers.IO) {
        try {
            if (!scriptsFile.exists()) return@withContext emptyList()
            val json = scriptsFile.readText()
            val array = JSONArray(json)
            val scripts = mutableListOf<Script>()
            for (i in 0 until array.length()) {
                scripts.add(Script.fromJson(array.getJSONObject(i)))
            }
            scripts.sortedByDescending { it.updatedAt }
        } catch (e: Exception) {
            e.printStackTrace()
            emptyList()
        }
    }

    /**
     * 获取单个脚本
     */
    suspend fun getScript(id: String): Script? = withContext(Dispatchers.IO) {
        getAllScripts().find { it.id == id }
    }

    /**
     * 保存脚本
     */
    suspend fun saveScript(script: Script) = withContext(Dispatchers.IO) {
        try {
            val scripts = getAllScripts().toMutableList()
            val existingIndex = scripts.indexOfFirst { it.id == script.id }
            if (existingIndex >= 0) {
                // 更新时保留 createdAt，更新 updatedAt
                scripts[existingIndex] = script.copy(updatedAt = System.currentTimeMillis())
            } else {
                scripts.add(0, script)
            }
            // 只保留最近的脚本
            val trimmedScripts = scripts.take(MAX_SCRIPTS)
            val array = JSONArray().apply {
                trimmedScripts.forEach { put(it.toJson()) }
            }
            scriptsFile.writeText(array.toString())
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    /**
     * 删除脚本
     */
    suspend fun deleteScript(id: String) = withContext(Dispatchers.IO) {
        try {
            val scripts = getAllScripts().filter { it.id != id }
            val array = JSONArray().apply {
                scripts.forEach { put(it.toJson()) }
            }
            scriptsFile.writeText(array.toString())
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    /**
     * 清空所有脚本
     */
    suspend fun clearAll() = withContext(Dispatchers.IO) {
        try {
            scriptsFile.delete()
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    /**
     * 检查名称是否重复
     */
    suspend fun isNameExists(name: String, excludeId: String? = null): Boolean = withContext(Dispatchers.IO) {
        getAllScripts().any { it.name == name && it.id != excludeId }
    }
}
