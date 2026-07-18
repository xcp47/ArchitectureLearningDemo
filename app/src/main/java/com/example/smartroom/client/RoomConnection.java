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
    public static final int DISCONNECTED = 0;
    public static final int CONNECTING = 1;
    public static final int CONNECTED = 2;

    public interface Listener {
        void onConnectionStateChanged(int state, String detail);

        void onRouterReady(IServiceRouter router);

        void onRouterLost();
    }

    private static final long CONNECT_TIMEOUT_MS = 2000L;
    private static final long QUICK_RETRY_MS = 500L;
    private static final long SLOW_RETRY_MS = 3000L;
    private static final int QUICK_RETRY_LIMIT = 5;

    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private final CopyOnWriteArrayList<Listener> listeners = new CopyOnWriteArrayList<>();
    private volatile int state = DISCONNECTED;
    private Context appContext;
    private IServiceRouter router;
    private boolean bound;
    private boolean manualStop = true;
    private int retryCount;

    private RoomConnection() {
    }

    private static final class Holder {
        private static final RoomConnection INSTANCE = new RoomConnection();
    }

    public static RoomConnection getInstance() {
        return Holder.INSTANCE;
    }

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

    private void connect() {
        if (manualStop || appContext == null || state == CONNECTING || state == CONNECTED) {
            return;
        }
        mainHandler.removeCallbacks(retryAction);
        updateState(CONNECTING, "正在绑定一级 Router Binder…");
        Intent intent = new Intent(appContext, RoomCoreService.class);
        try {
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

    private final Runnable connectTimeout = () -> {
        if (state == CONNECTING) {
            scheduleRetry("等待 ServiceConnection 超时");
        }
    };

    private final Runnable retryAction = this::connect;

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

    private final ServiceConnection serviceConnection = new ServiceConnection() {
        @Override
        public void onServiceConnected(ComponentName name, IBinder service) {
            mainHandler.removeCallbacks(connectTimeout);
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
