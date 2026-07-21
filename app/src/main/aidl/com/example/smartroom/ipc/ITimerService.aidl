package com.example.smartroom.ipc;

import com.example.smartroom.ipc.ITimerCallback;

/** 二级 Binder：专注计时领域的远程合同。 */
interface ITimerService {
    /** 注册计时状态回调；注册后会立刻收到一次当前状态。 */
    void registerCallback(ITimerCallback callback);

    /** 客户端不再观察计时状态时注销回调。 */
    void unregisterCallback(ITimerCallback callback);

    /** 启动倒计时，seconds 必须在服务端允许的范围内。 */
    int start(int seconds);

    /** 取消当前倒计时。 */
    int cancel();
}