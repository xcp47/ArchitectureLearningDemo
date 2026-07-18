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
    private final String serviceName;
    private volatile T remote;
    private boolean started;

    BaseFeatureProxy(String serviceName) {
        this.serviceName = serviceName;
    }

    public final void start() {
        if (started) {
            return;
        }
        started = true;
        connection.addListener(this);
    }

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
        releaseRemote(true);
        try {
            IBinder binder = router.getService(serviceName);
            T typedRemote = binder == null ? null : asInterface(binder);
            if (typedRemote == null) {
                onAvailabilityChanged(false);
                return;
            }
            remote = typedRemote;
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

    protected abstract T asInterface(IBinder binder);

    protected abstract void registerRemoteCallback(T remote) throws RemoteException;

    protected abstract void unregisterRemoteCallback(T remote) throws RemoteException;

    protected abstract void onAvailabilityChanged(boolean available);
}
