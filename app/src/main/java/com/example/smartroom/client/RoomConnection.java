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
 * 顶层 Service 的统一连接状态机。
 *
 * <p>它只认识 IServiceRouter，不认识灯光或计时器。前 5 次断线以 500ms 快速
 * 重试，之后改为 3s 慢速重试；stop() 是主动断开，不会偷偷重连。</p>
 */
public final class RoomConnection {
    // 连接状态只描述一级 Router Binder，不代表 LIGHT/TIMER 一定已经可用。
    public static final int DISCONNECTED = 0;
    public static final int CONNECTING = 1;
    public static final int CONNECTED = 2;

    /**
     * 连接观察者。MainActivity 和每个业务 Proxy 都会注册一个 Listener。
     */
    public interface Listener {
        /** 一级连接状态变化，detail 用来解释变化原因。 */
        void onConnectionStateChanged(int state, String detail);

        /** 一级 Router 已可用，业务 Proxy 可以开始索取二级 Binder。 */
        void onRouterReady(IServiceRouter router);

        /** Router 已失效，持有它的业务 Proxy 必须立即清空远程对象。 */
        void onRouterLost();
    }

    private static final long CONNECT_TIMEOUT_MS = 2000L;
    private static final long QUICK_RETRY_MS = 500L;
    private static final long SLOW_RETRY_MS = 3000L;
    private static final int QUICK_RETRY_LIMIT = 5;

    // 所有连接状态都在主线程修改，避免多个 Binder/回调线程同时改状态。
    private final Handler mainHandler = new Handler(Looper.getMainLooper());

    // 遍历通知的同时允许其他地方添加/删除监听者，适合监听者数量较少的场景。
    private final CopyOnWriteArrayList<Listener> listeners = new CopyOnWriteArrayList<>();

    private volatile int state = DISCONNECTED;
    private Context appContext;
    private IServiceRouter router;

    // bound 记录 bindService 是否成功，防止重复或非法 unbindService。
    private boolean bound;

    // true 表示用户主动停止。主动停止后不应该自动重连。
    private boolean manualStop = true;
    private int retryCount;

    private RoomConnection() {
    }

    /**
     * 静态内部类单例：第一次调用 getInstance() 时才创建对象，
     * Java 类加载机制还能保证线程安全。
     */
    private static final class Holder {
        private static final RoomConnection INSTANCE = new RoomConnection();
    }

    public static RoomConnection getInstance() {
        return Holder.INSTANCE;
    }

    /**
     * 添加观察者，并马上补发一次当前状态。
     * “补发”可以避免页面后注册监听时一直显示默认的“未连接”。
     */
    public void addListener(Listener listener) {
        if (listener == null) {
            return;
        }
        listeners.addIfAbsent(listener);
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

    /** 开始连接；applicationContext 可以避免把 Activity 长期保存在单例里。 */
    public void start(Context context) {
        if (context == null) {
            throw new IllegalArgumentException("context == null");
        }
        Context applicationContext = context.getApplicationContext();
        mainHandler.post(() -> {
            appContext = applicationContext;
            manualStop = false;
            connect();
        });
    }

    /**
     * 用户主动断开。除了 unbind，还要删除超时与重试任务，
     * 否则界面刚点击“断开”就可能被自动连接回来。
     */
    public void stop() {
        mainHandler.post(() -> {
            manualStop = true;
            retryCount = 0;
            mainHandler.removeCallbacks(connectTimeout);
            mainHandler.removeCallbacks(retryAction);
            router = null;
            notifyRouterLost();
            safeUnbind();
            updateState(DISCONNECTED, "已主动断开（不会自动重连）");
        });
    }

    /** 业务 Proxy 捕获到 RemoteException 时，请求顶层连接重新建立。 */
    void reconnectFromRemoteFailure(String detail) {
        mainHandler.post(() -> {
            if (!manualStop) {
                scheduleRetry("业务 Binder 异常：" + detail);
            }
        });
    }

    /** 真正执行 bindService；前面的条件用于防止无意义的重复绑定。 */
    private void connect() {
        if (manualStop || appContext == null || state == CONNECTING || state == CONNECTED) {
            return;
        }
        mainHandler.removeCallbacks(retryAction);
        updateState(CONNECTING, "正在绑定一级 Router Binder…");
        // 显式 Intent 直接指定 RoomCoreService，避免系统匹配到错误的 Service。
        Intent intent = new Intent(appContext, RoomCoreService.class);
        try {
            // BIND_AUTO_CREATE 表示没有 Service 实例时由系统自动创建。
            bound = appContext.bindService(intent, serviceConnection, Context.BIND_AUTO_CREATE);
        } catch (RuntimeException exception) {
            bound = false;
            scheduleRetry("bindService 异常：" + exception.getClass().getSimpleName());
            return;
        }
        if (!bound) {
            scheduleRetry("bindService 返回 false");
            return;
        }
        mainHandler.postDelayed(connectTimeout, CONNECT_TIMEOUT_MS);
    }

    // bindService 返回 true 不代表回调一定会及时到达，因此还需要独立超时保护。
    private final Runnable connectTimeout = () -> {
        if (state == CONNECTING) {
            scheduleRetry("等待 ServiceConnection 超时");
        }
    };

    private final Runnable retryAction = this::connect;

    /**
     * 统一的失败出口：清理旧 Binder、通知上层不可用，再安排下一次连接。
     * 前五次快速重试，之后降低频率，避免长期高频消耗资源。
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

    private void safeUnbind() {
        if (!bound || appContext == null) {
            bound = false;
            return;
        }
        try {
            appContext.unbindService(serviceConnection);
        } catch (RuntimeException ignored) {
            // Service 已经死亡时，解绑可能抛异常；状态仍应回到未绑定。
        }
        bound = false;
    }

    /**
     * Android 系统通过 ServiceConnection 把绑定结果通知回来。
     * 这些回调描述的是一级 Service 连接，不是具体灯光/计时业务结果。
     */
    private final ServiceConnection serviceConnection = new ServiceConnection() {
        @Override
        public void onServiceConnected(ComponentName name, IBinder service) {
            mainHandler.removeCallbacks(connectTimeout);
            // 把原始 IBinder 包装成可以调用 getService(...) 的强类型 AIDL 接口。
            IServiceRouter connectedRouter = IServiceRouter.Stub.asInterface(service);
            if (connectedRouter == null) {
                scheduleRetry("一级 Binder 为空");
                return;
            }
            router = connectedRouter;
            retryCount = 0;
            updateState(CONNECTED, "一级 Router Binder 已连接");
            for (Listener listener : listeners) {
                listener.onRouterReady(connectedRouter);
            }
        }

        @Override
        public void onServiceDisconnected(ComponentName name) {
            scheduleRetry("远端进程已断开");
        }

        @Override
        public void onBindingDied(ComponentName name) {
            scheduleRetry("绑定已失效");
        }

        @Override
        public void onNullBinding(ComponentName name) {
            scheduleRetry("Service 返回了空 Binder");
        }
    };

    private void notifyRouterLost() {
        for (Listener listener : listeners) {
            listener.onRouterLost();
        }
    }

    private void updateState(int newState, String detail) {
        state = newState;
        for (Listener listener : listeners) {
            listener.onConnectionStateChanged(newState, detail);
        }
    }

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
