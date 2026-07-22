package com.example.smartroom.ipc;

import com.example.smartroom.ipc.ITimerCallback;

/**
 * <b>二级 Binder：专注计时领域的远程服务接口。</b>
 *
 * <p>与 {@link ILightService} 结构完全相同——
 * 注册回调 → 发起命令 → 服务端通过回调推送状态。
 * 这种"注册 + 命令 + 回调"的三步模式是 AIDL 常用的设计范式。</p>
 *
 * @see com.example.smartroom.service.RoomCoreService.TimerBinder 服务端实现
 * @see com.example.smartroom.client.TimerProxy 客户端的本地封装
 */
interface ITimerService {
    /**
     * 注册倒计时状态回调。
     * 注册后立即补发一次当前状态，方便新打开的页面立刻获取最新计时数据。
     *
     * @param callback 客户端实现的跨进程计时回调接口
     */
    void registerCallback(ITimerCallback callback);

    /**
     * 注销倒计时回调。
     *
     * @param callback 之前注册过的回调接口
     */
    void unregisterCallback(ITimerCallback callback);

    /**
     * 启动倒计时。
     *
     * @param seconds 倒计时秒数，服务端会限制合法范围（如 1～3600）
     * @return {@link ResultCode#ACCEPTED} 表示受理成功
     *         {@link ResultCode#INVALID_ARGUMENT} 表示秒数超出范围
     */
    int start(int seconds);

    /**
     * 取消当前倒计时。
     *
     * @return 同步返回码
     */
    int cancel();
}