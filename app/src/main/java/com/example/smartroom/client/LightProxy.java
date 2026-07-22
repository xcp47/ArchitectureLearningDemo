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

/**
 * <b>灯光业务的本地代理（Proxy）。</b>
 *
 * <p>这是 UI 层看到的"本地灯光对象"。
 * 它内部使用 {@link BaseFeatureProxy}&lt;{@link ILightService}&gt; 处理所有跨进程细节，
 * 对外暴露简单的普通 Java 方法 {@link #setEnabled(boolean)}、{@link #setBrightness(int)}。
 * Activity 不需要知道 AIDL、IBinder 或 RemoteException 的存在。</p>
 *
 * <p><b>架构层次：</b>
 * <pre>
 * UI (Activity)  ←→  LightProxy  ←→  BaseFeatureProxy  ←→  RoomConnection  ←→  RoomCoreService
 * （普通 Java）      （本地封装）      （模板骨架）           （连接管理）         （远端进程）
 * </pre></p>
 *
 * <p><b>单例模式：</b>整个进程共享一个 LightProxy，
 * 避免重复注册 ILightCallback，保证灯光状态的一致性。</p>
 *
 * @see com.example.smartroom.service.RoomCoreService.LightBinder 服务端的对应实现
 */
public final class LightProxy extends BaseFeatureProxy<ILightService> {

    /**
     * 给 UI 层使用的纯 Java 监听器（与应用层解耦）。
     *
     * <p>它和 AIDL 的 {@link ILightCallback} 是两回事：
     * <ul>
     *   <li>{@link ILightCallback} —— AIDL 跨进程接口，运行在 Binder 线程池</li>
     *   <li>{@link Listener} —— 纯 Java 接口，在主线程通知，UI 可以直接使用</li>
     * </ul>
     * LightProxy 在 AIDL Callback 的 Binder 线程中收到通知，
     * 然后通过 Handler 切到主线程，再转发给本地的所有 Listener。
     * </p>
     */
    public interface Listener {
        /** 灯光业务可用/不可用时触发，UI 可据此启用/禁用控件 */
        void onLightAvailabilityChanged(boolean available);

        /**
         * 服务端确认的最终灯光状态。
         * 只有收到这个回调后，UI 才应该更新界面。
         *
         * @param enabled 当前是否开启
         * @param brightness 当前亮度（0～100）
         * @param servicePid 服务端进程 ID，用于验证跨进程通信
         */
        void onLightChanged(boolean enabled, int brightness, int servicePid);
    }

    //============================================================
    // 成员变量
    //============================================================

    /**
     * AIDL Callback 运行在 Binder 线程池（不是主线程），
     * 需要通过 Handler 切到主线程再更新 UI。
     */
    private final Handler mainHandler = new Handler(Looper.getMainLooper());

    /**
     * 线程安全的监听者列表。
     * CopyOnWriteArrayList 适合"读多写少"的场景，
     * 遍历时不需要加锁，适合频繁通知、偶尔添加/删除监听者的通信模式。
     */
    private final CopyOnWriteArrayList<Listener> listeners = new CopyOnWriteArrayList<>();

    /**
     * AIDL 回调对象（跨进程的"回信地址"）。
     * 客户端把这个对象通过 {@link ILightService#registerCallback} 传给服务端，
     * 服务端完成操作后会调用它的 {@link ILightCallback.Stub#onLightChanged} 方法。
     *
     * <p>Stub 是 AIDL 编译器自动生成的抽象类，
     * 它将 Binder 线程传过来的数据反序列化后，调用我们在下面定义的方法。
     * 我们在方法中把通知转发到主线程，再广播给所有本地 Listener。</p>
     */
    private final ILightCallback callback = new ILightCallback.Stub() {
        @Override
        public void onLightChanged(boolean enabled, int brightness, int servicePid) {
            // 此时还在 Binder 线程，不能直接操作 UI
            mainHandler.post(() -> {
                for (Listener listener : listeners) {
                    listener.onLightChanged(enabled, brightness, servicePid);
                }
            });
        }
    };

    //============================================================
    // 构造方法 & 单例
    //============================================================

    private LightProxy() {
        // 传入服务名 "LIGHT"，父类 BaseFeatureProxy 会用这个名称
        // 向一级 Router 索取灯光业务的二级 Binder
        super(ServiceNames.LIGHT);
    }

    /**
     * 静态内部类实现的懒加载单例。
     * 优点：线程安全（由类加载机制保证）、延迟加载（用到时才初始化）。
     */
    private static final class Holder {
        private static final LightProxy INSTANCE = new LightProxy();
    }

    /** 获取 LightProxy 的唯一实例 */
    public static LightProxy getInstance() {
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
    // 对 UI 暴露的业务方法（普通 Java 方法，无 AIDL 污染）
    //============================================================

    /**
     * 请求开灯或关灯。
     *
     * <p><b>重要：</b>返回 {@link ResultCode#ACCEPTED} 只表示请求被服务端受理，
     * <b>不代表灯光已经实际变化</b>。最终的开/关状态要通过
     * {@link Listener#onLightChanged} 异步回调确认。</p>
     *
     * @param enabled true = 开灯，false = 关灯
     * @return 同步返回码，参见 {@link ResultCode}
     */
    public int setEnabled(boolean enabled) {
        ILightService service = remote();

        // 如果二级 Binder 还没拿到，不发起远程调用，直接返回明确错误码
        if (service == null) {
            return ResultCode.NOT_CONNECTED;
        }
        try {
            return service.setEnabled(enabled);
        } catch (RemoteException exception) {
            // 远程调用失败，通知父类清理并触发重连
            remoteCallFailed(exception);
            return ResultCode.REMOTE_ERROR;
        }
    }

    /**
     * 请求设置亮度。
     * 参数是否合法由服务端 LightBinder 最终校验。
     *
     * @param brightness 亮度值 0～100
     * @return 同步返回码
     */
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

    //============================================================
    // BaseFeatureProxy 抽象方法实现（模板方法模式的差异点）
    //============================================================

    @Override
    protected ILightService asInterface(IBinder binder) {
        // AIDL 编译器自动生成了 Stub.asInterface() 方法：
        // - 如果 binder 在同一进程，返回服务端的本地实现对象
        // - 如果 binder 跨进程，返回一个跨进程调用的代理（Proxy）对象
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
        // 切到主线程通知监听者（因为可能从 Binder 线程调用）
        mainHandler.post(() -> {
            for (Listener listener : listeners) {
                listener.onLightAvailabilityChanged(available);
            }
        });
    }
}
