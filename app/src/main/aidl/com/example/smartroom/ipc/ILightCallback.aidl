package com.example.smartroom.ipc;

/**
 * 服务端完成灯光操作后，通过这个反向 Binder 通知客户端。
 * oneway 表示服务端只负责发出通知，不等待客户端页面处理完毕。
 */
oneway interface ILightCallback {
    /**
     * enabled 和 brightness 是最终状态；servicePid 用来证明回调来自远端进程。
     */
    void onLightChanged(boolean enabled, int brightness, int servicePid);
}