package com.example.smartroom.client;

import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.ServiceConnection;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;

import com.example.smartroom.ipc.IServiceRouter;
import com.example.smartroom.service.RoomCoreService;

import java.util.concurrent.CopyOnWriteArrayList;

/**
 * <b>顶层 Service 的统一连接状态机。</b>
 *
 * <p><b>职责：</b></p>
 * <ul>
 *   <li>负责与 {@link RoomCoreService} 建立/断开连接（调用 bindService/unbindService）</li>
 *   <li>只关心一级 IServiceRouter Binder，不关心灯光或计时器等具体业务</li>
 *   <li>实现了自动重连与退避策略（前 5 次 500ms 快速重试，之后 3s 慢速重试）</li>
 * </ul>
 *
 * <p><b>为什么是单例？</b>
 * 整个应用只需要一个连接管理器。如果每个 Activity 各自绑定一次 Service，
 * 会导致多个 ServiceConnection 回调互相干扰，状态难以统一管理。</p>
 *
 * <p><b>生命周期：</b>
 * <pre>
 * DISCONNECTED → CONNECTING → CONNECTED（正常连接）
 * CONNECTED → DISCONNECTED（断开或异常） → CONNECTING（自动重连）
 * </pre>
 * stop() 是主动断开，进入 DISCONNECTED 后<b>不会</b>自动重连。
 * </p>
 *
 * @see BaseFeatureProxy 业务 Proxy 通过监听这个类的状态来获取二级 Binder
 */
public final class RoomConnection {

    //============================================================
    // 状态常量
    //============================================================

    /** 未连接：还没开始 bindService 或已主动断开 */
    public static final int DISCONNECTED = 0;

    /** 连接中：已调用 bindService，正在等待 ServiceConnection 回调 */
    public static final int CONNECTING = 1;

    /** 已连接：一级 Router Binder 已就绪，可以通过它获取二级 Binder */
    public static final int CONNECTED = 2;

    //============================================================
    // 监听者接口
    //============================================================

    /**
     * 连接状态观察者接口。
     *
     * <p>三个方法的调用顺序：
     * <ol>
     *   <li>{@link #onConnectionStateChanged} —— 状态机变化通知（可能多次调用）</li>
     *   <li>{@link #onRouterReady} —— Router 就绪时调用（此时可以获取二级 Binder）</li>
     *   <li>{@link #onRouterLost} —— Router 失效时调用（必须清空二级 Binder）</li>
     * </ol>
     * </p>
     */
    public interface Listener {
        /**
         * 连接状态变化（DISCONNECTED / CONNECTING / CONNECTED）。
         * detail 参数说明了变化原因，可用于日志和 UI 提示。
         */
        void onConnectionStateChanged(int state, String detail);

        /**
         * 一级 Router Binder 已可用。
         * 业务 Proxy 收到这个通知后应当立即调用 router.getService() 获取二级 Binder。
         */
        void onRouterReady(IServiceRouter router);

        /**
         * Router 已失效（远端进程死亡或解绑）。
         * 持有二级 Binder 引用的地方必须将其置为 null，
         * 否则可能会向已经失效的 Binder 发送请求导致异常。
         */
        void onRouterLost();
    }

    //============================================================
    // 重连策略常量
    //============================================================

    /** bindService 超时时间：2 秒后还没收到回调就视为连接失败 */
    private static final long CONNECT_TIMEOUT_MS = 2000L;

    /** 前 5 次的快速重试间隔 */
    private static final long QUICK_RETRY_MS = 500L;

    /** 超过 5 次后的慢速重试间隔 */
    private static final long SLOW_RETRY_MS = 3000L;

    /** 快速重试次数上限 */
    private static final int QUICK_RETRY_LIMIT = 5;

    //============================================================
    // 成员变量
    //============================================================

    /**
     * 所有连接状态的修改都在主线程中执行，
     * 避免多个 Binder/回调线程同时修改状态造成竞态条件。
     */
    private final Handler mainHandler = new Handler(Looper.getMainLooper());

    /**
     * 线程安全的监听者列表。
     * 遍历通知时允许其他线程添加/删除监听者而不抛异常。
     */
    private final CopyOnWriteArrayList<Listener> listeners = new CopyOnWriteArrayList<>();

    /** 当前连接状态（volatile 确保其它线程能读到最新值） */
    private volatile int state = DISCONNECTED;

    /** 应用级别的 Context（防止持有 Activity 引用导致内存泄漏） */
    private Context appContext;

    /** 一级 Router Binder 的强类型接口 */
    private IServiceRouter router;

