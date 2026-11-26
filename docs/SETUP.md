# 安装与运行

这份指南使用同一台 Linux / WSL 主机上的 Python、ADB 和专用模拟器。所有服务器地址与本机路径都由使用者配置，不依赖原实验室的 SSH、VPN 或内部网关。

## 1. 准备环境

需要：

- Python 3.11，以及可用的 `venv`、`pip`。
- Git。
- JDK 17，包含 `java`、`javac`、`keytool`。
- Android SDK Command-line Tools、Platform Tools、Emulator。
- 编译平台 `platforms;android-35` 与构建工具 `build-tools;35.0.0`。
- 运行镜像 `system-images;android-33;google_apis;x86_64`。
- 支持图片输入、Responses API 和流式文本回答的模型接口。

编译 SDK 35 与运行 API 33 是两个概念：前者供 Java 编译使用，后者是当前基线经过适配的安卓运行环境。

Linux / WSL 必须能够运行 Android 模拟器。若运行环境没有可用的硬件虚拟化，先解决模拟器启动问题，再安装和运行实验。当前指南没有验证 macOS ARM 或原生 Windows 的完整评测链；Windows 用户使用 WSL 时，应在 WSL 内安装 Linux 版 SDK，而不是混用 Windows 的 `adb.exe`。

Android SDK 可从 [Android 官方下载页](https://developer.android.com/studio#command-line-tools-only) 安装。根据自己的安装位置设置环境变量，例如：

```bash
export ANDROID_SDK_ROOT="$HOME/Android/Sdk"
export JAVA_HOME="/path/to/jdk-17"
export PATH="$JAVA_HOME/bin:$ANDROID_SDK_ROOT/platform-tools:$ANDROID_SDK_ROOT/emulator:$PATH"
```

`/path/to/jdk-17` 是占位路径，要替换成自己的 JDK 目录。使用 SDK Manager 安装所需组件：

```bash
"$ANDROID_SDK_ROOT/cmdline-tools/latest/bin/sdkmanager" --licenses
"$ANDROID_SDK_ROOT/cmdline-tools/latest/bin/sdkmanager" \
  "platform-tools" \
  "emulator" \
  "platforms;android-35" \
  "build-tools;35.0.0" \
  "system-images;android-33;google_apis;x86_64"
```

这些命令会从 Android 官方服务器下载组件；镜像没有包含在本仓库中。SDK 许可由安装者在自己的机器上阅读并确认。

## 2. 生成本机配置

在仓库根目录运行：

```bash
python3 run.py init
```

编辑生成的 `config.local.json`，检查这些字段：

| 字段 | 应填写什么 |
|---|---|
| `android_sdk_root` | 当前运行环境中的 SDK 根目录 |
| `java_home` | JDK 根目录，下面应有 `bin/java` 和 `bin/javac` |
| `compile_sdk` | 保持 `35` |
| `build_tools_version` | 保持 `35.0.0` |
| `model_transport` | 默认 `host_relay`；`direct` 为经过验证的模拟器直连场景 |
| `responses_endpoint` | 完整的 Responses API URL，例如 `https://api.openai.com/v1/responses` |
| `model` | 服务实际提供的准确模型名 |
| `effort` | 默认 `default`；仅在模型支持时指定其他强度 |
| `allow_http` | 默认 `false`；明确使用 HTTP 接口时才改成 `true` |
| `avd_name` | 专用模拟器名称，默认 `AndroidWorldAvd` |
| `serial` | 默认 `emulator-5554`，必须与控制台端口对应 |
| `console_port` | 默认 `5554` |
| `grpc_port` | 默认 `8554` |
| `seed` | 默认 `30`，决定任务参数 |
| `timeout_seconds` | 默认 `360` |

`effort` 可选 `default`、`none`、`minimal`、`low`、`medium`、`high`、`xhigh`。程序允许这些配置，不代表每个模型都支持每一种；不确定时先用 `default`。

默认 `host_relay` 下，由主机连接填写的模型服务地址；模拟器只连接程序建立的本次任务转发端口。真实 API Key 保留在主机内存，模拟器客户端使用临时令牌。

这是因为 AndroidWorld 官方初始化会将设备时间设为 2023 年，模拟器直连 HTTPS 服务时可能发生证书时间校验错误。转发模式使用主机当前时间正常验证 HTTPS；不需要修改官方时间或关闭证书校验。

选择 `direct` 时，模型客户端直接从模拟器访问 `responses_endpoint`。模拟器中的 `localhost` 指向模拟器自己；如果模型网关运行在模拟器宿主机，可按实际端口使用 `10.0.2.2`，HTTP 模型地址还需要显式开启 `allow_http`。例如 `http://10.0.2.2:8000/v1/responses` 只是地址格式示例，不表示程序会启动该网关。直连场景要自行核对模拟器网络与服务兼容性。

不要把 API Key 写进 URL、配置文件、命令行参数或 README。

## 3. 安装 Python 依赖

```bash
python3 run.py setup --python python3.11
```

该步骤创建仓库本地 `.venv`，安装依赖，并将固定版本的上游代码放入 `androidworld-integration/upstream/`。上游提交固定为：

```text
55ddaf5edc6f6c25673aed343907d5b9dca66f84
```

运行前会核对该版本及受跟踪文件是否被修改。自行修改官方任务或评分器后，不要把结果继续标记为本版本的官方环境结果。

## 4. 编译 Android 程序

```bash
python3 run.py build
```

会在本机编译：

- `stock-android-agent.jar`：包含 Agent 与独立 Control 的运行代码。
- `androidworld-planner.apk`：独立模型客户端。
- `build.json`：源码和产物哈希、构建信息。

产物位于 `androidworld-integration/build-stock-baseline/`。首次构建会生成本机测试签名并把对应公钥指纹编入 Agent，使它能核对所连接的 Planner APK。后续应保留同一测试签名；若更换签名，应在专用模拟器中明确处理旧测试 APK 后重新部署，不能将签名不匹配当作可以忽略的错误。

## 5. 创建并启动专用模拟器

```bash
python3 run.py emulator create
python3 run.py emulator start
```

启动命令在前台运行，保持该终端打开。之后在同一 Linux / WSL 环境中打开另一个终端，回到仓库根目录。

本入口限定 API 33、模拟器设备及主屏 display 0。它会拒绝真机，也不会自动改用其他已连接设备。AndroidWorld 官方参考环境见 [上游安装说明](https://github.com/google-research/android_world#installation)。

## 6. 准备任务应用并部署

```bash
python3 run.py prepare --dedicated-emulator
python3 run.py deploy --dedicated-emulator
python3 run.py doctor
```

`prepare` 配置这四项任务需要的官方环境和应用。`deploy` 安装本机构建的 Planner APK，并将 Agent JAR 放到指定模拟器。`doctor` 只检查主机依赖与配置，不连接设备；`prepare`、`deploy` 和 `run` 会各自核对设备是否为指定模拟器。首次安装不要求先让 `doctor` 全部通过。

任务准备和后续运行会改变该模拟器的数据：时钟任务清理时钟应用状态，亮度任务设置初始亮度，相机任务清理 `/sdcard/Pictures` 和 `/sdcard/Movies`。必须使用只存放实验数据的专用模拟器。

## 7. 先看计划，再运行

```bash
python3 run.py plan
python3 run.py run --dedicated-emulator
```

`plan` 展示任务、参数、执行范围和准备动作，不调用模型。运行时会提示隐藏输入 API Key；输入时没有回显是正常的。四任务批次只需输入一次，密钥随本次进程结束释放。

可以指定任务子集：

```bash
python3 run.py run --dedicated-emulator \
  --tasks ClockStopWatchRunning,SystemBrightnessMax
```

这些任务使用同一配置中的模型和种子。这里的选择只限已经实现的四项任务，不会自动导入整个 AndroidWorld 任务集。

## 8. 生成报告

运行完成后，终端会打印批次目录，例如 `androidworld-integration/batches/batch-.../`。使用该目录中的实际 `summary.json` 路径：

```bash
python3 run.py report "androidworld-integration/batches/替换为实际批次目录/summary.json"
```

将示例中的“替换为实际批次目录”换成终端打印的目录名。输出 HTML 和文本报告。报告中的截图链接指向本次本机结果文件；分享报告时，需要连同被引用的结果文件一起分享，并先检查截图是否含有不适合公开的信息。

时间定义与结果解释见 [RESULTS.md](RESULTS.md)。

## 常见问题

| 现象 | 先检查什么 |
|---|---|
| 找不到 `java`、`javac` 或 SDK 工具 | `java_home`、`android_sdk_root`、编译平台和 Build Tools 是否齐全 |
| 找不到上游代码或 Python 模块 | 是否成功执行 `setup`，是否使用仓库生成的依赖环境 |
| 拒绝连接设备 | 是否为配置中的专用 API 33 模拟器，AVD 名称、serial 和端口是否一致 |
| Planner 签名或文件哈希不一致 | 是否把同一次构建的 JAR 和 APK 一起部署；是否误用了旧构建 |
| 接口报错或模型拒绝参数 | URL 是否为完整 Responses 地址、模型是否支持图片与流式输出、`effort` 是否受支持 |
| 任务环境准备超时 | 检查模拟器和 AndroidWorld 初始化日志；保留该次失败记录，再单独重跑 |
| 模型说完成但得分为 0 | 模型判断与实际状态可能不同，以官方评分及最终截图定位问题 |

本版本的电脑确认授权尚未从天气路径迁入。启动确认和 `--dedicated-emulator` 只表示操作者选择实验环境，不是逐任务系统用户授权。

## 本地验证与发布检查

以下命令不需要模型密钥，不运行在线任务：

```bash
python3 scripts/check.py
python3 scripts/check_distribution.py
python3 scripts/check_java.py
```

`check.py` 检查主机运行器和根命令入口；`check_distribution.py` 检查待分发文件中不应出现的配置、凭据和运行产物。

`check_java.py` 默认读取本机配置中的 JDK / SDK 路径，首次会从 Maven Central 下载固定版本的 `org.json` 测试依赖并校验哈希，缓存到 `.cache/`。依赖已缓存时可以使用 `--offline`；也可以通过 `--json-jar` 指定现有依赖。测试会启动临时的本地 HTTP 测试服务，不调用真实模型。其他参数见 `python3 scripts/check_java.py --help`。

这些检查用于验证程序约定和发布内容，不能替代真实模型、模拟器和四项任务的在线复验。
