package com.example.smartroom.ipc;

import com.example.smartroom.ipc.ILightCallback;

/** 二级 Binder：灯光领域的远程合同。 */
interface ILightService {
    void registerCallback(ILightCallback callback);
    void unregisterCallback(ILightCallback callback);
    int setEnabled(boolean enabled);
    int setBrightness(int brightness);
}
