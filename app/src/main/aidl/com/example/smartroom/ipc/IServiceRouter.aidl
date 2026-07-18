package com.example.smartroom.ipc;

/** 一级 Binder：只按服务名返回二级业务 Binder，不处理具体业务。 */
interface IServiceRouter {
    IBinder getService(String serviceName);
}
