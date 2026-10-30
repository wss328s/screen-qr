# 屏幕二维码连续识别（ScreenQrScanner）

一个安卓原生 APP：在后台持续监听屏幕，**只要屏幕上出现二维码，就自动识别内容并以悬浮窗形式弹出**，支持连续扫码、去重、一键复制。

- 纯本地离线识别（ZXing），**不依赖 Google Play Services**，国产 ROM（小米/华为/OPPO/vivo）都能跑
- 前台服务 + MediaProjection 截屏，后台不被杀
- 同一内容 8 秒内不重复弹窗，避免轰炸
- 识别到会震一下提醒

---

## 本次优化（v1.1）

在保持原有全部功能不变的前提下，做了代码健壮性与界面 UI 的全面打磨：

**代码健壮性**
- 修复 Android 14（API 34）上 `startForeground` 缺少服务类型导致崩溃的问题（显式声明 `mediaProjection` 类型）
- 截屏初始化失败 / VirtualDisplay 创建失败时统一清理资源并结束服务，避免半启动态与资源泄漏
- `MediaProjection` 被系统/用户停止时自动结束服务，不再残留「假运行」状态
- 防止重复启动（已在运行时忽略重复 `ACTION_START`）
- 诊断计数改用 `@Volatile`，保证后台线程写、主线程读的可见性
- 单帧处理异常不再静默吞掉，写入 Log 便于排查
- 音效改用 `SoundPool` 加载完成回调，避免加载完成前播放无效音
- 通知图标改用应用自身图标

**界面 UI 美化**
- 全局配色重设计：深色背景层级 + 亮青科技蓝主色
- 主界面：状态卡片带实时状态圆点（运行=绿/停止=灰）、渐变主按钮、描边次级按钮
- 悬浮窗：圆角卡片 + 标题栏 + 底部「复制全部」渐变按钮 + 右上角「重置」，样式切换保留圆角
- 返回主界面自动刷新运行状态

---

## 一、怎么编译出 APK

你需要一台 Windows/Mac 电脑，装一次 Android Studio（约 1GB）。

1. 下载 Android Studio：https://developer.android.com/studio （国内镜像：https://developer.android.google.cn/studio）
2. 打开 Android Studio → `File` → `Open` → 选这个 `ScreenQrScanner` 文件夹
3. 等它自动下载 Gradle 和依赖（第一次约 5–10 分钟，需要联网）
4. 手机打开「开发者选项」→「USB 调试」，用数据线连电脑
5. 点工具栏绿色三角 ▶️（Run 'app'），APK 会自动装到手机上

> 没有数据线也可以：Android Studio 菜单 `Build` → `Build Bundle(s) / APK(s)` → `Build APK(s)`，生成的 APK 在 `app/build/outputs/apk/debug/app-debug.apk`，发到手机安装即可。

---

## 二、装好后怎么用（第一次要给三个权限）

1. 打开 APP → 点「启动连续识别」
2. 弹出「显示在其他应用上层」权限 → 去系统设置打开
3. 回到 APP 再点一次「启动」→ 系统弹出「开始录制屏幕/投射到其他应用」→ 点「立即开始」
4. 通知栏会出现「屏幕二维码识别中」常驻通知
5. **切到任何 APP，只要屏幕上出现二维码，顶部就会弹悬浮窗显示内容**

> Android 安全限制：每次重启手机或强杀 APP 后，都要重新点一次「启动」再授权一次屏幕录制。这是系统强制的，绕不开。

---

## 三、可调参数（在 `QrScanService.kt` 顶部）

| 参数 | 默认 | 作用 |
|---|---|---|
| `SCAN_INTERVAL_MS` | 600 | 多久截一次屏。越小越跟手，越费电。想更灵敏改 300 |
| `DEDUP_TTL_MS` | 8000 | 同一内容多少毫秒内不重复弹 |
| `SCALE_WIDTH` | 500 | 识别前把画面缩到多宽。越小越快，但太小程序识别率下降 |

---

## 四、常见问题

**Q：识别率低 / 小二维码识别不出来？**
A：把 `SCALE_WIDTH` 从 500 改到 800 或 1000，精度更高但更耗电。

**Q：锁屏后就不识别了？**
A：正常。MediaProjection 在锁屏时不返回画面，亮屏才工作。

**Q：手机后台一会就被杀？**
A：在系统设置里把这个 APP 加入「后台白名单/自启动」，关闭电池优化。

**Q：想加自动打开链接？**
A：在 `FloatingResultManager.show()` 里加判断：如果 `text` 以 `http` 开头，弹个「打开链接」按钮即可。

---

## 五、不想自己编译？现成替代方案

如果你只是想「点一下悬浮球就扫当前屏幕二维码」，**不想自己编译**，直接装这个开源 APP：

- **CircleToSearch**（AKS-Labs，GPL-3.0，含 QR 识别 + 悬浮球 + 本地 OCR）
  - F-Droid 下载：https://f-droid.org/en/packages/com.akslabs.circletosearch/
  - GitHub Releases：https://github.com/AKS-Labs/CircleToSearch/releases

它和你要的区别：它是「点一下悬浮球 → 圈选 → 识别」，不是本项目这种「屏幕一出现二维码自动弹」。如果只是偶尔用一次，装它最省事；如果要**自动连续监听**，用本项目自己编译。
