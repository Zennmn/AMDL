# AMDL · Apple Music Downloader

AMDL 是一个 Apple Music 音乐下载工具。本仓库包含原有 Android 项目和基于它移植的 Windows 桌面版。使用时需要有效的 Apple Music 订阅，以及能够访问相应音乐的账号。

## 功能

- 通过 Apple Music 歌曲或专辑链接创建下载任务。
- 支持 AAC，并可优先选择杜比全景声音轨；实际可用格式取决于歌曲和账号。
- 写入歌曲元数据与封面，支持内嵌 LRC 歌词和额外保存 TTML 逐字歌词。
- Windows 版提供任务队列、失败重试、下载记录、目录选择和浅色／深色界面。

## 选择平台

| 平台 | 项目位置 | 说明 |
| --- | --- | --- |
| Android | 仓库根目录的 `app/` 和 `rust/` | 原 Android 工程，可使用 Android Studio 打开仓库根目录。 |
| Windows 10/11 x64 | [`windows/`](windows/) | Compose Desktop 桌面工程；构建、运行和登录说明见 [Windows 使用指南](windows/README.md)。 |

Windows 桌面源码版本为 **1.1.6**。仓库提交包含源码，不包含已构建的安装包或 ZIP；请按指南自行构建，或使用维护者之后提供的发行包。

## Windows 快速构建

需要 Windows x64、PowerShell 7、完整的 x64 JDK 21（含 `jpackage`）和 Git。项目使用固定版本的 [Miuix](https://github.com/compose-miuix-ui/miuix) 源码进行 composite build。首次构建还会准备 Rust 与 LLVM-MinGW 工具链，并下载 Gradle 依赖。

在本仓库根目录的 PowerShell 7 中执行：

```powershell
git clone https://github.com/compose-miuix-ui/miuix.git miuix
git -C miuix checkout 23b469ee404b0ea9737e225bbd87d224226a3efa
Set-Location windows
pwsh -NoProfile -File ./scripts/build.ps1
```

构建成功后，应用目录为 `windows/dist/AMDL-1.1.6/`，ZIP 为 `windows/dist/AMDL-Windows-x64-1.1.6.zip`。完整应用目录包含 Java 运行时；使用时保留整个目录。首次登录需要本机安装 Microsoft Edge 或 Google Chrome。详细说明和其他命令见 [Windows 使用指南](windows/README.md)。

## 许可与致谢

AMDL 原项目 Copyright (C) 2026 Kyant，采用 [GNU General Public License v3.0](LICENSE)。原项目使用了 [gamdl](https://github.com/glomatico/gamdl)（MIT License，Copyright © 2024 Glomatico）；Windows 界面使用 [Miuix](https://github.com/compose-miuix-ui/miuix)。其他依赖遵循各自的许可证。
