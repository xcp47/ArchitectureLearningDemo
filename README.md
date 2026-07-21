# ArchitectureLearningDemo

这是一个能直接用 Android Studio 打开的“小型智能自习室”项目。它把原车机语音工程最关键的架构缩到一个 APK 中，同时提供控制端和被控端模拟页面：

- 主进程：`MainActivity`，作为控制端；
- 远端进程：`RoomCoreService` 与 `DevicePreviewActivity`，作为被控端；
- 一级 Binder：`IServiceRouter`，像总机一样按服务名路由；
- 二级 Binder：`ILightService`、`ITimerService`，各管一个业务领域；
- 客户端代理：`LightProxy`、`TimerProxy`，让 UI 不接触 AIDL 细节；
- 统一连接：`RoomConnection`，管理绑定、超时、主动断开和快慢重试；
- 模板方法：`BaseFeatureProxy`，固定“取 Binder → 转接口 → 注册回调 → 清理”的流程；
- 异步反馈：同步返回只代表请求已受理，最终状态由 Callback 返回。

## 直接运行

1. Android Studio 选择 **Open**，打开整个 `ArchitectureLearningDemo` 文件夹。
2. Gradle JDK 选择 17 或 21，不要使用 JDK 25/26。
3. 等待 Gradle Sync 完成，选择 `app` 配置。
4. 在 API 26 及以上的模拟器或手机上点击 Run。

命令行构建：

~~~powershell
# JAVA_HOME 请指向本机的 JDK 17 或 JDK 21
$env:JAVA_HOME='C:\path\to\jdk-17'
.\gradlew.bat :app:assembleDebug
~~~

Debug APK 位于：

~~~text
app/build/outputs/apk/debug/app-debug.apk
~~~

## 推荐体验顺序

1. 看顶部 PID，确认控制端和 `:room_core` 位于不同进程。
2. 开灯或关灯，比较同步返回和 250ms 后异步回调的顺序。
3. 启动 10 秒或 30 秒倒计时。
4. 点击“查看被控端效果”。
5. 在被控端模拟屏观察明亮/暗黑模式，以及方框式 `XX / XX` 倒计时。
6. 点击“返回控制端”继续发送控制命令。

被控端页面只根据 `RoomCoreService` 的最终 Callback 更新，不会在控制按钮点击时提前伪造效果。

## 运行时观察什么

控制端日志先出现：

~~~text
同步返回 ← LightProxy.setEnabled(true)：0（请求已受理）
~~~

约 250ms 后出现：

~~~text
异步回调 → onLightChanged(true, 50)，来自 PID=1260
~~~

计时回调同时传递“剩余秒数”和“总秒数”，因此被控端页面即使中途打开，也可以显示类似 `08 / 10` 的完整倒计时。

## 文档

- [运行与阅读指南](docs/01_运行与阅读指南.md)
- [与原项目架构映射](docs/02_与原项目架构映射.md)
- [原工程阅读摘要](docs/03_原工程阅读摘要.md)
- [项目详细介绍](docs/04_项目详细介绍.md)
- [控制端与被控端说明](docs/05_控制端与被控端说明.md)
- [单 APK 双进程与双 APK 对比](docs/06_单APK双进程与双APK对比.md)