package com.example.smartroom.ipc;

/**
 * 二级 Binder 的“分机号”。
 * 客户端与服务端只能引用这里的常量，避免一边写 LIGHT、一边误写成 LAMP。
 */
public final class ServiceNames {
    // 客户端拿着这些名称去调用 IServiceRouter.getService(...)。
    public static final String LIGHT = "LIGHT";
    public static final String TIMER = "TIMER";

    // 工具类只保存常量，不需要创建对象，所以构造方法设为 private。
    private ServiceNames() {
    }
}