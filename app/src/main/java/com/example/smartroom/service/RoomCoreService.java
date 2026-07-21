package com.example.smartroom.service;

import android.app.Service;
import android.content.Intent;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.Process;
import android.os.RemoteCallbackList;
import android.os.RemoteException;

import com.example.smartroom.ipc.ILightCallback;
import com.example.smartroom.ipc.ILightService;
import com.example.smartroom.ipc.IServiceRouter;
import com.example.smartroom.ipc.ITimerCallback;
import com.example.smartroom.ipc.ITimerService;
import com.example.smartroom.ipc.ResultCode;
import com.example.smartroom.ipc.ServiceNames;

/**
 * 运行在 :room_core 进程的服务端。
 *
 * <p>它本身只发布一个 RouterBinder（一级 Binder）。RouterBinder 根据服务名返回
 * LightBinder 或 TimerBinder（二级 Binder）。新增领域时，Activity 不需要知道
 * Service 的实现，只需增加一个 AIDL、一个 Binder 和一个路由分支。</p>
 */
public class RoomCoreService extends Service {
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final LightBinder lightBinder = new LightBinder();
    private final TimerBinder timerBinder = new TimerBinder();

    private final IServiceRouter.Stub routerBinder = new IServiceRouter.Stub() {
        @Override
        public IBinder getService(String serviceName) {
            if (ServiceNames.LIGHT.equals(serviceName)) {
                return lightBinder;
            }
            if (ServiceNames.TIMER.equals(serviceName)) {
                return timerBinder;
            }
            return null;
        }
    };

    @Override
    public IBinder onBind(Intent intent) {
        return routerBinder;
    }

    @Override
    public void onDestroy() {
        handler.removeCallbacksAndMessages(null);
        lightBinder.destroy();
        timerBinder.destroy();
        super.onDestroy();
    }

    /** 灯光领域的服务端实现。RemoteCallbackList 能自动清理死亡的客户端 Binder。 */
    private final class LightBinder extends ILightService.Stub {
        private final RemoteCallbackList<ILightCallback> callbacks = new RemoteCallbackList<>();
        private boolean enabled;
        private int brightness = 50;

        @Override
        public void registerCallback(ILightCallback callback) {
            if (callback == null) {
                return;
            }
            callbacks.register(callback);
            notifyOne(callback);
        }

        @Override
        public void unregisterCallback(ILightCallback callback) {
            if (callback != null) {
                callbacks.unregister(callback);
            }
        }

        @Override
        public int setEnabled(boolean targetEnabled) {
            // Binder 线程只做校验和排队，模拟硬件 250ms 后才真正执行完成。
            handler.postDelayed(() -> {
                enabled = targetEnabled;
                notifyAllClients();
            }, 250L);
            return ResultCode.ACCEPTED;
        }

        @Override
        public int setBrightness(int targetBrightness) {
            if (targetBrightness < 0 || targetBrightness > 100) {
                return ResultCode.INVALID_ARGUMENT;
            }
            handler.postDelayed(() -> {
                brightness = targetBrightness;
                notifyAllClients();
            }, 250L);
            return ResultCode.ACCEPTED;
        }

        private void notifyOne(ILightCallback callback) {
            try {
                callback.onLightChanged(enabled, brightness, Process.myPid());
            } catch (RemoteException ignored) {
                // 客户端可能恰好退出；RemoteCallbackList 会在后续自动清理。
            }
        }

        private void notifyAllClients() {
            int count = callbacks.beginBroadcast();
            try {
                for (int index = 0; index < count; index++) {
                    notifyOne(callbacks.getBroadcastItem(index));
                }
            } finally {
                callbacks.finishBroadcast();
            }
        }

        private void destroy() {
            callbacks.kill();
        }
    }

    /** 专注倒计时领域的服务端实现。 */
    private final class TimerBinder extends ITimerService.Stub {
        private final RemoteCallbackList<ITimerCallback> callbacks = new RemoteCallbackList<>();
        private int remainingSeconds;
        private int totalSeconds;
        private boolean running;

        private final Runnable ticker = new Runnable() {
            @Override
            public void run() {
                if (!running) {
                    return;
                }
                remainingSeconds--;
                if (remainingSeconds <= 0) {
                    remainingSeconds = 0;
                    running = false;
                    notifyAllClients();
                    return;
                }
                notifyAllClients();
                handler.postDelayed(this, 1000L);
            }
        };

        @Override
        public void registerCallback(ITimerCallback callback) {
            if (callback == null) {
                return;
            }
            callbacks.register(callback);
            notifyOne(callback);
        }

        @Override
        public void unregisterCallback(ITimerCallback callback) {
            if (callback != null) {
                callbacks.unregister(callback);
            }
        }

        @Override
        public int start(int seconds) {
            if (seconds < 1 || seconds > 60 * 60) {
                return ResultCode.INVALID_ARGUMENT;
            }
            handler.post(() -> {
                handler.removeCallbacks(ticker);
                remainingSeconds = seconds;
                totalSeconds = seconds;
                running = true;
                notifyAllClients();
                handler.postDelayed(ticker, 1000L);
            });
            return ResultCode.ACCEPTED;
        }

        @Override
        public int cancel() {
            handler.post(() -> {
                handler.removeCallbacks(ticker);
                remainingSeconds = 0;
                running = false;
                notifyAllClients();
            });
            return ResultCode.ACCEPTED;
        }

        private void notifyOne(ITimerCallback callback) {
            try {
                callback.onTimerChanged(remainingSeconds, totalSeconds, running, Process.myPid());
            } catch (RemoteException ignored) {
                // 客户端已离开时忽略本次通知。
            }
        }

        private void notifyAllClients() {
            int count = callbacks.beginBroadcast();
            try {
                for (int index = 0; index < count; index++) {
                    notifyOne(callbacks.getBroadcastItem(index));
                }
            } finally {
                callbacks.finishBroadcast();
            }
        }

        private void destroy() {
            handler.removeCallbacks(ticker);
            callbacks.kill();
        }
    }
}
