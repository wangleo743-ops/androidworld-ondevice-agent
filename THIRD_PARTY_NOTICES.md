# 第三方来源与许可说明

本项目将自己的 Android 内 Agent 与 Control 接入 AndroidWorld。AndroidWorld 官方任务、初始化和评分代码由安装步骤从固定上游版本取得，保留其原有版权和许可。

| 组件 | 来源 | 说明 |
|---|---|---|
| AndroidWorld | [google-research/android_world](https://github.com/google-research/android_world) | 上游使用 Apache License 2.0；固定版本为 `55ddaf5edc6f6c25673aed343907d5b9dca66f84` |
| AndroidEnv | [google-deepmind/android_env](https://github.com/google-deepmind/android_env) | Python 运行依赖，版本固定为 1.2.3；使用时保留安装包随附许可 |
| Android SDK、Build Tools、Emulator、系统镜像 | [Android Developers](https://developer.android.com/studio) | 由使用者通过官方渠道安装，遵守各组件的 Android SDK 许可；本仓库不分发这些二进制文件 |
| `org.json:json:20240303` | [JSON-java](https://github.com/stleary/JSON-java) / Maven Central | JVM 测试依赖，首次检查时下载并校验哈希；遵循该版本随附的许可，缓存不打包分发 |
| 其他 Python 依赖 | 安装后的各软件包与其上游项目 | 版本见 `androidworld-integration/requirements-restored-20261122.txt`，各自遵循原许可 |

本仓库没有提供 API Key、账号令牌、签名私钥、APK、模拟器镜像或真实实验截图。构建所需的测试签名在使用者本机生成，生成的文件不应提交到仓库。

## 本项目原创代码

本次整理没有替维护者选择原创代码的开源许可证。公开发布前，维护者需要确认原创代码的权属和拟采用的许可证，再添加对应的 `LICENSE` 文件。本页的第三方许可说明不自动授予本项目原创代码某一种开源许可。

## 引用 AndroidWorld

使用 AndroidWorld 的任务或评测结果时，请引用其论文，并注明本项目采用自定义的 Android 内 Agent / Control：

```bibtex
@misc{rawles2024androidworlddynamicbenchmarkingenvironment,
  title={AndroidWorld: A Dynamic Benchmarking Environment for Autonomous Agents},
  author={Christopher Rawles and Sarah Clinckemaillie and Yifan Chang and Jonathan Waltz and Gabrielle Lau and Marybeth Fair and Alice Li and William Bishop and Wei Li and Folawiyo Campbell-Ajala and Daniel Toyama and Robert Berry and Divya Tyamagundlu and Timothy Lillicrap and Oriana Riva},
  year={2024},
  eprint={2405.14573},
  archivePrefix={arXiv},
  primaryClass={cs.AI},
  url={https://arxiv.org/abs/2405.14573}
}
```
