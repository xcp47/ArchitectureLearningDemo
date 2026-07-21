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
    public interface Listener {
        void onTimerAvailabilityChanged(boolean available);

        void onTimerChanged(int remainingSeconds, int totalSeconds, boolean running, int servicePid);
    }

    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private final CopyOnWriteArrayList<Listener> listeners = new CopyOnWriteArrayList<>();

    private final ITimerCallback callback = new ITimerCallback.Stub() {
        @Override
        public void onTimerChanged(int remainingSeconds, int totalSeconds, boolean running,
                                   int servicePid) {
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
