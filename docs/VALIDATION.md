# 本次源码整理的验证记录

## 2026-11-27：GitHub 源码发布与自动检查

79 个源码和文档文件已按原目录发布到 GitHub，替换仓库根目录的源码 ZIP，原提交历史保留。首次云端检查在 SDK 安装阶段遇到上游已移除的旧 `tools` 包；工作流现显式安装 `platform-tools`，随后安装 API 35 和 Build Tools 35.0.0。实际检查结果见 [GitHub Actions](https://github.com/wangleo743-ops/androidworld-ondevice-agent/actions/workflows/check.yml)。此工作流只做源码检查、离线测试和构建，不调用真实模型或运行模拟器任务。

## 2026-11-26：源码目录发布复核

本次以 GitHub `6acc5e3` 提交中的源码 ZIP 为依据，核对 Git blob 与清单内 79 个文件的 SHA-256 后，展开为可直接维护的仓库目录。Agent、Control 和模型调用逻辑保留，更新源码获取与维护说明，修复重新打包时重复包含旧文件清单的问题。

复核结果：主机测试 80 项中 79 通过、1 项因本地未安装官方 AndroidWorld 而跳过；Java 检查 8 套共 477 项断言通过；使用 JDK 21.0.10 和 Android API 35 编译接口成功构建 Agent JAR 与 Planner APK。Windows 下目录过深曾导致构建失败，使用较短目录后成功。没有调用真实模型或操作模拟器，这些结果不代表新一轮四任务线上成绩。

以下保留首次整理时的验证记录；GitHub Actions 的实际运行结果以仓库 Actions 页面为准。

## 2026-11-22：首次整理记录

验证日期：2026-11-22。本页只记录这次发布副本的检查，历史任务成绩见 [RESULTS.md](RESULTS.md)。

## 已经验证了什么

| 检查 | 结果 | 能说明什么 |
|---|---|---|
| 主机程序与统一入口测试 | 80 项：79 通过，1 跳过，无失败 | 配置校验、任务范围、批次控制、桥接协议和模型转发等检查通过 |
| Java / MCP 协议检查 | 8 套、477 项断言全部通过 | Agent 请求、Control 会话、MCP、模型报文、系统接口契约通过离线检查 |
| Android 实际构建 | Agent JAR 和 Planner APK 均成功 | 本副本所含源码足以完成 Android 构建，不依赖原研究目录的源码 |
| 报告入口 | 已用既有历史结果生成报告 | 统一入口能读取原有结果格式；这不是一次新的任务实验 |
| 源码检查和压缩包 | 打包脚本扫描文件，并逐文件校验 SHA-256 | 发布内容不包含本机配置、编译产物、测试签名、模拟器镜像或原始实验记录 |

主机测试中跳过的是“与官方 AndroidWorld 任务定义比较”的检查，因为此次使用的 Windows Python 没有安装 `android_world`。完整基准运行环境应按 [SETUP.md](SETUP.md) 在 Linux / WSL 中安装。没有把这项跳过记为通过。

Java 检查的 8 套明细：

| 测试类 | 通过断言数 |
|---|---:|
| BenchmarkContractChecks | 87 |
| AndroidWorldFocusChecks | 38 |
| AndroidWorldPayloadChecks | 13 |
| AndroidWorldTaskPayloadChecks | 94 |
| AndroidWorldDecisionDiagnosticChecks | 72 |
| PortableResponsesChecks | 27 |
| PlatformContractChecks | 57 |
| StockMcpChecks | 89 |
| **合计** | **477** |

构建使用 Windows、JDK 21.0.10、Android API 35 编译接口和 Build Tools 35，输出最低 API 33。JVM 检查编译了 47 个 Java 文件。README 推荐的 Linux / WSL + JDK 17 环境尚未在一台全新机器上从头验收；仓库提供了对应 GitHub Actions 工作流，但本次尚未推送或执行云端 CI。

## 模型转发测到了什么

`host_relay` 使用本机临时 HTTP 服务模拟上游，检查实时 SSE 转发、临时令牌、固定模型和端点、禁止重定向、错误体脱敏、大小限制、超时和关闭取消。HTTPS 连接使用系统默认 TLS 校验上下文。没有在这些检查中调用真实模型。

它是此次整理增加的网络兼容措施：AndroidWorld 会固定模拟器时间，模型 HTTPS 证书由时间正确的主机校验。Agent 的任务循环、MCP 和 Control 仍在 Android 内。正式运行的 `model_seconds` 包含这段网络转发时间，不应再把转发统计加一次。

## 还没有验证什么

- 这次整理版尚未重新连接模拟器和真实模型完成四项任务；历史四项成功记录不能代替这一验收。
- 尚未在全新 Linux / WSL 机器完成安装、启动、部署和在线运行的完整流程。
- 没有测试四项任务之外的任意 App、物理手机、第二套虚拟界面或 MobiClaw 系统后端。
- 当前标准安卓基线没有接入逐任务电脑真人授权，离线权限契约检查不等于正式用户授权验收。

## 如何复查

```bash
python3 scripts/check.py
python3 scripts/check_distribution.py
python3 scripts/check_java.py
python3 run.py build
python3 scripts/package.py
```

Java 检查和构建需要安装说明中的 JDK / SDK；首次 Java 检查还需取得校验哈希固定的 `org.json` 测试依赖。检查不调用真实模型，也不操作模拟器。在线验收请使用安装说明中的专用模拟器，并记录实际使用的模型、配置和新生成的批次结果。
