package com.example.smartroom.ipc;

/**
 * <b>灯光状态异步回调接口（服务端 → 客户端的反向通知）。</b>
 *
 * <p><b>为什么是 "oneway"？</b>
 * oneway 表示服务端调用这个方法时不需要等待客户端处理完毕就返回。
 * 如果不用 oneway，服务端会阻塞直到客户端的方法执行完成；
 * 用了 oneway，服务端可以并发向多个客户端推送状态而不会被慢速客户端拖累。</p>
 *
 * <p><b>通信方向：</b>客户端在 {@link ILightService#registerCallback} 时
 * 把自己的 callback 传给服务端。之后服务端完成操作后，主动调用这个 callback
 * 把最新状态推回给客户端。在 Android Binder 机制中，这就是"反向回调"或
 * "监听者模式"的跨进程实现。</p>
 *
 * @see com.example.smartroom.client.LightProxy 客户端定义了这个 callback 的具体行为
 * @see com.example.smartroom.service.RoomCoreService.LightBinder 服务端调用这个 callback
 */
oneway interface ILightCallback {
    /**
     * 灯光最终状态变化的通知。
     * 只有收到这个回调后，UI 才应该更新灯光相关的显示。
     *
     * @param enabled 当前是否开启
     * @param brightness 当前亮度值（0～100）
     * @param servicePid 服务端进程 ID，用于调试确认跨进程通信是否生效
     */
    void onLightChanged(boolean enabled, int brightness, int servicePid);
}