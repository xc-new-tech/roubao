# 肉包 MCP 服务接口文档

## 概述

肉包 (Roubao) 提供 MCP (Model Context Protocol) 服务，允许外部应用调用其手机自动化能力。支持两种调用方式：

1. **MCP 协议** - 标准 JSON-RPC 2.0，适合 AI Agent 集成
2. **REST API** - 简化的 HTTP 接口，适合普通应用调用

## 快速开始

### 1. 启用 MCP 服务

在肉包 App 中：设置 → 开发者 → 开启 MCP 服务

### 2. 连接信息

- **地址**: `http://<手机IP>:8765`
- **默认端口**: 8765
- **协议**: HTTP (非 HTTPS)

> 确保调用方与手机在同一局域网内

---

## MCP 协议

### 端点

```
POST http://<手机IP>:8765/mcp
Content-Type: application/json
```

### 初始化连接

```json
{
  "jsonrpc": "2.0",
  "id": 1,
  "method": "initialize",
  "params": {}
}
```

**响应:**

```json
{
  "jsonrpc": "2.0",
  "id": 1,
  "result": {
    "protocolVersion": "2024-11-05",
    "serverInfo": {
      "name": "roubao",
      "version": "1.0.0"
    },
    "capabilities": {
      "tools": {},
      "resources": {}
    }
  }
}
```

### 列出可用工具

```json
{
  "jsonrpc": "2.0",
  "id": 2,
  "method": "tools/list",
  "params": {}
}
```

### 调用工具

```json
{
  "jsonrpc": "2.0",
  "id": 3,
  "method": "tools/call",
  "params": {
    "name": "execute_instruction",
    "arguments": {
      "instruction": "打开微信"
    }
  }
}
```

---

## 可用工具

### 1. execute_instruction

执行自然语言指令，让肉包自动完成手机操作任务。

**参数:**

| 参数 | 类型 | 必填 | 说明 |
|------|------|------|------|
| instruction | string | 是 | 要执行的指令 |

**示例:**

```json
{
  "jsonrpc": "2.0",
  "id": 1,
  "method": "tools/call",
  "params": {
    "name": "execute_instruction",
    "arguments": {
      "instruction": "帮我打开微信，给张三发送消息：明天见"
    }
  }
}
```

**响应:**

```json
{
  "jsonrpc": "2.0",
  "id": 1,
  "result": {
    "content": [{
      "type": "text",
      "text": "{\"success\": true, \"message\": \"指令已开始执行: 帮我打开微信，给张三发送消息：明天见\"}"
    }]
  }
}
```

### 2. play_script

播放已保存的自动化脚本。

**参数:**

| 参数 | 类型 | 必填 | 说明 |
|------|------|------|------|
| script_id | string | 是 | 脚本 ID |
| params | object | 否 | 脚本参数 (键值对) |

**示例:**

```json
{
  "jsonrpc": "2.0",
  "id": 1,
  "method": "tools/call",
  "params": {
    "name": "play_script",
    "arguments": {
      "script_id": "abc123",
      "params": {
        "recipient": "张三",
        "message": "你好"
      }
    }
  }
}
```

### 3. list_scripts

列出所有可用的自动化脚本。

**参数:** 无

**示例:**

```json
{
  "jsonrpc": "2.0",
  "id": 1,
  "method": "tools/call",
  "params": {
    "name": "list_scripts",
    "arguments": {}
  }
}
```

**响应:**

```json
{
  "jsonrpc": "2.0",
  "id": 1,
  "result": {
    "content": [{
      "type": "text",
      "text": "{\"scripts\": [{\"id\": \"abc123\", \"name\": \"发送微信消息\", \"description\": \"自动发送微信消息\", \"action_count\": 5}]}"
    }]
  }
}
```

### 4. get_status

获取当前执行状态。

**参数:** 无

**响应:**

```json
{
  "jsonrpc": "2.0",
  "id": 1,
  "result": {
    "content": [{
      "type": "text",
      "text": "{\"is_running\": true, \"current_task\": \"打开微信\", \"progress\": null, \"message\": null}"
    }]
  }
}
```

### 5. stop_execution

停止当前正在执行的任务。

**参数:** 无

**响应:**

```json
{
  "jsonrpc": "2.0",
  "id": 1,
  "result": {
    "content": [{
      "type": "text",
      "text": "{\"success\": true, \"message\": \"已发送停止信号\"}"
    }]
  }
}
```

---

## REST API

为方便非 MCP 客户端调用，提供简化的 REST API。

### 服务信息

```
GET http://<手机IP>:8765/
```

**响应:**

```json
{
  "name": "roubao-mcp-server",
  "version": "1.0.0",
  "description": "肉包 AI 手机自动化助手 MCP 服务",
  "protocol": "mcp",
  "endpoints": {
    "mcp": "/mcp",
    "tools": "/api/tools",
    "execute": "/api/execute",
    "scripts": "/api/scripts",
    "status": "/api/status"
  }
}
```

### 执行指令

```
POST http://<手机IP>:8765/api/execute
Content-Type: application/json

{
  "instruction": "打开微信"
}
```

### 列出脚本

```
GET http://<手机IP>:8765/api/scripts
```

### 播放脚本

```
POST http://<手机IP>:8765/api/scripts/play
Content-Type: application/json

{
  "script_id": "abc123",
  "params": {
    "key": "value"
  }
}
```

### 获取状态

