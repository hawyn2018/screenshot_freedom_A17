# 更新记录

## 3.4.7

### 修复

- 框架 Binder 投递入口增加真实调用方 UID 检查，仅信任 root（UID 0）或 system（UID 1000）。普通应用、shell 以及其他 UID 在读取附带 Binder、调用 SDK 或同步配置前即被拒绝，已有框架连接保持不变。
- 以自有 Provider 委托原 libxposed service 102.0.0 Provider，移除原 SDK 的直接导出入口，保留原 authority、SEND_BINDER 方法、Bundle 协议和 SDK 初始化/生命周期行为。
- 不使用框架包名、签名、品牌、机型或版本号作为信任条件。此检查认证直接投递者；root/system 属于信任范围。后续框架若改变投递身份或协议，需另行适配验证。

### 界面

- GitHub 链接支持点击浏览和长按选择复制，主页与主题页均生效。
- “解除窗口限制”与“阻止图层排除”下的 `DISABLE_FLAG_SECURE`、`DISABLE_SKIP_SCREENSHOT` 统一字号，各占一行；按实际字体宽度共同调整，兼顾窄屏和字体缩放。

### 验证与范围

- 构建、APK 签名、最终合并 Manifest、DEX、源码与原生资产一致性检查通过。
- 入口鉴权的主机 JVM 验证通过 135 项断言，覆盖两个 call 重载、root/system 允许、其他 UID 拒绝且不触达 SDK、初始化与生命周期。该验证使用 Android 替身，不代表 LSPosed/Vector 真机回归。
- 已通过 root 覆盖安装并核对安装后 APK 校验值；界面效果和实际框架连接由用户手动验证。
- 截图/录屏 hook、名单仲裁、原生程序及系统组件保持不变，系统组件仍为 3.4.2；无需因本次更新更换系统组件。

## 3.4.6

- 首页顶部英文改为 `SCREENSHOT FREEDOM`。
- 底部署名为“作者：Hawyn; Codex”，作者下一行提供可点击的项目 GitHub 链接。
- 系统组件仍为 3.4.2。

## 3.4.5

- 桌面图标采用标准 Adaptive Icon：Monet 底色铺满背景层，原取景框与勾选作为独立前景层。
- `android:icon` 和 `roundIcon` 指向相同的自适应图标，Android 13 及以上提供 monochrome 层。
- 保留原图形与系统 Monet 深浅配色，外轮廓由桌面裁切；系统组件仍为 3.4.2。
