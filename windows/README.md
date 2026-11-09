# AMDL for Windows

AMDL 的 Windows 桌面版，基于原 Android 项目的下载逻辑和 Rust 核心构建，使用 Kotlin、Compose Desktop 和 Miuix 界面。当前源码版本为 **1.1.6**。

## 系统与使用

- Windows 10 或 Windows 11，x64 架构。
- 有效的 Apple Music 订阅，以及可访问所需音乐的账号。
- 首次登录需要 Microsoft Edge 或 Google Chrome。

从源码构建后，打开 `dist/AMDL-1.1.6/AMDL.exe`，或将 `dist/AMDL-Windows-x64-1.1.6.zip` 解压后运行。应用自带 Java 运行时，请保留完整应用目录。本仓库不包含预先构建的发行包。

1. 首次使用时，在程序打开的独立浏览器窗口中登录 Apple Music。
2. 在「设置」中选择保存目录、音质和其他下载偏好。
3. 在首页粘贴歌曲或专辑链接，点击「开始下载」。
4. 在「下载任务」查看进度或重试失败任务，在「下载记录」查看本次运行已完成的任务。

支持 AAC、优先杜比全景声、元数据、内嵌封面、LRC 歌词和额外的 TTML 逐字歌词。歌曲和账号可能影响音轨及歌词的可用性；项目不提供无损下载。下载记录仅保留本次运行的信息，下载的文件会保留在所选目录。

网页登录使用独立的临时浏览器配置目录。密码在浏览器中输入；保存的登录信息使用 Windows DPAPI 加密并绑定当前 Windows 用户。退出账号会移除保存的登录信息。设置保存在 `%LOCALAPPDATA%/AMDL/settings.json`，默认下载目录为用户音乐目录下的 `AMDL`。

## 从源码构建

准备 Git、PowerShell 7、完整的 x64 JDK 21（包含 `jpackage`）和网络连接。Windows 版通过 Gradle composite build 使用本仓库根目录下的 `miuix/` 源码目录。请在仓库根目录的 PowerShell 7 中执行：

```powershell
git clone https://github.com/compose-miuix-ui/miuix.git miuix
git -C miuix checkout 23b469ee404b0ea9737e225bbd87d224226a3efa
Set-Location windows

# 构建 Rust 引擎、运行测试、打包应用目录和 ZIP
pwsh -NoProfile -File ./scripts/build.ps1
```

构建脚本会查找 JDK 21，也可以通过 `JAVA_HOME` 指定。首次构建会在 `windows/.tools/` 准备独立的 Rust GNU 和 LLVM-MinGW 工具链，并下载 Gradle 及依赖；不修改系统 PATH。构建结果位于 `dist/`。

在 `windows/` 目录中还可以运行：

```powershell
# 原生 DLL 已存在时，跳过 Rust 构建
pwsh -NoProfile -File ./scripts/build.ps1 -SkipNative

# 构建时额外执行隔离数据下的离屏界面渲染
pwsh -NoProfile -File ./scripts/build.ps1 -SkipNative -RenderUi

# 从源码运行，或检查原生库与引擎初始化
pwsh -NoProfile -File ./scripts/run.ps1
pwsh -NoProfile -File ./scripts/run.ps1 -Diagnostics
```

`-SkipNative` 要求已经生成 `src/main/resources/native/windows-x64/amdl.dll`；首次构建请不要使用此选项。若浏览器登录被管理策略阻止，请检查本机浏览器的远程调试权限。账号地区、订阅状态及 Apple Music 接口可能影响下载。

## 许可证

原 AMDL 项目 Copyright (C) 2026 Kyant；本项目采用 [GPL-3.0](LICENSE)。原项目使用 [gamdl](https://github.com/glomatico/gamdl)（MIT License），桌面界面使用 [Miuix](https://github.com/compose-miuix-ui/miuix)。
