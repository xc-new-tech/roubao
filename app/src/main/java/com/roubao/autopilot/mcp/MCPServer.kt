package com.roubao.autopilot.mcp

import android.content.Context
import android.util.Log
import fi.iki.elonen.NanoHTTPD
import kotlinx.coroutines.*
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException

/**
 * MCP (Model Context Protocol) Server
 * 为外部应用提供肉包自动化能力的接口
 */
class MCPServer(
    private val context: Context,
    private val port: Int = 8765
) : NanoHTTPD(port) {

    companion object {
        private const val TAG = "MCPServer"
        const val DEFAULT_PORT = 8765
    }

    // 工具执行回调
    var onExecuteInstruction: ((String) -> Unit)? = null
    var onPlayScript: ((String, Map<String, String>) -> Unit)? = null
    var onStopExecution: (() -> Unit)? = null
    var getScripts: (() -> List<ScriptInfo>)? = null
    var getExecutionStatus: (() -> ExecutionStatusInfo)? = null

    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    data class ScriptInfo(
        val id: String,
        val name: String,
        val description: String,
        val actionCount: Int
    )

    data class ExecutionStatusInfo(
        val isRunning: Boolean,
        val currentTask: String?,
        val progress: Int?,
        val message: String?
    )

    override fun serve(session: IHTTPSession): Response {
        val uri = session.uri
        val method = session.method

        Log.d(TAG, "Request: $method $uri")

        // CORS headers
        val corsHeaders = mutableMapOf(
            "Access-Control-Allow-Origin" to "*",
            "Access-Control-Allow-Methods" to "GET, POST, OPTIONS",
            "Access-Control-Allow-Headers" to "Content-Type"
        )

        // Handle preflight
        if (method == Method.OPTIONS) {
            return newFixedLengthResponse(Response.Status.OK, "text/plain", "").apply {
                corsHeaders.forEach { (k, v) -> addHeader(k, v) }
            }
        }

        val response = when {
            // MCP 协议端点
            uri == "/" && method == Method.GET -> handleInfo()
            uri == "/mcp" && method == Method.POST -> handleMCPRequest(session)

            // 简化的 REST API (兼容非 MCP 客户端)
            uri == "/api/tools" && method == Method.GET -> handleListTools()
            uri == "/api/execute" && method == Method.POST -> handleExecute(session)
            uri == "/api/scripts" && method == Method.GET -> handleListScripts()
            uri == "/api/scripts/play" && method == Method.POST -> handlePlayScript(session)
            uri == "/api/status" && method == Method.GET -> handleStatus()
            uri == "/api/stop" && method == Method.POST -> handleStop()

            else -> newFixedLengthResponse(
                Response.Status.NOT_FOUND,
                "application/json",
                """{"error": "Not found"}"""
            )
        }

        corsHeaders.forEach { (k, v) -> response.addHeader(k, v) }
        return response
    }

    /**
     * 服务器信息
     */
    private fun handleInfo(): Response {
        val info = JSONObject().apply {
            put("name", "roubao-mcp-server")
            put("version", "1.0.0")
            put("description", "肉包 AI 手机自动化助手 MCP 服务")
            put("protocol", "mcp")
            put("endpoints", JSONObject().apply {
                put("mcp", "/mcp")
                put("tools", "/api/tools")
                put("execute", "/api/execute")
                put("scripts", "/api/scripts")
                put("status", "/api/status")
            })
        }
        return jsonResponse(info)
    }

    /**
     * 处理 MCP JSON-RPC 请求
     */
    private fun handleMCPRequest(session: IHTTPSession): Response {
        val body = getRequestBody(session)

        return try {
            val request = JSONObject(body)
            val method = request.optString("method")
            val id = request.opt("id")
            val params = request.optJSONObject("params") ?: JSONObject()

            val result = when (method) {
                "initialize" -> handleInitialize()
                "tools/list" -> handleToolsList()
                "tools/call" -> handleToolsCall(params)
                "resources/list" -> handleResourcesList()
                "prompts/list" -> handlePromptsList()
                else -> throw MCPException(-32601, "Method not found: $method")
            }

            jsonRpcResponse(id, result)
        } catch (e: MCPException) {
            jsonRpcError(null, e.code, e.message ?: "Unknown error")
        } catch (e: Exception) {
            Log.e(TAG, "MCP request error", e)
            jsonRpcError(null, -32603, e.message ?: "Internal error")
        }
    }

    /**
     * MCP 初始化
     */
    private fun handleInitialize(): JSONObject {
        return JSONObject().apply {
            put("protocolVersion", "2024-11-05")
            put("serverInfo", JSONObject().apply {
                put("name", "roubao")
                put("version", "1.0.0")
            })
            put("capabilities", JSONObject().apply {
                put("tools", JSONObject())
                put("resources", JSONObject())
            })
        }
    }

    /**
     * 列出可用工具
     */
    private fun handleToolsList(): JSONObject {
        val tools = JSONArray().apply {
            // 执行指令工具
            put(JSONObject().apply {
                put("name", "execute_instruction")
                put("description", "执行自然语言指令，让肉包自动完成手机操作任务")
                put("inputSchema", JSONObject().apply {
                    put("type", "object")
                    put("properties", JSONObject().apply {
                        put("instruction", JSONObject().apply {
                            put("type", "string")
                            put("description", "要执行的指令，如「帮我打开微信」「在美团点一份外卖」")
                        })
                    })
                    put("required", JSONArray().put("instruction"))
                })
            })

            // 播放脚本工具
            put(JSONObject().apply {
                put("name", "play_script")
                put("description", "播放已保存的自动化脚本")
                put("inputSchema", JSONObject().apply {
                    put("type", "object")
                    put("properties", JSONObject().apply {
                        put("script_id", JSONObject().apply {
                            put("type", "string")
                            put("description", "脚本 ID")
                        })
                        put("params", JSONObject().apply {
                            put("type", "object")
                            put("description", "脚本参数（可选）")
                            put("additionalProperties", JSONObject().put("type", "string"))
                        })
                    })
                    put("required", JSONArray().put("script_id"))
                })
            })

            // 列出脚本工具
            put(JSONObject().apply {
                put("name", "list_scripts")
                put("description", "列出所有可用的自动化脚本")
                put("inputSchema", JSONObject().apply {
                    put("type", "object")
                    put("properties", JSONObject())
                })
            })

            // 获取状态工具
            put(JSONObject().apply {
                put("name", "get_status")
                put("description", "获取当前执行状态")
                put("inputSchema", JSONObject().apply {
                    put("type", "object")
                    put("properties", JSONObject())
                })
            })

            // 停止执行工具
            put(JSONObject().apply {
                put("name", "stop_execution")
                put("description", "停止当前正在执行的任务")
                put("inputSchema", JSONObject().apply {
                    put("type", "object")
                    put("properties", JSONObject())
                })
            })
        }

        return JSONObject().put("tools", tools)
    }

    /**
     * 调用工具
     */
    private fun handleToolsCall(params: JSONObject): JSONObject {
        val toolName = params.optString("name")
        val arguments = params.optJSONObject("arguments") ?: JSONObject()

        val result = when (toolName) {
            "execute_instruction" -> {
                val instruction = arguments.optString("instruction")
                if (instruction.isBlank()) {
                    throw MCPException(-32602, "Missing required parameter: instruction")
                }
                onExecuteInstruction?.invoke(instruction)
                JSONObject().apply {
                    put("success", true)
                    put("message", "指令已开始执行: $instruction")
                }
            }

            "play_script" -> {
                val scriptId = arguments.optString("script_id")
                if (scriptId.isBlank()) {
                    throw MCPException(-32602, "Missing required parameter: script_id")
                }
                val scriptParams = mutableMapOf<String, String>()
                arguments.optJSONObject("params")?.let { p ->
                    p.keys().forEach { key ->
                        scriptParams[key] = p.optString(key)
                    }
                }
                onPlayScript?.invoke(scriptId, scriptParams)
                JSONObject().apply {
                    put("success", true)
                    put("message", "脚本已开始播放")
                }
            }

            "list_scripts" -> {
                val scripts = getScripts?.invoke() ?: emptyList()
                JSONObject().apply {
                    put("scripts", JSONArray().apply {
                        scripts.forEach { script ->
                            put(JSONObject().apply {
                                put("id", script.id)
                                put("name", script.name)
                                put("description", script.description)
                                put("action_count", script.actionCount)
                            })
                        }
                    })
                }
            }

            "get_status" -> {
                val status = getExecutionStatus?.invoke() ?: ExecutionStatusInfo(false, null, null, null)
                JSONObject().apply {
                    put("is_running", status.isRunning)
                    put("current_task", status.currentTask)
                    put("progress", status.progress)
                    put("message", status.message)
                }
            }

            "stop_execution" -> {
                onStopExecution?.invoke()
                JSONObject().apply {
                    put("success", true)
                    put("message", "已发送停止信号")
                }
            }

            else -> throw MCPException(-32602, "Unknown tool: $toolName")
        }

        return JSONObject().apply {
            put("content", JSONArray().put(JSONObject().apply {
                put("type", "text")
                put("text", result.toString(2))
            }))
        }
    }

    /**
     * 资源列表（暂无）
     */
    private fun handleResourcesList(): JSONObject {
        return JSONObject().put("resources", JSONArray())
    }

    /**
     * 提示列表（暂无）
     */
    private fun handlePromptsList(): JSONObject {
        return JSONObject().put("prompts", JSONArray())
    }

    // ========== REST API 处理 ==========

    private fun handleListTools(): Response {
        return jsonResponse(handleToolsList())
    }

    private fun handleExecute(session: IHTTPSession): Response {
        val body = getRequestBody(session)
        return try {
            val json = JSONObject(body)
            val instruction = json.optString("instruction")
            if (instruction.isBlank()) {
                return errorResponse("Missing instruction")
            }
            onExecuteInstruction?.invoke(instruction)
            jsonResponse(JSONObject().apply {
                put("success", true)
                put("message", "指令已开始执行")
            })
        } catch (e: Exception) {
            errorResponse(e.message ?: "Error")
        }
    }

    private fun handleListScripts(): Response {
        val scripts = getScripts?.invoke() ?: emptyList()
        return jsonResponse(JSONObject().apply {
            put("scripts", JSONArray().apply {
                scripts.forEach { script ->
                    put(JSONObject().apply {
                        put("id", script.id)
                        put("name", script.name)
                        put("description", script.description)
                        put("action_count", script.actionCount)
                    })
                }
            })
        })
    }

    private fun handlePlayScript(session: IHTTPSession): Response {
        val body = getRequestBody(session)
        return try {
            val json = JSONObject(body)
            val scriptId = json.optString("script_id")
            if (scriptId.isBlank()) {
                return errorResponse("Missing script_id")
            }
            val params = mutableMapOf<String, String>()
            json.optJSONObject("params")?.let { p ->
                p.keys().forEach { key ->
                    params[key] = p.optString(key)
                }
            }
            onPlayScript?.invoke(scriptId, params)
            jsonResponse(JSONObject().apply {
                put("success", true)
                put("message", "脚本已开始播放")
            })
        } catch (e: Exception) {
            errorResponse(e.message ?: "Error")
        }
    }

    private fun handleStatus(): Response {
        val status = getExecutionStatus?.invoke() ?: ExecutionStatusInfo(false, null, null, null)
        return jsonResponse(JSONObject().apply {
            put("is_running", status.isRunning)
            put("current_task", status.currentTask)
            put("progress", status.progress)
            put("message", status.message)
        })
    }

    private fun handleStop(): Response {
        onStopExecution?.invoke()
        return jsonResponse(JSONObject().apply {
            put("success", true)
            put("message", "已发送停止信号")
        })
    }

    // ========== 辅助方法 ==========

    private fun getRequestBody(session: IHTTPSession): String {
        val contentLength = session.headers["content-length"]?.toIntOrNull() ?: 0
        if (contentLength == 0) return ""

        val buffer = ByteArray(contentLength)
        session.inputStream.read(buffer, 0, contentLength)
        return String(buffer, Charsets.UTF_8)
    }

    private fun jsonResponse(json: JSONObject): Response {
        return newFixedLengthResponse(
            Response.Status.OK,
            "application/json",
            json.toString(2)
        )
    }

    private fun errorResponse(message: String): Response {
        return newFixedLengthResponse(
            Response.Status.BAD_REQUEST,
            "application/json",
            JSONObject().apply {
                put("success", false)
                put("error", message)
            }.toString()
        )
    }

    private fun jsonRpcResponse(id: Any?, result: JSONObject): Response {
        val response = JSONObject().apply {
            put("jsonrpc", "2.0")
            put("id", id)
            put("result", result)
        }
        return jsonResponse(response)
    }

    private fun jsonRpcError(id: Any?, code: Int, message: String): Response {
        val response = JSONObject().apply {
            put("jsonrpc", "2.0")
            put("id", id)
            put("error", JSONObject().apply {
                put("code", code)
                put("message", message)
            })
        }
        return jsonResponse(response)
    }

    fun startServer(): Boolean {
        return try {
            start(SOCKET_READ_TIMEOUT, false)
            Log.i(TAG, "MCP Server started on port $port")
            true
        } catch (e: IOException) {
            Log.e(TAG, "Failed to start MCP Server", e)
            false
        }
    }

    fun stopServer() {
        stop()
        scope.cancel()
        Log.i(TAG, "MCP Server stopped")
    }

    class MCPException(val code: Int, message: String) : Exception(message)
}
