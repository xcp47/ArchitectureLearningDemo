package com.example.smartroom.ipc;

import com.example.smartroom.ipc.ITimerCallback;

/** 二级 Binder：专注计时领域的远程合同。 */
interface ITimerService {
    void registerCallback(ITimerCallback callback);
    void unregisterCallback(ITimerCallback callback);
    int start(int seconds);
    int cancel();
}
