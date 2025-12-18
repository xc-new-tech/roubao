# Open-AutoGLM 整合到 Roubao 项目计划

## 项目背景

Open-AutoGLM 是基于智谱 AI AutoGLM 模型的 Python 实现，效果较好。需要将其核心逻辑整合到 Roubao (Kotlin) 项目中。

## 核心差异分析

| 维度 | Roubao (现有) | Open-AutoGLM | 整合方案 |
|------|--------------|--------------|---------|
| 语言 | Kotlin | Python | 迁移核心逻辑到 Kotlin |
| 设备控制 | Shizuku | ADB | 保留 Shizuku (更好) |
| 坐标系统 | 绝对坐标 | 相对坐标 (0-1000) | **采用相对坐标** |
| Agent循环 | 三层分离 | 单循环+流式 | **简化为单循环** |
| Prompt设计 | 多Agent提示词 | 统一系统提示词 | **采用统一提示词** |
| 动作解析 | JSON | `do(action=...)` 函数式 | **采用函数式语法** |
| 上下文管理 | ConversationMemory | 消息列表+图片移除 | **采用新方案** |

## 整合任务清单

### 阶段一: 核心 Agent 重构 ✅ 已完成

- [x] 1. 创建 `AutoGLMAgent.kt` - 新的单循环 Agent 实现
  - 移植 `phone_agent/agent.py` 的 PhoneAgent 逻辑
  - 简化为单一 Agent 循环 (去除 Manager/Executor/Reflector 分离)
  - 支持步骤回调显示思考过程
  - 路径: `app/src/main/java/com/roubao/autopilot/autoglm/AutoGLMAgent.kt`

- [x] 2. 创建 `ActionParser.kt` - 函数式动作解析器
  - 解析 `do(action="Tap", element=[x,y])` 格式
  - 解析 `finish(message="完成")` 格式
  - 安全解析 (使用正则表达式，不使用 eval)
  - 路径: `app/src/main/java/com/roubao/autopilot/autoglm/ActionParser.kt`

- [x] 3. 创建 `MessageBuilder.kt` - 消息构建器
  - 构建系统提示词 (包含 18 条规则)
  - 构建用户消息 (任务 + 截图 + 屏幕信息)
  - 支持从历史消息中移除图片节省 Token
  - 路径: `app/src/main/java/com/roubao/autopilot/autoglm/MessageBuilder.kt`

### 阶段二: 坐标系统适配 ✅ 已完成 (合并到阶段一)

- [x] 4. 相对坐标转换已在 `AutoGLMAgent.kt` 中实现
  - `convertRelativeToAbsolute()` 方法
  - 模型输出 0-999 相对坐标 → 屏幕绝对坐标

### 阶段三: Prompt 移植 ✅ 已完成

- [x] 5. AutoGLM 系统提示词已在 `MessageBuilder.kt` 中实现
  - `getSystemPrompt()` 方法
  - 包含 18 条操作规则
  - 支持所有 13 种动作类型定义

- [x] 6. 创建 `app_packages.json` 和 `AppPackages.kt`
  - 移植 80+ 应用包名映射到 `assets/app_packages.json`
  - 创建 `AppPackages.kt` 工具类支持智能匹配
  - 支持 13 个分类: social, ecommerce, video, music, etc.

### 阶段四: VLM 客户端增强 ✅ 已完成

- [x] 7. 增强 `VLMClient.kt` - 支持流式输出
  - 添加 `StreamCallback` 回调接口
  - 添加 `StreamResponse` 响应结构
  - 添加 `predictWithContextStream()` 流式方法
  - 实时检测 `do(action=` 和 `finish(message=` 标记
  - 分离思考过程和动作指令
  - 记录性能指标 (TTFT, totalTime)

- [x] 8. 更新 `AutoGLMAgent.kt` - 集成流式输出
  - 添加 `useStreaming` 配置选项
  - 更新 `StepCallback` 接口添加 `onThinkingChunk()` 和 `onPerformanceMetrics()`
  - 支持流式/非流式两种模式切换

### 阶段五: UI 集成 ✅ 已完成

- [x] 9. 更新 `OverlayService.kt` - 显示思考过程
  - 实时显示 AI 思考内容
  - 动作执行状态展示

