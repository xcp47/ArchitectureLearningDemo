package com.example.smartroom.client;

import android.os.IBinder;
import android.os.IInterface;
import android.os.RemoteException;

import com.example.smartroom.ipc.IServiceRouter;

/**
 * <b>业务 Proxy 的模板方法骨架（抽象父类）。</b>
 *
 * <p><b>设计模式：模板方法（Template Method）</b>
 * 父类定义了"连接 + 获取二级 Binder + 注册回调 + 断线清理"的不变流程，
 * 子类只需要实现四个抽象方法，填写领域差异部分：
 * <ol>
 *   <li>{@link #asInterface(IBinder)} —— 把 IBinder 转成强类型 AIDL 接口</li>
 *   <li>{@link #registerRemoteCallback(IInterface)} —— 向服务端注册异步回调</li>
 *   <li>{@link #unregisterRemoteCallback(IInterface)} —— 注销回调</li>
 *   <li>{@link #onAvailabilityChanged(boolean)} —— 通知上层业务是否可用</li>
 * </ol>
 * </p>
 *
 * <p><b>工作流程：</b>
 * <pre>{@code
 * start() → addListener → onRouterReady → 获取 IBinder
 * → asInterface() 转成 T → registerCallback → onAvailabilityChanged(true)
 * 
 * stop() → releaseRemote(true) → removeListener
 * }</pre>
 * </p>
 *
 * @param <T> 具体的业务 AIDL 接口类型，如 {@link com.example.smartroom.ipc.ILightService}
 *            或 {@link com.example.smartroom.ipc.ITimerService}
 */
abstract class BaseFeatureProxy<T extends IInterface> implements RoomConnection.Listener {

    // 统一连接层单例，所有业务 Proxy 共用同一个 RoomConnection
    private final RoomConnection connection = RoomConnection.getInstance();

    /**
     * 服务名（分机号），用于向一级 Router 索取对应的二级 Binder。
     * 由子类在构造时传入，例如 {@link ServiceNames#LIGHT} 或 {@link ServiceNames#TIMER}。
     */
    private final String serviceName;

    /**
     * 已经转换好的二级 AIDL 接口，即强类型的远程业务代理。
     * volatile 关键字确保多线程下的可见性：
     * 一个线程写入 remote，另一个线程读取时能立即看到最新值。
     * null 表示当前业务不可用。
     */
    private volatile T remote;

    // 防止同一个 Proxy 重复注册 RoomConnection.Listener
    private boolean started;

    /**
     * @param serviceName 向 IServiceRouter 索取二级 Binder 时用的服务名
     */
    BaseFeatureProxy(String serviceName) {
        this.serviceName = serviceName;
    }

    /**
     * 开始观察一级连接状态。一旦 Router 就绪，会自动获取二级 Binder。
     * final 关键字阻止子类重写，确保连接初始化流程不被篡改。
     */
    public final void start() {
        if (started) {
            // 防止多次调用导致重复注册 Listener
            return;
        }
        started = true;
        connection.addListener(this);
    }

    /**
     * 停止观察，并尽可能向服务端注销 Callback。
     * 页面 onStop() 时务必调用此方法，避免内存泄漏或无效回调。
     */
    public final void stop() {
        if (!started) {
            return;
        }
        started = false;
        releaseRemote(true);  // 清理二级 Binder 并注销回调
        connection.removeListener(this);
    }

    /**
     * 判断业务是否就绪（即二级 Binder 是否已经取得）。
     * UI 层可以用这个方法来启用/禁用相关控件。
     */
    public final boolean isReady() {
        return remote != null;
    }

    /**
     * 子类通过此方法获取当前可用的远程接口实例，用于发起 AIDL 调用。
     * 如果返回 null，说明业务暂时不可用。
     */
    protected final T remote() {
        return remote;
    }

    // ============================================================
    // RoomConnection.Listener 接口实现
    // ============================================================

    @Override
    public final void onConnectionStateChanged(int state, String detail) {
        // 领域 Proxy 只关注 Router 是否可用，不关心一级连接状态机细节。
        // 如果需要显示连接状态，由 Activity 自己注册 RoomConnection.Listener。
    }

    @Override
    public final void onRouterReady(IServiceRouter router) {
        // 先清理旧接口，避免重连后仍然向已经失效的 Binder 发请求
        releaseRemote(true);
        try {
            // 【第一步】用服务名从一级 Router 取得原始二级 Binder（IBinder）
            IBinder binder = router.getService(serviceName);

            // 【第二步】由子类把 IBinder 转成强类型业务接口（如 ILightService）
            T typedRemote = binder == null ? null : asInterface(binder);
            if (typedRemote == null) {
                // 服务端没有这个名称的 Binder，通知上层不可用
                onAvailabilityChanged(false);
                return;
            }
            remote = typedRemote;

            // 【第三步】注册反向 Callback，这样服务端才能向客户端推送最终状态
            registerRemoteCallback(typedRemote);
            onAvailabilityChanged(true);
        } catch (RemoteException exception) {
            // 跨进程调用异常（如远端进程崩溃），清理并触发重连
            remote = null;
            onAvailabilityChanged(false);
            connection.reconnectFromRemoteFailure(exception.getClass().getSimpleName());
        }
    }

    @Override
    public final void onRouterLost() {
        // 远端已死亡时不再尝试 unregister，直接丢弃失效的 remote 引用
        remote = null;
        onAvailabilityChanged(false);
    }

    /**
     * 业务调用失败时的统一处理入口。
     * 子类在执行 AIDL 调用捕获到 RemoteException 后，应调用此方法。
     * 它会清空二级 Binder，并让统一连接层负责重新绑定。
     */
    protected final void remoteCallFailed(RemoteException exception) {
        remote = null;
        onAvailabilityChanged(false);
        connection.reconnectFromRemoteFailure(exception.getClass().getSimpleName());
    }

    /**
     * 清理远程引用。
     *
     * @param unregister 是否同时向服务端注销回调。
     *                   true = 正常清理（如主动 stop），
     *                   false = 远端已死，不再尝试跨进程通信
     */
    private void releaseRemote(boolean unregister) {
        T oldRemote = remote;
        remote = null;
        if (unregister && oldRemote != null) {
            try {
                unregisterRemoteCallback(oldRemote);
            } catch (RemoteException ignored) {
                // 清理阶段远端可能已经死亡，忽略异常
            }
        }
        onAvailabilityChanged(false);
    }

    // ============================================================
    // 以下四个抽象方法是模板方法模式中的"钩子方法"，由子类实现
    // ============================================================

    /** 把原始 IBinder 转换为强类型的业务 AIDL 接口 */
    protected abstract T asInterface(IBinder binder);

    /** 向服务端注册异步回调，注册后服务端才能推送实时状态 */
    protected abstract void registerRemoteCallback(T remote) throws RemoteException;

    /** 向服务端注销异步回调 */
    protected abstract void unregisterRemoteCallback(T remote) throws RemoteException;

    /** 通知上层监听者：当前业务是否可用 */
    protected abstract void onAvailabilityChanged(boolean available);
}
