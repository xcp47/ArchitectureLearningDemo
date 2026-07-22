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

/**
 * <b>计时器业务的本地代理（Proxy）。</b>
 *
 * <p>结构与 {@link LightProxy} 完全相同，证明 {@link BaseFeatureProxy} 模板可以复用。
 * 它把 AIDL 接口 {@link ITimerService} 封装成简单的 Java 方法：
 * {@link #startTimer(int)} 和 {@link #cancelTimer()}。</p>
 *
 * <p><b>和 LightProxy 的区别：</b>唯一的区别是底层 AIDL 接口不同。
 * LightProxy 操作的是 {@link com.example.smartroom.ipc.ILightService}，
 * TimerProxy 操作的是 {@link ITimerService}。
 * 模板方法模式让这两个 Proxy 的连接管理和错误处理逻辑完全复用。</p>
 *
 * @see com.example.smartroom.service.RoomCoreService.TimerBinder 服务端的对应实现
 * @see LightProxy 结构相同的灯光代理
 */
public final class TimerProxy extends BaseFeatureProxy<ITimerService> {

    /**
     * UI 层使用的纯 Java 监听器。
     * UI 实现这个接口即可接收倒计时状态更新，无需接触 AIDL。
     */
    public interface Listener {
        /** 计时业务可用/不可用时触发 */
        void onTimerAvailabilityChanged(boolean available);

        /**
         * 服务端每秒推送一次的倒计时状态。
         *
         * @param remainingSeconds 剩余秒数
         * @param totalSeconds 本次倒计时总秒数
         * @param running 是否正在计时
         * @param servicePid 服务端进程 ID
         */
        void onTimerChanged(int remainingSeconds, int totalSeconds, boolean running, int servicePid);
    }

    //============================================================
    // 成员变量
    //============================================================

    // 所有 Listener 最终都在主线程收到通知，可以安全更新 TextView/ProgressBar
    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private final CopyOnWriteArrayList<Listener> listeners = new CopyOnWriteArrayList<>();

    /**
     * AIDL 回调对象。服务端每秒调用一次 {@link #onTimerChanged}，
     * 把最新的倒计时状态从远端进程推回来。
     */
    private final ITimerCallback callback = new ITimerCallback.Stub() {
        @Override
        public void onTimerChanged(int remainingSeconds, int totalSeconds, boolean running,
                                   int servicePid) {
            // Binder 线程收到回调 → 切到主线程 → 广播给所有 Listener
            mainHandler.post(() -> {
                for (Listener listener : listeners) {
                    listener.onTimerChanged(remainingSeconds, totalSeconds, running, servicePid);
                }
            });
        }
    };

    //============================================================
    // 构造方法 & 单例
    //============================================================

    private TimerProxy() {
        super(ServiceNames.TIMER);
    }

    /** 静态内部类实现的线程安全懒加载单例 */
    private static final class Holder {
        private static final TimerProxy INSTANCE = new TimerProxy();
    }

    public static TimerProxy getInstance() {
        return Holder.INSTANCE;
    }

    //============================================================
    // 监听者管理
    //============================================================

    public void addListener(Listener listener) {
        listeners.addIfAbsent(listener);
    }

    public void removeListener(Listener listener) {
        listeners.remove(listener);
    }

    //============================================================
    // 对 UI 暴露的业务方法
    //============================================================

    /**
     * 请求服务端启动倒计时。
     *
     * <p><b>注意：</b>同步返回只表示是否受理；
     * 真正的剩余时间变化由 {@link Listener#onTimerChanged} 每秒回调推送。</p>
     *
     * @param seconds 倒计时秒数（服务端验证合法性，如 1～3600）
     * @return 同步返回码
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

    /**
     * 请求服务端取消倒计时。
     * 最终的 "00 / XX" 和运行状态仍以 Callback 确认为准。
     *
     * @return 同步返回码
     */
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

    //============================================================
    // BaseFeatureProxy 抽象方法实现
    //============================================================

    @Override
    protected ITimerService asInterface(IBinder binder) {
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
