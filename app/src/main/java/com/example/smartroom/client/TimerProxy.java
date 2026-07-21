package com.example.smartroom.client;

import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.RemoteException;

import com.example.smartroom.ipc.ITimerCallback;
import com.example.smartroom.ipc.ITimerService;
import com.example.smartroom.ipc.ResultCode;
import com.example.smartroom.ipc.ServiceNames;

import java.util.concurrent.CopyOnWriteArrayList;

/** 专注计时器的本地代理，结构与 LightProxy 相同，证明模板可以复用。 */
public final class TimerProxy extends BaseFeatureProxy<ITimerService> {
    /** UI 只观察这个普通 Listener，不直接实现 AIDL 接口。 */
    public interface Listener {
        void onTimerAvailabilityChanged(boolean available);

        void onTimerChanged(int remainingSeconds, int totalSeconds, boolean running, int servicePid);
    }

    // 所有 Listener 最终都在主线程收到通知，可以安全更新 TextView/ProgressBar。
    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private final CopyOnWriteArrayList<Listener> listeners = new CopyOnWriteArrayList<>();

    // 服务端每秒调用这个 AIDL Callback，把最新倒计时状态推回来。
    private final ITimerCallback callback = new ITimerCallback.Stub() {
        @Override
        public void onTimerChanged(int remainingSeconds, int totalSeconds, boolean running,
                                   int servicePid) {
            // 先切到主线程，再把同一份状态广播给所有本地 Listener。
            mainHandler.post(() -> {
                for (Listener listener : listeners) {
                    listener.onTimerChanged(remainingSeconds, totalSeconds, running, servicePid);
                }
            });
        }
    };

    private TimerProxy() {
        super(ServiceNames.TIMER);
    }

    // 每个进程只有一个 TimerProxy，避免重复注册同一业务的 Callback。
    private static final class Holder {
        private static final TimerProxy INSTANCE = new TimerProxy();
    }

    public static TimerProxy getInstance() {
        return Holder.INSTANCE;
    }

    public void addListener(Listener listener) {
        listeners.addIfAbsent(listener);
    }

    public void removeListener(Listener listener) {
        listeners.remove(listener);
    }

    /**
     * 请求服务端启动倒计时。
     * 同步返回只表示是否受理；剩余时间由 onTimerChanged 每秒更新。
     */
    public int startTimer(int seconds) {
        ITimerService service = remote();
        if (service == null) {
            return ResultCode.NOT_CONNECTED;
        }
        try {
            return service.start(seconds);
        } catch (RemoteException exception) {
            remoteCallFailed(exception);
            return ResultCode.REMOTE_ERROR;
        }
    }

    /** 请求服务端取消倒计时，最终的 00 / XX 仍由 Callback 确认。 */
    public int cancelTimer() {
        ITimerService service = remote();
        if (service == null) {
            return ResultCode.NOT_CONNECTED;
        }
        try {
            return service.cancel();
        } catch (RemoteException exception) {
            remoteCallFailed(exception);
            return ResultCode.REMOTE_ERROR;
        }
    }

    @Override
    protected ITimerService asInterface(IBinder binder) {
        // 把原始 Binder 转为有 start/cancel 方法的强类型接口。
        return ITimerService.Stub.asInterface(binder);
    }

    @Override
    protected void registerRemoteCallback(ITimerService remote) throws RemoteException {
        remote.registerCallback(callback);
    }

    @Override
    protected void unregisterRemoteCallback(ITimerService remote) throws RemoteException {
        remote.unregisterCallback(callback);
    }

    @Override
    protected void onAvailabilityChanged(boolean available) {
        mainHandler.post(() -> {
            for (Listener listener : listeners) {
                listener.onTimerAvailabilityChanged(available);
            }
        });
    }
}
