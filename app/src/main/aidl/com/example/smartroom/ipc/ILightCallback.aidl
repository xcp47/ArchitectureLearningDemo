package com.example.smartroom.ipc;

/** 服务端完成灯光操作后，通过这个反向 Binder 通知客户端。 */
oneway interface ILightCallback {
    void onLightChanged(boolean enabled, int brightness, int servicePid);
}