- [x] 10. 添加模式切换
  - 设置页面添加 "使用 AutoGLM 模式" 开关
  - 保留原有 Agent 作为备选

### 阶段六: 测试与优化

- [ ] 11. 集成测试
  - 测试常见场景 (打开应用、搜索、点击等)
  - 对比原有 Agent 效果

### 悬浮窗简化任务 ✅ 已完成

- [x] 将复杂悬浮窗改为简洁的圆形按钮
  - 移除状态文字、思考内容等复杂UI
  - 创建圆形按钮 (52dp)
  - 保留七彩渐变动画
  - 点击停止任务
  - 保持拖动功能

**修改文件:**
- `app/src/main/java/com/roubao/autopilot/ui/OverlayService.kt`

**变更:**
- 移除 textView, thinkingView, thinkingContainer, divider, cancelButton 等复杂组件
- 创建 52dp 圆形按钮，使用 GradientDrawable.OVAL
- 保留七彩渐变流动动画
- 保留拖动功能
- 简化模式切换：
  - 正常模式: 白色 ⏹ 图标 (点击停止)
  - 人机协作模式: 绿色 ▶ 图标 (点击继续)
  - 确认模式: 黄色 ✓ 图标 (点击确认)
- 保持原有 API 兼容性 (show, hide, update 等静态方法)

### AccessibilityService 集成 ✅ 已完成

- [x] 创建无障碍服务配置 XML
- [x] 创建 AutoPilotAccessibilityService.kt
- [x] 更新 AndroidManifest.xml 注册服务
- [x] 修改 DeviceController 集成 A11y 操作
- [x] 添加无障碍权限检查和引导 UI

**新增文件:**
```
app/src/main/res/xml/accessibility_service_config.xml
app/src/main/java/com/roubao/autopilot/accessibility/AutoPilotAccessibilityService.kt
```

**修改文件:**
- `app/src/main/AndroidManifest.xml` - 注册无障碍服务
- `app/src/main/res/values/strings.xml` - 添加无障碍服务描述
- `app/src/main/java/com/roubao/autopilot/controller/DeviceController.kt` - 集成 A11y 操作
- `app/src/main/java/com/roubao/autopilot/ui/screens/SettingsScreen.kt` - 添加无障碍开关UI

**功能:**
1. **手势操作**: tap, longPress, doubleTap, swipe - 优先使用 A11y，降级 Shizuku
2. **文本输入**: type() - 直接设置文本，秒级完成
3. **全局操作**: back, home - 使用 A11y performGlobalAction
4. **UI 树**: getUITreeDescription() - 获取界面元素描述
5. **元素操作**: clickByText(), typeToFirstEditable() - 通过文本定位元素

**用户操作:**
设置 → 执行设置 → 无障碍服务 → 点击跳转到系统设置开启

---

## 关键代码参考

### 动作格式 (Open-AutoGLM)

```python
# 支持的动作
do(action="Launch", app="微信")
do(action="Tap", element=[500, 100])
do(action="Type", text="搜索词")
do(action="Swipe", start=[100, 500], end=[100, 200])
do(action="Back")
do(action="Home")
do(action="Long_press", element=[500, 500])
do(action="Double_tap", element=[500, 500])
do(action="Wait", duration="2 seconds")
do(action="Take_over", message="请手动登录")
finish(message="任务完成")
```

### 坐标转换

```kotlin
// 相对坐标 (0-1000) → 绝对坐标
fun convertRelativeToAbsolute(element: List<Int>, screenWidth: Int, screenHeight: Int): Pair<Int, Int> {
    val x = (element[0] / 1000.0 * screenWidth).toInt()
    val y = (element[1] / 1000.0 * screenHeight).toInt()
    return Pair(x, y)
}
```

### 系统提示词核心规则

1. 先检查当前 app 是否是目标 app，不是则 Launch
2. 进入无关页面先执行 Back
3. 页面未加载最多 Wait 三次
4. 页面找不到目标信息可 Swipe 查找
5. 购物车全选后再点击全选可全不选
... (共 18 条规则)

---

## 审查

### 阶段一完成总结 (2024-12-18)

