package com.example.smartroom.ipc;

import com.example.smartroom.ipc.ILightCallback;

/**
 * <b>二级 Binder：灯光领域的远程服务接口。</b>
 *
 * <p>这个 AIDL 接口定义了客户端可以对灯光做的所有操作。
 * 服务端（RoomCoreService.LightBinder）实现这些方法，
 * 客户端通过 Proxy 层调用，无需直接接触 Binder 细节。</p>
 *
 * <p><b>设计模式：</b>这里的 setEnabled/setBrightness 都是"命令"方法，
 * 同步返回 {@link ResultCode#ACCEPTED} 只表示命令被受理，
 * 最终效果通过 {@link ILightCallback#onLightChanged} 异步通知。</p>
 *
 * @see com.example.smartroom.service.RoomCoreService.LightBinder 服务端实现
 * @see com.example.smartroom.client.LightProxy 客户端的本地封装
 */
interface ILightService {
    /**
     * 注册灯光状态回调。
     * 注册后，服务端会立即补发一次当前状态（初始状态补发），
     * 后续每次状态变化也会通过这个 callback 通知。
     *
     * @param callback 客户端实现的跨进程回调接口
     */
    void registerCallback(ILightCallback callback);

    /**
     * 注销回调。
     * 页面不可见时应及时注销，避免服务端持续向已离开的客户端发送数据。
     *
     * @param callback 之前注册过的回调接口
     */
    void unregisterCallback(ILightCallback callback);

    /**
     * 请求开灯或关灯。
     *
     * @param enabled true = 开灯，false = 关灯
     * @return {@link ResultCode#ACCEPTED} 表示受理成功
     *         {@link ResultCode#NOT_CONNECTED} 表示 Binder 未就绪
     */
    int setEnabled(boolean enabled);

    /**
     * 设置亮度。
     *
     * @param brightness 亮度值 0～100，超出范围返回 {@link ResultCode#INVALID_ARGUMENT}
     * @return 同步返回码
     */
    int setBrightness(int brightness);
}