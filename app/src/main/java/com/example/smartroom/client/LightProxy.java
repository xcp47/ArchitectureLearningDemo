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
    public interface Listener {
        void onLightAvailabilityChanged(boolean available);

        void onLightChanged(boolean enabled, int brightness, int servicePid);
    }

    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private final CopyOnWriteArrayList<Listener> listeners = new CopyOnWriteArrayList<>();

    private final ILightCallback callback = new ILightCallback.Stub() {
        @Override
        public void onLightChanged(boolean enabled, int brightness, int servicePid) {
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

    public int setEnabled(boolean enabled) {
        ILightService service = remote();
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
