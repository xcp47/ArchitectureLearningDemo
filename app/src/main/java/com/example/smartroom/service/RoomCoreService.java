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
    /*
     * AIDL 方法默认在 Binder 线程池执行。这里把真正的状态修改投递到 Service
     * 主线程，既避免并发修改状态，也模拟“硬件命令需要一段时间才能完成”。
     */
    private final Handler handler = new Handler(Looper.getMainLooper());

    // 两个对象就是两个二级业务 Binder，它们共享同一个顶层 Service 生命周期。
    private final LightBinder lightBinder = new LightBinder();
    private final TimerBinder timerBinder = new TimerBinder();

    private final IServiceRouter.Stub routerBinder = new IServiceRouter.Stub() {
        @Override
        public IBinder getService(String serviceName) {
            // 一级 Binder 只负责“查分机”，不在这里实现灯光或计时业务。
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
        // 客户端首次绑定拿到的是一级 Router，而不是某个具体业务接口。
        return routerBinder;
    }

    @Override
    public void onDestroy() {
        // Service 销毁时取消所有未执行任务，避免 Runnable 继续引用旧 Service。
        handler.removeCallbacksAndMessages(null);
        lightBinder.destroy();
        timerBinder.destroy();
        super.onDestroy();
    }

    /** 灯光领域的服务端实现。RemoteCallbackList 能自动清理死亡的客户端 Binder。 */
    private final class LightBinder extends ILightService.Stub {
        /*
         * RemoteCallbackList 专门保存跨进程 Callback。
         * 客户端进程死亡后，它能识别并清理对应 Binder。
         */
        private final RemoteCallbackList<ILightCallback> callbacks = new RemoteCallbackList<>();

        // 下面两个字段就是本示例用来代替真实灯具的“设备状态”。
        private boolean enabled;
        private int brightness = 50;

        @Override
        public void registerCallback(ILightCallback callback) {
            if (callback == null) {
                return;
            }
            callbacks.register(callback);

            // 新客户端一注册就补发当前状态，页面无需等待下一次操作。
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
            // beginBroadcast/finishBroadcast 必须成对出现，finally 保证一定收尾。
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

        // remaining 是当前剩余秒数；total 用于让页面显示“剩余 / 总时长”。
        private int remainingSeconds;
        private int totalSeconds;
        private boolean running;

        /*
         * ticker 是每秒执行一次的任务。它不会自己创建线程，而是反复投递到
         * 上面的 Handler 所在线程。
         */
        private final Runnable ticker = new Runnable() {
            @Override
            public void run() {
                if (!running) {
                    return;
                }
                // 每次 tick 先减 1；到 0 后停止继续投递自己。
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
            // 远端必须再次校验参数，不能完全相信客户端传入的数据。
            if (seconds < 1 || seconds > 60 * 60) {
                return ResultCode.INVALID_ARGUMENT;
            }
            handler.post(() -> {
                // 新计时开始前先移除旧 ticker，防止两个倒计时同时递减。
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
            // beginBroadcast/finishBroadcast 必须成对出现，finally 保证一定收尾。
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
