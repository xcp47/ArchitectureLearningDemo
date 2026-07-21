package com.example.smartroom.client;

import android.os.IBinder;
import android.os.IInterface;
import android.os.RemoteException;

import com.example.smartroom.ipc.IServiceRouter;

/**
 * 业务 Proxy 的模板方法骨架。
 *
 * <p>固定步骤由父类完成：取得二级 Binder → 转成强类型接口 → 注册回调 → 断线清理。
 * 子类只填写三个领域差异点：asInterface、register、unregister。</p>
 */
abstract class BaseFeatureProxy<T extends IInterface> implements RoomConnection.Listener {
    private final RoomConnection connection = RoomConnection.getInstance();

    // serviceName 是要向一级 Router 索取的“分机号”，例如 LIGHT。
    private final String serviceName;

    // remote 是已经转换好的二级 AIDL 接口；null 表示当前业务不可调用。
    private volatile T remote;

    // 防止同一个 Proxy 重复注册 RoomConnection.Listener。
    private boolean started;

    BaseFeatureProxy(String serviceName) {
        this.serviceName = serviceName;
    }

    /** 开始观察一级连接。final 表示子类不能改变这套固定流程。 */
    public final void start() {
        if (started) {
            return;
        }
        started = true;
        connection.addListener(this);
    }

    /** 停止观察，并尽可能向服务端注销 Callback。 */
    public final void stop() {
        if (!started) {
            return;
        }
        started = false;
        releaseRemote(true);
        connection.removeListener(this);
    }

    public final boolean isReady() {
        return remote != null;
    }

    protected final T remote() {
        return remote;
    }

    @Override
    public final void onConnectionStateChanged(int state, String detail) {
        // 领域 Proxy 只关心 Router 是否可用，不复制顶层状态机。
    }

    @Override
    public final void onRouterReady(IServiceRouter router) {
        // 先清理旧接口，避免重连后仍然向已经失效的 Binder 发请求。
        releaseRemote(true);
        try {
            // 第一步：用服务名从一级 Router 取得原始二级 Binder。
            IBinder binder = router.getService(serviceName);

            // 第二步：由子类把 IBinder 转成 ILightService/ITimerService。
            T typedRemote = binder == null ? null : asInterface(binder);
            if (typedRemote == null) {
                onAvailabilityChanged(false);
                return;
            }
            remote = typedRemote;

            // 第三步：注册反向 Callback，之后服务端才能推送最终状态。
            registerRemoteCallback(typedRemote);
            onAvailabilityChanged(true);
        } catch (RemoteException exception) {
            remote = null;
            onAvailabilityChanged(false);
            connection.reconnectFromRemoteFailure(exception.getClass().getSimpleName());
        }
    }

    @Override
    public final void onRouterLost() {
        // 远端已死亡时不再尝试 unregister，直接丢弃失效代理。
        remote = null;
        onAvailabilityChanged(false);
    }

    /** 业务调用失败时清空二级 Binder，并让统一连接层负责重新绑定。 */
    protected final void remoteCallFailed(RemoteException exception) {
        remote = null;
        onAvailabilityChanged(false);
        connection.reconnectFromRemoteFailure(exception.getClass().getSimpleName());
    }

    private void releaseRemote(boolean unregister) {
        T oldRemote = remote;
        remote = null;
        if (unregister && oldRemote != null) {
            try {
                unregisterRemoteCallback(oldRemote);
            } catch (RemoteException ignored) {
                // 清理阶段远端可能已经死亡。
            }
        }
        onAvailabilityChanged(false);
    }

    // 以下四个方法是模板中的“空格”，由每个具体业务 Proxy 填写。
    protected abstract T asInterface(IBinder binder);

    protected abstract void registerRemoteCallback(T remote) throws RemoteException;

    protected abstract void unregisterRemoteCallback(T remote) throws RemoteException;

    protected abstract void onAvailabilityChanged(boolean available);
}