```
GET http://<手机IP>:8765/api/status
```

### 停止执行

```
POST http://<手机IP>:8765/api/stop
```

---

## 代码示例

### Python

```python
import requests
import json

BASE_URL = "http://192.168.1.100:8765"

# 方式1: 使用 REST API
def execute_instruction(instruction):
    response = requests.post(
        f"{BASE_URL}/api/execute",
        json={"instruction": instruction}
    )
    return response.json()

# 方式2: 使用 MCP 协议
def mcp_call(method, params=None):
    response = requests.post(
        f"{BASE_URL}/mcp",
        json={
            "jsonrpc": "2.0",
            "id": 1,
            "method": method,
            "params": params or {}
        }
    )
    return response.json()

# 初始化 MCP 连接
mcp_call("initialize")

# 执行指令
mcp_call("tools/call", {
    "name": "execute_instruction",
    "arguments": {"instruction": "打开微信"}
})

# 获取状态
status = requests.get(f"{BASE_URL}/api/status").json()
print(f"正在执行: {status['is_running']}")
```

### Kotlin / Android

```kotlin
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject

class RoubaoMCPClient(private val baseUrl: String = "http://localhost:8765") {
    private val client = OkHttpClient()
    private val jsonMediaType = "application/json".toMediaType()

    // REST API 方式
    fun executeInstruction(instruction: String): JSONObject {
        val body = JSONObject().apply {
            put("instruction", instruction)
        }.toString().toRequestBody(jsonMediaType)

        val request = Request.Builder()
            .url("$baseUrl/api/execute")
            .post(body)
            .build()

        client.newCall(request).execute().use { response ->
            return JSONObject(response.body?.string() ?: "{}")
        }
    }

    // MCP 协议方式
    fun mcpCall(method: String, params: JSONObject = JSONObject()): JSONObject {
        val body = JSONObject().apply {
            put("jsonrpc", "2.0")
            put("id", 1)
            put("method", method)
            put("params", params)
        }.toString().toRequestBody(jsonMediaType)

        val request = Request.Builder()
            .url("$baseUrl/mcp")
            .post(body)
            .build()

        client.newCall(request).execute().use { response ->
            return JSONObject(response.body?.string() ?: "{}")
        }
    }

    fun getStatus(): JSONObject {
        val request = Request.Builder()
            .url("$baseUrl/api/status")
            .get()
            .build()

        client.newCall(request).execute().use { response ->
            return JSONObject(response.body?.string() ?: "{}")
        }
    }

    fun stop() {
        val request = Request.Builder()
            .url("$baseUrl/api/stop")
            .post("".toRequestBody())
            .build()

        client.newCall(request).execute()
    }
}

// 使用示例
val mcpClient = RoubaoMCPClient("http://192.168.1.100:8765")
mcpClient.executeInstruction("打开微信，发送消息给张三")
```

### JavaScript / Node.js

```javascript
const BASE_URL = 'http://192.168.1.100:8765';

// REST API 方式
async function executeInstruction(instruction) {
  const response = await fetch(`${BASE_URL}/api/execute`, {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ instruction })
  });
  return response.json();
}

// MCP 协议方式
async function mcpCall(method, params = {}) {
  const response = await fetch(`${BASE_URL}/mcp`, {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({
      jsonrpc: '2.0',
      id: 1,
      method,
      params
    })
  });
  return response.json();
}

// 使用示例
(async () => {
  // 初始化
  await mcpCall('initialize');

  // 执行指令
  await mcpCall('tools/call', {
    name: 'execute_instruction',
    arguments: { instruction: '打开微信' }
  });

  // 获取状态
  const status = await fetch(`${BASE_URL}/api/status`).then(r => r.json());
  console.log('执行中:', status.is_running);
})();
```

---

## 错误处理

### MCP 协议错误

```json
{
  "jsonrpc": "2.0",
  "id": 1,
  "error": {
    "code": -32602,
    "message": "Missing required parameter: instruction"
  }
}
```

**错误码:**

| 代码 | 说明 |
|------|------|
| -32601 | 方法不存在 |
| -32602 | 参数错误 |
| -32603 | 内部错误 |

### REST API 错误

```json
{
  "success": false,
  "error": "Missing instruction"
}
```

---

## 注意事项

1. **网络要求**: 调用方需与手机在同一局域网
2. **权限**: 肉包 App 需要已授权 Shizuku 权限
3. **异步执行**: `execute_instruction` 和 `play_script` 是异步的，调用后立即返回，使用 `get_status` 轮询执行状态
4. **敏感操作**: 涉及支付、密码等敏感页面时，肉包会自动停止执行
5. **并发限制**: 同一时间只能执行一个任务，新任务会覆盖正在执行的任务

---

## 常见问题

**Q: 如何获取手机 IP 地址？**

A: 设置 → WLAN → 点击已连接的网络 → 查看 IP 地址

**Q: 连接失败怎么办？**

1. 确认 MCP 服务已开启
2. 确认手机和调用方在同一网络
3. 检查防火墙设置
4. 尝试 ping 手机 IP

**Q: 如何知道任务是否完成？**

A: 使用 `get_status` 接口轮询，当 `is_running` 为 `false` 时表示执行完成

---

## 更新日志

- **v1.0.0** (2024-12): 初始版本
  - 支持 MCP 协议和 REST API
  - 5 个核心工具: execute_instruction, play_script, list_scripts, get_status, stop_execution