    /** bindService 是否成功，防止重复或非法 unbindService */
    private boolean bound;

    /**
     * true 表示用户主动停止连接。
     * 主动停止后不应该自动重连，直到用户再次调用 start()。
     */
    private boolean manualStop = true;

    /** 当前已经重试的次数 */
    private int retryCount;

    //============================================================
    // 构造方法 & 单例
    //============================================================

    private RoomConnection() {
    }

    /**
     * 静态内部类实现的线程安全懒加载单例。
     *
     * <p>优点：
     * <ul>
     *   <li>第一次调用 getInstance() 时才创建对象（懒加载）</li>
     *   <li>Java 类加载机制保证了线程安全，不需要 synchronized</li>
     * </ul>
     */
    private static final class Holder {
        private static final RoomConnection INSTANCE = new RoomConnection();
    }

    public static RoomConnection getInstance() {
        return Holder.INSTANCE;
    }

    //============================================================
    // 监听者管理
    //============================================================

    /**
     * 添加观察者，并立即补发一次当前状态。
     *
     * <p>"补发"机制很重要：如果页面在连接已经建立后才注册监听，
     * 它不会知道自己错过了连接成功的事件。补发可以确保新注册的监听者
     * 立刻就获取到当前的最新状态。</p>
     */
    public void addListener(Listener listener) {
        if (listener == null) {
            return;
        }
        listeners.addIfAbsent(listener);
        // 补发当前状态，确保新注册的监听者不会错过已发生的事件
        mainHandler.post(() -> {
            if (!listeners.contains(listener)) {
                return;
            }
            listener.onConnectionStateChanged(state, stateText(state));
            if (router != null) {
                listener.onRouterReady(router);
            }
        });
    }

    public void removeListener(Listener listener) {
        listeners.remove(listener);
    }

    //============================================================
    // 核心操作：启动 / 停止 / 重连
    //============================================================

    /**
     * 开始连接服务端。
     * 使用 applicationContext 避免把 Activity 长期保存在单例中。
     *
     * @param context 任意 Context（内部会自动取 applicationContext）
     */
    public void start(Context context) {
        if (context == null) {
            throw new IllegalArgumentException("context == null");
        }
        Context applicationContext = context.getApplicationContext();
        mainHandler.post(() -> {
            appContext = applicationContext;
            manualStop = false;  // 清除"主动停止"标记，允许自动重连
            connect();
        });
    }

    /**
     * 用户主动断开连接。
     *
     * <p>除了 unbindService，还要删除所有待执行的超时和重试任务，
     * 否则界面刚点击"断开"按钮就可能被自动连接回来。</p>
     */
    public void stop() {
        mainHandler.post(() -> {
            manualStop = true;
            retryCount = 0;
            // 移除所有待执行的回调任务
            mainHandler.removeCallbacks(connectTimeout);
            mainHandler.removeCallbacks(retryAction);
            router = null;
            notifyRouterLost();
            safeUnbind();
            updateState(DISCONNECTED, "已主动断开（不会自动重连）");
        });
    }

    /**
     * 业务 Proxy 在捕获到 RemoteException 时调用此方法，
     * 请求顶层连接层重新建立连接。
     *
     * <p>这个方法是包级访问权限（default），只在 client 包内使用。
     * 外部代码不需要也不应该调用它。</p>
     */
    void reconnectFromRemoteFailure(String detail) {
        mainHandler.post(() -> {
            if (!manualStop) {
                scheduleRetry("业务 Binder 异常：" + detail);
            }
        });
    }

    //============================================================
    // 私有方法：连接、重连、解绑
    //============================================================

    /**
     * 真正执行 bindService。
     * 前面的条件判断防止无意义的重复绑定。
     */
    private void connect() {
        if (manualStop || appContext == null || state == CONNECTING || state == CONNECTED) {
            return;
        }
        mainHandler.removeCallbacks(retryAction);
        updateState(CONNECTING, "正在绑定一级 Router Binder…");

        // 使用显式 Intent 直接指定 RoomCoreService，
        // 避免系统匹配到其他 Service。
        Intent intent = new Intent(appContext, RoomCoreService.class);
        try {
            // BIND_AUTO_CREATE：如果没有 Service 实例，系统自动创建
            bound = appContext.bindService(intent, serviceConnection, Context.BIND_AUTO_CREATE);
        } catch (RuntimeException exception) {
            // 在某些异常情况下（如应用正在被销毁），bindService 可能抛异常
            bound = false;
            scheduleRetry("bindService 异常：" + exception.getClass().getSimpleName());
            return;
        }
        if (!bound) {
            // bindService 返回 false 通常意味着系统找不到对应的 Service
            scheduleRetry("bindService 返回 false");
            return;
        }
        // 设置超时保护：防止 ServiceConnection 回调迟迟不来
        mainHandler.postDelayed(connectTimeout, CONNECT_TIMEOUT_MS);
    }

