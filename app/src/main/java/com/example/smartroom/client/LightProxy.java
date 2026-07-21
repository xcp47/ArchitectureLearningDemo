package com.example.smartroom.client;

import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.RemoteException;

import com.example.smartroom.ipc.ILightCallback;
import com.example.smartroom.ipc.ILightService;
import com.example.smartroom.ipc.ResultCode;
import com.example.smartroom.ipc.ServiceNames;

import java.util.concurrent.CopyOnWriteArrayList;

/** UI 眼中的“本地灯光对象”；AIDL、IBinder 和 RemoteException 全被封装在这里。 */
public final class LightProxy extends BaseFeatureProxy<ILightService> {
    /**
     * 给普通 Java/UI 层使用的监听器。
     * 它与 AIDL Callback 分开，UI 因而不需要依赖 Binder 类型。
     */
    public interface Listener {
        void onLightAvailabilityChanged(boolean available);

        void onLightChanged(boolean enabled, int brightness, int servicePid);
    }

    // AIDL Callback 可能运行在 Binder 线程，更新 View 前必须切回主线程。
    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private final CopyOnWriteArrayList<Listener> listeners = new CopyOnWriteArrayList<>();

    // 这是客户端交给服务端的“回信地址”，服务端完成操作后会调用它。
    private final ILightCallback callback = new ILightCallback.Stub() {
        @Override
        public void onLightChanged(boolean enabled, int brightness, int servicePid) {
            // Stub 回调不保证在 UI 线程，因此只负责转发，不直接操作页面。
            mainHandler.post(() -> {
                for (Listener listener : listeners) {
                    listener.onLightChanged(enabled, brightness, servicePid);
                }
            });
        }
    };

    private LightProxy() {
        super(ServiceNames.LIGHT);
    }

    // 懒加载单例：整个进程共享一个灯光代理和一份远程状态。
    private static final class Holder {
        private static final LightProxy INSTANCE = new LightProxy();
    }

    public static LightProxy getInstance() {
        return Holder.INSTANCE;
    }

    public void addListener(Listener listener) {
        listeners.addIfAbsent(listener);
    }

    public void removeListener(Listener listener) {
        listeners.remove(listener);
    }

    /**
     * 对 UI 暴露的普通 Java 方法。
     * 返回 ACCEPTED 只表示命令已到服务端，最终开关状态看 onLightChanged。
     */
    public int setEnabled(boolean enabled) {
        ILightService service = remote();

        // 二级 Binder 还没拿到时，不发起远程调用，直接返回明确错误码。
        if (service == null) {
            return ResultCode.NOT_CONNECTED;
        }
        try {
            return service.setEnabled(enabled);
        } catch (RemoteException exception) {
            remoteCallFailed(exception);
            return ResultCode.REMOTE_ERROR;
        }
    }

    /** 请求设置亮度；参数是否合法由远端 LightBinder 最终校验。 */
    public int setBrightness(int brightness) {
        ILightService service = remote();
        if (service == null) {
            return ResultCode.NOT_CONNECTED;
        }
        try {
            return service.setBrightness(brightness);
        } catch (RemoteException exception) {
            remoteCallFailed(exception);
            return ResultCode.REMOTE_ERROR;
        }
    }

    @Override
    protected ILightService asInterface(IBinder binder) {
        // AIDL 生成的 asInterface 会返回本地实现或跨进程代理对象。
        return ILightService.Stub.asInterface(binder);
    }

    @Override
    protected void registerRemoteCallback(ILightService remote) throws RemoteException {
        remote.registerCallback(callback);
    }

    @Override
    protected void unregisterRemoteCallback(ILightService remote) throws RemoteException {
        remote.unregisterCallback(callback);
    }

    @Override
    protected void onAvailabilityChanged(boolean available) {
        mainHandler.post(() -> {
            for (Listener listener : listeners) {
                listener.onLightAvailabilityChanged(available);
            }
        });
    }
}
