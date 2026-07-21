package com.example.smartroom.ipc;

import com.example.smartroom.ipc.ILightCallback;

/**
 * 二级 Binder：灯光领域的远程合同。
 * AIDL 只规定双方可以调用什么，不关心服务端具体怎样实现灯光。
 */
interface ILightService {
    /** 注册反向回调；服务端的灯光状态变化后会通过它通知客户端。 */
    void registerCallback(ILightCallback callback);

    /** 页面离开时注销回调，避免继续向已经不需要结果的客户端发送数据。 */
    void unregisterCallback(ILightCallback callback);

    /** 请求开灯或关灯。返回值只表示请求是否受理，不代表灯光已经变化。 */
    int setEnabled(boolean enabled);

    /** 请求设置 0～100 的亮度，最终亮度仍以 Callback 为准。 */
    int setBrightness(int brightness);
}