**新增文件:**
```
app/src/main/java/com/roubao/autopilot/autoglm/
├── ActionParser.kt    (8.5 KB) - 动作解析器
├── MessageBuilder.kt  (12.9 KB) - 消息构建器
└── AutoGLMAgent.kt    (16.8 KB) - 核心 Agent
```

**核心改进:**
1. **单循环架构**: 相比原来的三层 Agent (Manager/Executor/Reflector)，新实现采用单一循环，减少 VLM 调用次数，提高效率
2. **函数式动作语法**: 采用 `do(action="Tap", element=[x,y])` 格式，比 JSON 更紧凑
3. **相对坐标系统**: 使用 0-999 相对坐标，自动转换为屏幕绝对坐标
4. **Token 优化**: 每步执行后自动从历史消息中移除图片
5. **完整规则集**: 系统提示词包含 18 条操作规则

**待完成:**
- 阶段四: VLM 客户端增强 (流式输出)
- 阶段五-六: UI 集成、测试

### 阶段三完成总结 (2024-12-18)

**新增文件:**
```
app/src/main/assets/app_packages.json                          - 应用包名映射 JSON
app/src/main/java/com/roubao/autopilot/autoglm/AppPackages.kt  - 应用包名工具类
```

**核心功能:**
1. **80+ 应用映射**: 覆盖社交、电商、视频、音乐、出行等 13 个分类
2. **智能匹配**: 支持完全匹配、前缀匹配、包含匹配
3. **双向查找**: 应用名 ↔ 包名
4. **大小写不敏感**: "WeChat" / "wechat" / "微信" 都能匹配
5. **内置备选**: JSON 加载失败时使用内置映射

### 阶段四完成总结 (2024-12-18)

**修改文件:**
```
app/src/main/java/com/roubao/autopilot/vlm/VLMClient.kt     - 新增流式输出支持
app/src/main/java/com/roubao/autopilot/autoglm/AutoGLMAgent.kt - 集成流式 API
```

**新增功能:**

1. **VLMClient 流式输出:**
   - `StreamCallback` 接口: onFirstToken, onThinking, onActionStart, onAction, onComplete, onError
   - `StreamResponse` 结构: thinking, action, rawContent, timeToFirstTokenMs, timeToActionMs, totalTimeMs
   - `predictWithContextStream()` 方法: SSE 格式解析，实时分离思考/动作

2. **AutoGLMAgent 流式集成:**
   - `useStreaming` 配置项 (默认 true)
   - `StepCallback.onThinkingChunk()`: 实时接收思考片段
   - `StepCallback.onPerformanceMetrics()`: 性能指标回调

**性能指标:**
- TTFT (Time To First Token): 首个 token 响应时间
- Time to Action: 检测到动作标记的时间
- Total Time: 完整响应时间

### 阶段五完成总结 (2024-12-18)

**修改文件:**
```
app/src/main/java/com/roubao/autopilot/ui/OverlayService.kt    - 新增思考内容显示
app/src/main/java/com/roubao/autopilot/ui/screens/SettingsScreen.kt - 新增 AutoGLM 模式开关
app/src/main/java/com/roubao/autopilot/data/SettingsManager.kt  - 新增 useAutoGLMMode 设置项
app/src/main/java/com/roubao/autopilot/MainActivity.kt          - 传递新回调
app/build.gradle.kts                                            - 添加 okhttp-sse 依赖
```

**新增功能:**

1. **悬浮窗思考过程显示 (OverlayService):**
   - `thinkingContainer` 和 `thinkingView`: 可展开/收起的思考内容区域
   - `updateThinking()`: 静态方法，实时更新思考文本 (支持追加模式)
   - `clearThinking()`: 清空思考内容
   - `showMetrics()`: 显示性能指标 (TTFT, 总耗时)
   - 点击主状态栏切换展开/收起
   - 深色半透明背景，最多显示 6 行

2. **AutoGLM 模式切换 (Settings):**
   - `useAutoGLMMode`: 新增设置项 (默认开启)
   - 设置页面 "执行设置" 分组中添加开关
   - 开启: 使用优化后的单循环 Agent
   - 关闭: 使用原始三层 Agent (Manager/Executor/Reflector)

**依赖更新:**
- 添加 `okhttp-sse:4.12.0` 用于 SSE 流式响应解析
