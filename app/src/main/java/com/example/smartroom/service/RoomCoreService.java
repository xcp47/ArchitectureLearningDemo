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
 * <b>服务端核心组件，运行在 :room_core 进程。</b>
 *
 * <p><b>架构角色：</b></p>
 * <ul>
 *   <li>这是一个 Android {@link Service}，通过 {@link #onBind} 发布一个"一级 Router Binder"</li>
 *   <li>Router Binder 是一个 {@link IServiceRouter}，客户端通过它查找具体的业务接口</li>
 *   <li>内部维护了 {@link LightBinder} 和 {@link TimerBinder} 两个"二级 Binder"</li>
 * </ul>
 *
 * <p><b>为什么是 onBind 而不是 onStartCommand？</b>
 * 因为我们使用的是绑定服务（Bound Service）模式。
 * 客户端通过 bindService() 连接，通过 unbindService() 断开。
 * BIND_AUTO_CREATE 标志会在有客户端绑定时自动创建 Service 实例。
 * 当所有客户端都解绑后，系统会自动销毁这个 Service。</p>
 *
 * <p><b>二级 Binder 的分发逻辑：</b>
 * <pre>
 * RouterBinder.getService("LIGHT") → 返回 LightBinder
 * RouterBinder.getService("TIMER") → 返回 TimerBinder
 * RouterBinder.getService("其他")  → 返回 null
 * </pre>
 * 新增业务领域时，只需增加一个新的二级 Binder 和一个路由分支即可，
 * Activity 不需要知道 Service 的实现细节。</p>
 *
 * @see com.example.smartroom.client.RoomConnection 客户端的连接管理器
 * @see com.example.smartroom.client.BaseFeatureProxy 客户端的模板方法代理
 */
public class RoomCoreService extends Service {

    /*
     * AIDL 方法默认在 Binder 线程池中执行（不是主线程）。
     * 这里把真正的状态修改投递到 Service 的主线程 Handler 中，
     * 有两个好处：
     * 1. 避免多个 Binder 线程同时修改状态导致并发问题
     * 2. 用 postDelayed 模拟"硬件命令需要一段时间才能完成"的效果
     */
    private final Handler handler = new Handler(Looper.getMainLooper());

    // 两个二级业务 Binder 共享同一个顶层 Service 的生命周期
    private final LightBinder lightBinder = new LightBinder();
    private final TimerBinder timerBinder = new TimerBinder();

    /**
     * 一级 Router Binder（总机）。
     * 客户端的 RoomConnection 绑定 Service 后拿到的是这个接口。
     */
    private final IServiceRouter.Stub routerBinder = new IServiceRouter.Stub() {
        @Override
        public IBinder getService(String serviceName) {
            // 一级 Binder 只负责"查分机号"，不在这个方法里实现具体业务逻辑
            if (ServiceNames.LIGHT.equals(serviceName)) {
                return lightBinder;
            }
            if (ServiceNames.TIMER.equals(serviceName)) {
                return timerBinder;
            }
            // 不认识的服务名返回 null，客户端会收到空引用并标记为不可用
            return null;
        }
    };

    //============================================================
    // Service 生命周期方法
    //============================================================

    @Override
    public IBinder onBind(Intent intent) {
        // 客户端通过 bindService() 绑定该 Service 时，系统调用此方法
        // 返回的是 Router Binder，不是具体的灯光或计时器接口
        return routerBinder;
    }

    @Override
    public void onDestroy() {
        // Service 销毁时清理所有未执行的 Handler 任务，
        // 避免 Runnable 继续持有已销毁的 Service 引用导致内存泄漏
        handler.removeCallbacksAndMessages(null);
        lightBinder.destroy();
        timerBinder.destroy();
        super.onDestroy();
    }

    //============================================================
    // 二级 Binder：灯光业务的服务端实现
    //============================================================

    /**
     * 灯光领域的服务端 Binder 实现。
     *
     * <p>它直接继承 AIDL 编译器生成的 Stub 类（也就是实现了 ILightService 接口）。
     * RemoteCallbackList 是 Android 专门为跨进程回调设计的容器，
     * 它能自动清理死亡客户端的 Binder 引用，避免内存泄漏。</p>
     */
    private final class LightBinder extends ILightService.Stub {
        /*
         * RemoteCallbackList 是 Android framework 提供的特殊容器，
         * 专门用于保存跨进程的 Callback Binder。
         * 当客户端进程死亡时，它能自动识别并移除对应的 Binder 条目。
         * 注意它不是标准的 List，必须通过 beginBroadcast/finishBroadcast 遍历。
         */
        private final RemoteCallbackList<ILightCallback> callbacks = new RemoteCallbackList<>();

        // 下面两个字段模拟真实的"灯具设备状态"
        private boolean enabled;           // 灯是否打开
        private int brightness = 50;       // 当前亮度（范围 0～100）

        //------------------------------------------------------
        // ILightService 接口实现
        //------------------------------------------------------

        @Override
        public void registerCallback(ILightCallback callback) {
            if (callback == null) {
                return;
            }
            callbacks.register(callback);

            // 新客户端注册后立刻补发当前状态，
            // 这样页面打开后不需要等待操作就能看到当前灯光的真实状态
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
            /*
             * Binder 线程只做参数校验和任务入队：
             * 用 postDelayed 模拟硬件执行需要 250ms 延迟。
             * 返回 ACCEPTED 后，客户端可以立即继续做其他事情，
             * 不需要等待 250ms 的模拟硬件延迟。
             */
            handler.postDelayed(() -> {
                enabled = targetEnabled;
                notifyAllClients();    // 通知所有已注册的客户端
            }, 250L);
            return ResultCode.ACCEPTED;
        }

        @Override
        public int setBrightness(int targetBrightness) {
            // 服务端必须再次校验参数，不能完全信任客户端传入的数据
            if (targetBrightness < 0 || targetBrightness > 100) {
                return ResultCode.INVALID_ARGUMENT;
            }
            handler.postDelayed(() -> {
                brightness = targetBrightness;
                notifyAllClients();
            }, 250L);
            return ResultCode.ACCEPTED;
        }

        //------------------------------------------------------
        // 通知辅助方法
        //------------------------------------------------------

        /** 向单个客户端发送当前状态 */
        private void notifyOne(ILightCallback callback) {
            try {
                callback.onLightChanged(enabled, brightness, Process.myPid());
            } catch (RemoteException ignored) {
                // 客户端可能恰好退出，RemoteCallbackList 会在后续自动清理
            }
        }

        /** 广播当前状态给所有已注册的客户端 */
        private void notifyAllClients() {
            /*
             * RemoteCallbackList 的遍历规则：
             * beginBroadcast() 和 finishBroadcast() 必须成对出现。
             * 用 try/finally 确保 finishBroadcast() 一定会被执行。
             */
            int count = callbacks.beginBroadcast();
            try {
                for (int index = 0; index < count; index++) {
                    notifyOne(callbacks.getBroadcastItem(index));
                }
            } finally {
                callbacks.finishBroadcast();
            }
        }

        /** 清理所有回调（Service 销毁时调用） */
        private void destroy() {
            callbacks.kill();
        }
    }

    //============================================================
    // 二级 Binder：倒计时业务的服务端实现
    //============================================================

    /**
     * 专注倒计时领域的服务端 Binder 实现。
     *
     * <p>与 {@link LightBinder} 结构类似，但业务逻辑不同：
     * 它通过一个每秒执行一次的 {@link #ticker} Runnable 实现倒计时。</p>
     */
    private final class TimerBinder extends ITimerService.Stub {
        private final RemoteCallbackList<ITimerCallback> callbacks = new RemoteCallbackList<>();

        // remainingSeconds = 当前剩余秒数
        // totalSeconds = 本次倒计时的总秒数（用于 UI 计算进度百分比）
        private int remainingSeconds;
        private int totalSeconds;
        private boolean running;  // 是否正在倒计时

        /*
         * ticker 是一个每秒执行一次的任务。
         * 它不会自己创建新线程，而是通过 handler.postDelayed(this, 1000L)
         * 反复把自己投递到 Handler 所在的线程（即 Service 的主线程）。
         * 这种方式避免了手动管理 Timer 或 ScheduledExecutorService 的复杂性。
         */
        private final Runnable ticker = new Runnable() {
            @Override
            public void run() {
                if (!running) {
                    return;  // 如果倒计时已被取消，不再执行
                }
                // 每次 tick 减 1 秒
                remainingSeconds--;
                if (remainingSeconds <= 0) {
                    remainingSeconds = 0;
                    running = false;  // 倒计时结束
                    notifyAllClients();
                    return;  // 不再投递下次 tick
                }
                notifyAllClients();
                // 继续投递下一次 tick（1 秒后执行）
                handler.postDelayed(this, 1000L);
            }
        };

        //------------------------------------------------------
        // ITimerService 接口实现
        //------------------------------------------------------

        @Override
        public void registerCallback(ITimerCallback callback) {
            if (callback == null) {
                return;
            }
            callbacks.register(callback);
            // 新注册的客户端立即获取当前状态
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
            // 服务端必须独立校验参数合法性
            if (seconds < 1 || seconds > 60 * 60) {
                return ResultCode.INVALID_ARGUMENT;
            }
            handler.post(() -> {
                // 新计时开始前，移除旧的 ticker，防止两个倒计时同时递减
                handler.removeCallbacks(ticker);
                remainingSeconds = seconds;
                totalSeconds = seconds;
                running = true;
                notifyAllClients();
                // 启动每秒一次的 tick
                handler.postDelayed(ticker, 1000L);
            });
            return ResultCode.ACCEPTED;
        }

        @Override
        public int cancel() {
            handler.post(() -> {
                handler.removeCallbacks(ticker);  // 停止 tick
                remainingSeconds = 0;
                running = false;
                notifyAllClients();
            });
            return ResultCode.ACCEPTED;
        }

        //------------------------------------------------------
        // 通知辅助方法
        //------------------------------------------------------

        private void notifyOne(ITimerCallback callback) {
            try {
                callback.onTimerChanged(remainingSeconds, totalSeconds, running, Process.myPid());
            } catch (RemoteException ignored) {
                // 客户端已离开时忽略本次通知
            }
        }

        /** 广播当前计时状态给所有已注册的客户端 */
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

        /** 清理资源（Service 销毁时调用） */
        private void destroy() {
            handler.removeCallbacks(ticker);
            callbacks.kill();
        }
    }
}
