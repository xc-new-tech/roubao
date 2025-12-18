# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## 项目概述

肉包 (Roubao) 是一款开源的 AI 手机自动化助手，使用 Kotlin 原生实现，基于视觉语言模型 (VLM) 在 Android 设备上自动执行任务。核心特点是无需电脑、无需 Python 环境，通过 Shizuku 获取系统级权限。

## 构建命令

```bash
# 构建 Debug 版本
./gradlew assembleDebug

# 安装到设备
./gradlew installDebug

# 构建 Release 版本
./gradlew assembleRelease
```

## 技术栈

- **语言**: Kotlin, JDK 17
- **UI**: Jetpack Compose, Material 3
- **最低 SDK**: 26 (Android 8.0), 目标 SDK: 34
- **权限管理**: Shizuku (系统级控制)
- **网络**: OkHttp
- **崩溃上报**: Firebase Crashlytics

## 核心架构

项目采用三层 Agent 架构，代码位于 `app/src/main/java/com/roubao/autopilot/`:

### 1. Skills 层 (`skills/`)
面向用户意图的任务层，将自然语言映射到操作:
- `SkillManager.kt` - 意图识别和路由
- `SkillRegistry.kt` - Skill 注册表
- `Skill.kt` - Skill 接口定义
- 配置文件: `app/src/main/assets/skills.json`

两种执行模式:
- **Delegation**: 高置信度时直接 DeepLink 跳转到 AI App
- **GUI 自动化**: 通过截图-分析-操作循环完成任务

### 2. Tools 层 (`tools/`)
原子能力封装，每个 Tool 完成独立操作:
- `ToolManager.kt` - 工具管理器
- `SearchAppsTool.kt` - 应用搜索 (支持拼音/语义)
- `OpenAppTool.kt` / `DeepLinkTool.kt` - 应用启动
- `ClipboardTool.kt` / `ShellTool.kt` / `HttpTool.kt`

### 3. Agent 层 (`agent/`)
移植自阿里 MobileAgent-v3 框架，多 Agent 协作:
- `MobileAgent.kt` - 主循环控制
- `Manager.kt` - 规划 Agent
- `Executor.kt` - 执行 Agent
- `ActionReflector.kt` - 反思 Agent (评估操作效果)
- `Notetaker.kt` - 记录 Agent
- `InfoPool.kt` - 状态池

### 其他关键模块

- **controller/**: `DeviceController.kt` (Shizuku 控制), `AppScanner.kt` (应用扫描)
- **vlm/**: `VLMClient.kt` - 视觉语言模型 API 封装
- **ui/screens/**: Compose 页面 (Home, Settings, History, Capabilities, Onboarding)
- **ui/**: `OverlayService.kt` - 悬浮窗服务
- **data/**: `SettingsManager.kt` - 设置管理, `ExecutionHistory.kt` - 执行历史
- **service/**: `ShellService.kt` - Shell 服务

## 工作流程

```
用户输入 → Skills 匹配
  ├── 高置信度 Delegation → DeepLink 跳转 → 完成
  └── 标准路径 → Agent 循环:
      截图 → Manager 规划 → Executor 决策 → 执行动作 → Reflector 反思 → 循环
```

## 开发注意事项

- API Key 使用 AES-256-GCM 加密存储 (`SettingsManager.kt`)
- 检测到支付/密码等敏感页面需自动停止
- 支持多种 VLM: 阿里云通义千问、OpenAI GPT-4V、Claude 等
- v2.0 开发分支: `roubao2.0+AccessibilityService` (集成无障碍服务)
