<p align="center">
  <a href="#qq-group">QQ 交流群 941336897</a> ·
  <a href="https://t.me/blroot">Telegram @blroot</a>
</p>
<p align="center"><img src="docs/app-icon.svg" width="104" height="104" alt="自由截图 A17 应用图标"></p>
<h1 align="center">自由截图 A17 · Screenshot Freedom A17</h1>
<p align="center">
  <a href="#qq-group">QQ 群 941336897</a> ·
  <a href="https://t.me/blroot">Telegram @blroot</a> ·
  <a href="https://www.coolapk.com/u/43879986">酷安 hawyn</a> ·
  <a href="https://m.tb.cn/h.jgDi6un?tk=4QbA6Z6JhAO">闲鱼 2018hawyn</a>
</p>
<p align="center">
  <a href="#zh-cn">中文版</a> · <a href="#en">English</a> ·
  <a href="https://github.com/hawyn2018/screenshot_freedom_A17/releases/tag/v3.6.7">v3.6.7 源码 / Source</a>
</p>

<a name="zh-cn"></a>

## 中文版

自由截图 A17 为解除 Android 17 的截图限制而来。在解除部分软件窗口截图限制的基础上，增加了禁止截图图层排除的功能。两项机制可分别开启，并可用于受支持的截图、录屏和 ADB 投屏链路。

### 安装与验证

