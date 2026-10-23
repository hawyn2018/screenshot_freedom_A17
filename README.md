# 自由截图A17 / Screenshot Freedom A17

**应用版本 3.4.7 · versionCode 31 · 系统组件 3.4.2**

包名：`net.hawyn.screenshotfreedoma17`  
作者：Hawyn; Codex

系统截图与录屏兼容模块，包含 Android 应用、LSPosed 系统侧模块和独立原生系统组件。

## 功能

- 两个总开关分别控制 `FLAG_SECURE` 窗口限制与 `SKIP_SCREENSHOT` 图层排除兼容。
- 采集工具黑名单、白名单独立保存，各工具分别设置两种机制；新安装默认关闭名单仲裁。
- 提供真实窗口保护、图层排除测试页，以及 Monet 动态主题和悬浮底栏。
- 3.4.5 桌面图标采用前景、背景分离的 Adaptive Icon，沿用系统 Monet 配色。

应用采用系统级作用域，按实际接口及代码语义适配；具体设备支持情况以实际使用为准。
采集入口仍沿用系统原有权限和 MediaProjection 授权。

## 更新记录

完整版本列表见 [CHANGELOG.md](CHANGELOG.md)。

### 3.4.7

- **修复框架 Binder 投递入口鉴权**：使用实际 Binder 调用方 UID，仅接受 root（0）和 system（1000）。普通应用及其他 UID 的投递会在进入 SDK 前被拒绝，不替换已连接的服务，也不向其同步设置或名单。
- **GitHub 链接支持选择复制**：保留点击浏览，新增长按选择文字与复制；主页和主题页共用。
- **统一机制标签排版**：“解除窗口限制”下的 `DISABLE_FLAG_SECURE` 与“阻止图层排除”下的 `DISABLE_SKIP_SCREENSHOT` 使用相同字号，各占一行，并随可用宽度共同调整。

保留原 libxposed authority、投递协议和 SDK 初始化行为，不按框架名称、包名、签名或版本筛选。
截图/录屏 hook、名单仲裁、原生程序和系统组件未变，组件仍为 3.4.2。
已通过构建、签名、包体核验及入口鉴权主机验证；实际框架分支兼容性以设备验证为准。

## 安装与升级

本仓库仅发布源码，不创建 GitHub Release。
使用自己构建的 APK，在 LSPosed 中启用“自由截图A17”并设置对应系统作用域。
原生图层兼容需要安装构建生成的系统模块，或使用应用内“安装/更新系统模块”。
同包名 APK 覆盖升级要求签名证书一致；这里没有原发布者的签名私钥，
使用自己的签名构建时应按独立签名应用安装，已有配置请自行备份。
框架及系统模块切换按各管理器提示完成，避免同时启用重复的兼容模块。

## 从源码构建

当前完整打包脚本针对 Windows，准备以下环境：

- Python 3、Git、JDK 21；Gradle Wrapper 已包含在仓库中。
- Android SDK Platform 37、Build Tools 37.0.0。
- Android NDK r29，原生系统组件当前构建为 `arm64-v8a`。
- 按 `app/gradle.lockfile` 锁定的依赖版本，首次构建需要联网下载。

在 PowerShell 中配置自己的安装路径，例如：

```powershell
$env:JAVA_HOME = 'C:\path\to\jdk-21'
$env:ANDROID_SDK_ROOT = 'D:\path\to\AndroidSDK'
$env:ANDROID_NDK_HOME = 'D:\path\to\AndroidSDK\ndk\android-ndk-r29'
```

正式打包需要自己的签名材料。先创建已被 Git 忽略的 `signing` 目录，
再通过交互式 `keytool` 生成密钥：

```powershell
New-Item -ItemType Directory -Force signing
keytool -genkeypair -keystore signing/screenshot-compat.jks -alias screenshotcompat -keyalg RSA -keysize 3072 -validity 10000
```

将相同的密钥库口令仅保存在本地 `signing/keystore.pass` 文件中，然后运行：

```powershell
python tools/build.py
```

脚本会先从 `native/` 重建原生程序、准备应用资产，再构建并签名 APK。
输出位于 `dist/`：应用 APK、源码包、系统模块 ZIP、校验文件及公开签名证书。
`--ui-only` 和 `--app-only` 是原开发工作区的历史版本比对模式，依赖本地封存的 `releases/`；
公开源码仓库应使用上述完整构建命令。

## 源码范围

源码来自已交付的 3.4.7 封存源码包，应用和原生功能源码保持原样。
公开快照不包含发布签名材料、访问令牌、设备日志、历史设备验证脚本和报告、
构建缓存、生成的原生二进制、APK 或历史发布产物。
`SOURCE_SNAPSHOT.json` 记录原始源码包校验值及保留源码文件的校验值。

## 许可证

项目使用 **GNU General Public License v3.0 (GPL-3.0)**。
完整许可证见 [LICENSE.txt](LICENSE.txt)，第三方依赖与来源声明见 [NOTICE.txt](NOTICE.txt)。
