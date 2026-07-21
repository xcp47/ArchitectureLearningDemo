package com.example.smartroom.ipc;

/**
 * 专注计时器的异步状态回调。
 * oneway 让服务端发送通知后立即返回，不被客户端界面更新阻塞。
 */
oneway interface ITimerCallback {
    /**
     * remainingSeconds 是剩余时间，totalSeconds 是本次总时间。
     * 两者一起传递后，中途打开被控端页面也能显示正确的 XX / XX。
     */
    void onTimerChanged(int remainingSeconds, int totalSeconds, boolean running, int servicePid);
}