    /**
     * bindService 超时保护。
     * bindService 返回 true 不代表回调一定会及时到达，
     * 因此需要独立的超时机制来自动触发重连。
     */
    private final Runnable connectTimeout = () -> {
        if (state == CONNECTING) {
            scheduleRetry("等待 ServiceConnection 超时");
        }
    };

    /** 重连任务：直接调用 connect() */
    private final Runnable retryAction = this::connect;

    /**
     * 统一的失败出口：
     * 清理旧 Binder → 通知上层不可用 → 根据重试次数安排下一次连接。
     *
     * <p>退避策略：前 5 次用 500ms 快速重试，之后用 3s 慢速重试。
     * 这样在临时性故障时可以快速恢复，
     * 而持续故障时也不会过度消耗 CPU 和电量。</p>
     */
    private void scheduleRetry(String reason) {
        mainHandler.removeCallbacks(connectTimeout);
        router = null;
        notifyRouterLost();
        safeUnbind();
        updateState(DISCONNECTED, reason);
        if (manualStop) {
            return;
        }
        retryCount++;
        long delay = retryCount <= QUICK_RETRY_LIMIT ? QUICK_RETRY_MS : SLOW_RETRY_MS;
        updateState(DISCONNECTED, reason + "；" + delay + "ms 后重试");
        mainHandler.removeCallbacks(retryAction);
        mainHandler.postDelayed(retryAction, delay);
    }

    /**
     * 安全解绑 Service。
     * 捕获 RuntimeException 防止 Service 已死亡时解绑抛异常。
     */
    private void safeUnbind() {
        if (!bound || appContext == null) {
            bound = false;
            return;
        }
        try {
            appContext.unbindService(serviceConnection);
        } catch (RuntimeException ignored) {
            // Service 可能已经死亡，忽略解绑异常
        }
        bound = false;
    }

    //============================================================
    // ServiceConnection（Android 系统通过这个回调通知绑定结果）
    //============================================================

    /**
     * Android 系统通过 ServiceConnection 把绑定结果通知回来。
     *
     * <p><b>注意：</b>这些回调描述的是<b>一级 Service 连接</b>，
     * 不是具体灯光或计时业务的结果。
     * 业务状态要通过二级 Binder 的 Callback 获取。</p>
     */
    private final ServiceConnection serviceConnection = new ServiceConnection() {

        @Override
        public void onServiceConnected(ComponentName name, IBinder service) {
            // Service 连接成功后，移除超时保护
            mainHandler.removeCallbacks(connectTimeout);

            // 把原始 IBinder 包装成可以调用 getService() 的 IServiceRouter 接口
            IServiceRouter connectedRouter = IServiceRouter.Stub.asInterface(service);
            if (connectedRouter == null) {
                scheduleRetry("一级 Binder 为空");
                return;
            }
            router = connectedRouter;
            retryCount = 0;  // 连接成功，重置重试计数
            updateState(CONNECTED, "一级 Router Binder 已连接");
            for (Listener listener : listeners) {
                listener.onRouterReady(connectedRouter);
            }
        }

        @Override
        public void onServiceDisconnected(ComponentName name) {
            // 远端进程意外终止时会收到此回调
            scheduleRetry("远端进程已断开");
        }

        @Override
        public void onBindingDied(ComponentName name) {
            // Binder 死亡，比 onServiceDisconnected 更严重
            scheduleRetry("绑定已失效");
        }

        @Override
        public void onNullBinding(ComponentName name) {
            // Service 的 onBind() 返回了 null
            scheduleRetry("Service 返回了空 Binder");
        }
    };

    //============================================================
    // 通知辅助方法
    //============================================================

    /** 通知所有监听者：Router 已丢失 */
    private void notifyRouterLost() {
        for (Listener listener : listeners) {
            listener.onRouterLost();
        }
    }

    /** 更新状态并通知所有监听者 */
    private void updateState(int newState, String detail) {
        state = newState;
        for (Listener listener : listeners) {
            listener.onConnectionStateChanged(newState, detail);
        }
    }

    /** 把状态常量转成中文描述 */
    private static String stateText(int state) {
        if (state == CONNECTED) {
            return "已连接";
        }
        if (state == CONNECTING) {
            return "连接中";
        }
        return "未连接";
    }
}
