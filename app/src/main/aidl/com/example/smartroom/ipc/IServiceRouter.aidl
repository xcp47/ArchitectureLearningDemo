package com.example.smartroom.ipc;

/**
 * <b>一级 Binder（总机/路由器）。</b>
 *
 * <p>这是一个"服务发现"接口，类似于电话总机。
 * 客户端绑定 RoomCoreService 后，首先拿到的是 IServiceRouter，
 * 然后通过它来查找具体的业务接口。</p>
 *
 * <p><b>为什么需要两级 Binder？</b>
 * 如果只有一个 Binder 接口，所有业务方法（灯光 + 计时 + 未来更多功能）
 * 都得挤在同一个接口里，接口会变得越来越臃肿。
 * 通过"总机 → 分机"模式，每个领域有自己独立的 AIDL 接口，
 * 互不干扰，方便扩展新业务。</p>
 *
 * <p><b>工作流程：</b>
 * <ol>
 *   <li>客户端 {@code bindService()} → 拿到 {@code IServiceRouter}</li>
 *   <li>调用 {@code getService("LIGHT")} → 拿到 {@code IBinder}（灯光二级 Binder）</li>
 *   <li>调用 {@code ILightService.Stub.asInterface(binder)} → 强类型灯光接口</li>
 * </ol></p>
 *
 * @see com.example.smartroom.service.RoomCoreService 实现了这个接口
 * @see com.example.smartroom.client.BaseFeatureProxy 客户端通过它获取二级 Binder
 */
interface IServiceRouter {
    /**
     * 根据服务名获取对应的二级 Binder。
     *
     * @param serviceName 服务名，如 {@link ServiceNames#LIGHT} 或 {@link ServiceNames#TIMER}
     * @return 对应业务的 IBinder 对象；如果找不到对应的服务则返回 null
     */
    IBinder getService(String serviceName);
}