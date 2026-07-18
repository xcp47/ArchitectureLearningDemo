# ArchitectureLearningDemo

这是一个能直接用 Android Studio 打开的“小型智能自习室”项目。它把原车机语音工程最关键的架构缩到一个 APK 中：

- 主进程：`MainActivity`，负责界面；
- 远端进程：`RoomCoreService`，负责灯光与专注计时；
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

```powershell
# JAVA_HOME 请指向本机的 JDK 17 或 JDK 21
$env:JAVA_HOME='C:\path\to\jdk-17'
.\gradlew.bat :app:assembleDebug
```

Debug APK 位于：

```text
app/build/outputs/apk/debug/app-debug.apk
```

## 运行时观察什么

打开 App 后，页面顶部会显示两个不同 PID：

```text
UI 进程 PID=1234；远端 :room_core PID=1260
```

操作灯光或计时器，日志会先出现：

```text
同步返回 ← LightProxy.setEnabled(true)：0（请求已受理）
```

约 250ms 后再出现：

```text
异步回调 → onLightChanged(true, 50)，来自 PID=1260
```

这正是原工程里“业务 AIDL 同步返回 + Listener 异步给最终结果”的缩小版。

更详细的阅读路线见 [docs/01_运行与阅读指南.md](docs/01_运行与阅读指南.md)，原工程映射见 [docs/02_与原项目架构映射.md](docs/02_与原项目架构映射.md)。
