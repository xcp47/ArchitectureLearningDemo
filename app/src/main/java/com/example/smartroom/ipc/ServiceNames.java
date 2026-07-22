package com.example.smartroom.ipc;

/**
 * 二级 Binder 的"分机号"常量表。
 *
 * <p><b>为什么需要这个类？</b></p>
 * <ul>
 *   <li>客户端调用 {@link IServiceRouter#getService(String)} 时，
 *       必须传入服务端能识别的名字。两边用同一个常量可以避免拼写错误。</li>
 *   <li>如果有一天需要重命名服务（例如 LIGHT → LAMP），
 *       只需要改这一个地方，不用担心遗漏。</li>
 * </ul>
 *
 * <p><b>用法：</b>客户端和服务端都引用这里的常量，而不是硬编码字符串。
 * <pre>{@code
 * // 正确：
 * IBinder binder = router.getService(ServiceNames.LIGHT);
 * // 错误：
 * IBinder binder = router.getService("light"); // 拼写不一致可能导致找不到！
 * }</pre>
 *
 * @see com.example.smartroom.service.RoomCoreService 服务端根据这些常量返回对应的 Binder
 */
public final class ServiceNames {

    // 客户端拿着这些名称去调用 IServiceRouter.getService(...)
    // 服务端在 RoomCoreService.routerBinder 中根据名称返回对应的二级 Binder

    /** 灯光服务的分机号 */
    public static final String LIGHT = "LIGHT";

    /** 计时服务的分机号 */
    public static final String TIMER = "TIMER";

    // 工具类只保存常量，不需要创建对象，所以构造方法设为 private
    private ServiceNames() {
    }
}