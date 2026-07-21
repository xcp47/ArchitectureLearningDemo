package com.example.smartroom.ipc;

/**
 * 一级 Binder，也就是“总机”。
 * 客户端先取得这个接口，再按服务名获得某个具体业务的二级 Binder。
 */
interface IServiceRouter {
    /** serviceName 例如 LIGHT 或 TIMER；找不到时返回 null。 */
    IBinder getService(String serviceName);
}