1. 按[构建说明](#build)生成并安装自己的签名 APK。首次打开时，可阅读完整许可证，再确认或退出。
2. **窗口保护**：在兼容 libxposed API 102 的 LSPosed 框架中启用「自由截图 A17」，勾选推荐的「系统框架」作用域，然后按框架提示重启。若系统厂商在独立截图或录屏进程中额外检查结果或显示提示，可按设备实际情况加入对应的**系统采集组件**，再重新加载。保持被采集的普通应用在作用域之外。
3. **图层排除与采集仲裁**：在有 Root 的设备上，点击首页「安装/更新系统模块」，或通过 KernelSU 安装构建生成的系统模块 ZIP。按管理器提示重启，回到首页确认截图子层、录屏子层和采集仲裁状态。应用版本为 **3.6.7**，配套系统模块版本为 **3.6.1**。
4. 在首页按需打开下表中的开关。分别在「窗口测试」和「图层测试」使用系统截图或录屏，检查保存文件中的四色、字母和图案编号。改变录屏配置后，请停止旧录屏再开始。ADB 投屏也可断开重连后对照测试。

| 首页开关 | 作用 |
| --- | --- |
| 解除窗口限制 · `DISABLE_FLAG_SECURE` | 处理部分应用给窗口设置的 `FLAG_SECURE` 及受支持的系统采集结果检查。 |
| 阻止图层排除 · `DISABLE_SKIP_SCREENSHOT` | 尝试让系统采集保留被标记为排除的子图层。效果取决于设备的 SurfaceFlinger 实现及采集路径。 |

### 采集工具名单、状态与诊断

- **采集工具名单**针对发起截图、录屏或投屏的工具。关闭仲裁时，所有采集工具可按首页开关获得兼容。开启后，黑名单限制已勾选的工具，白名单仅允许已勾选的工具。两种名单独立保存，每个工具可分别配置 `FLAG_SECURE` 和 `SKIP_SCREENSHOT`。新安装默认关闭仲裁。
- **检查间隔**可填 0–86400 秒。新安装默认 **0 秒**，关闭后台定时完整检查。主页在前台亮屏时会主动刷新过期状态；退到后台或退出后，原生定时检查按保存值运行。
- **日志**默认关闭。排查问题时开启日志、复现，再手动点击「导出日志」。ZIP 保存到 Download，格式为 `screenshotfreedom_log_版本_yyyyMMdd_HHmmss.zip`。Root 可补充诊断数据。
- **兼容范围**取决于设备和采集入口。ADB 投屏支持经系统 DisplayManager 建立的部分自动镜像，例如走此入口的 scrcpy；旧式或厂商私有路径需实测。系统权限和 MediaProjection 授权仍照常适用。仅有应用和框架时可验证窗口兼容；图层排除和名单仲裁还依赖原生系统模块。

[切换到 English](#en)

<a name="en"></a>

## English

Screenshot Freedom A17 was built to lift Android 17 screenshot restrictions. It can lift window-level screenshot restrictions for some apps and adds a separate option to prevent layers from being excluded from capture. Both mechanisms can be configured independently for supported screenshot, recording, and ADB mirroring paths.

### Installation and verification

1. Follow the [build instructions](#build) to generate and install an APK signed with your own key. On first launch, you can read the full license before accepting or exiting.
2. **Window protection:** Enable Screenshot Freedom A17 in an LSPosed framework compatible with libxposed API 102, select the recommended **System Framework** scope, and restart as prompted. If an OEM checks results or displays warnings in a separate screenshot or recording process, add the corresponding **system capture component** to the scope as needed and reload. Keep ordinary apps being captured outside the scope.
3. **Layer exclusion and capture-tool rules:** On a rooted device, choose **Install / update system module** on the home page, or install the system module ZIP produced by the build through KernelSU. Restart as instructed by the manager, then check the screenshot layer, recording layer, and capture-tool arbitration statuses on the home page. The app version is **3.6.7** and the companion module version is **3.6.1**.
4. Enable the switches below as needed. Use your system screenshot or recording controls on the **Window Test** and **Layer Test** pages, then check the saved file for all four colors, the letters, and the pattern number. Restart recording after changing its settings. Reconnect ADB mirroring when comparing results.

| Home switch | What it does |
| --- | --- |
| `DISABLE_FLAG_SECURE` | Handles the `FLAG_SECURE` window setting used by some apps and supported system capture result checks. |
| `DISABLE_SKIP_SCREENSHOT` | Attempts to retain child layers marked for exclusion. Results depend on the device's SurfaceFlinger implementation and capture path. |

### Capture tools, status, and diagnostics

- **Capture-tool lists** apply to the tool initiating a screenshot, recording, or mirror session. With arbitration off, the home switches apply to all capture tools. With arbitration on, the blocklist restricts selected tools and the allowlist permits only selected tools. The lists are stored separately and each tool has its own `FLAG_SECURE` and `SKIP_SCREENSHOT` options. Arbitration is off by default on fresh installs.
- **Check interval** accepts 0–86,400 seconds. Fresh installs use **0 seconds**, which turns off scheduled full checks in the background. The home page requests a refresh when status expires while the app is visible and the screen is on. Scheduled native checks use the saved interval when the app is in the background or closed.
- **Diagnostic logging** is off by default. To troubleshoot, turn it on, reproduce the issue, and choose **Export logs**. The ZIP is saved to Download as `screenshotfreedom_log_version_yyyyMMdd_HHmmss.zip`. Root access can provide additional diagnostics.
- **Compatibility** varies by device and capture entry point. ADB mirroring covers some automatic-mirroring paths through the system DisplayManager, such as scrcpy when it uses that path. Older and OEM-specific paths require device testing. System permissions and MediaProjection approval still apply. You can test window compatibility with the app and framework alone. Layer exclusion and capture-tool arbitration also require the native system module.

[切换到中文版](#zh-cn)

<a name="build"></a>

## 从源码构建 · Build from source

完整构建脚本面向 Windows。需要 Python 3、JDK 21、Android SDK Platform 37、Build Tools 37.0.0 和 Android NDK r29。仓库已包含 Gradle Wrapper，首次构建需下载依赖。当前原生组件构建目标为 `arm64-v8a`。

The full packaging script targets Windows. Install Python 3, JDK 21, Android SDK Platform 37, Build Tools 37.0.0, and Android NDK r29. The Gradle Wrapper is included and the first build downloads dependencies. The current native build targets `arm64-v8a`.

在 PowerShell 配置自己的工具路径并生成本地密钥库。 / Set your tool paths and create a local keystore in PowerShell.

```powershell
$env:JAVA_HOME = 'C:\path\to\jdk-21'
$env:ANDROID_SDK_ROOT = 'D:\path\to\AndroidSDK'
$env:ANDROID_NDK_HOME = 'D:\path\to\AndroidSDK\ndk\android-ndk-r29'

New-Item -ItemType Directory -Force signing
keytool -genkeypair -keystore signing/screenshot-compat.jks -alias screenshotcompat -keyalg RSA -keysize 3072 -validity 10000
```

将密钥库口令保存到本地 `signing/keystore.pass`，再运行 `python tools/build.py`。脚本会编译原生组件、生成并签名 APK，输出到 `dist/`。覆盖安装要求 APK 签名证书一致。

Save the keystore password locally in `signing/keystore.pass`, then run `python tools/build.py`. The script builds the native companion and signs the APK, with outputs in `dist/`. An in-place Android upgrade requires the same signing certificate.

## 更新记录与许可证 · Changelog and license

- [更新记录 / Changelog](CHANGELOG.md) · [源码校验记录 / Source snapshot](SOURCE_SNAPSHOT.json)
- 项目自有代码使用 **GNU Affero General Public License v3.0 only（AGPL-3.0-only）**，与应用内许可一致。第三方内容各自适用其原许可证。
- The project's own code is licensed under **GNU Affero General Public License v3.0 only (AGPL-3.0-only)**, matching the in-app license. Third-party material retains its respective license.
- [AGPLv3 原文 / License text](LICENSE.txt) · [第三方声明 / Third-party notices](NOTICE.txt)

<a name="qq-group"></a>

### QQ 交流群 · QQ group

在 QQ 的群搜索中输入 **941336897**。 / In QQ, search for group **941336897**.
