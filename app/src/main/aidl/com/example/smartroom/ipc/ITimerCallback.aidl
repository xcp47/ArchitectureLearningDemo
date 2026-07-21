package com.example.smartroom.ipc;

/** 专注计时器的异步状态回调。 */
oneway interface ITimerCallback {
    void onTimerChanged(int remainingSeconds, int totalSeconds, boolean running, int servicePid);
}
