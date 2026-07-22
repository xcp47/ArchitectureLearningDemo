package com.example.smartroom.ipc;

/**
 * 远程方法的<strong>同步</strong>返回码（约定俗成的常量表）。
 *
 * <p><b>设计说明：</b>所有 AIDL 同步方法都返回一个 int 表示"受理结果"，
 * 而不是直接返回业务数据。真正的"最终状态"由服务端通过异步 Callback 推送。
 * 这种"同步受理 + 异步回调"的模式在 Android IPC 中很常见。</p>
 *
 * <p><b>示例流程：</b>LightProxy.setEnabled(true) → 服务端返回 ACCEPTED(0)
 * → 250ms 后服务端完成硬件模拟 → 通过 ILightCallback.onLightChanged() 通知客户端。
 * 页面上看到灯光变化的是第二步的回调，不是第一步的同步返回。</p>
 *
 * <p><b>特别注意：</b>ACCEPTED(0) 和传统 HTTP 200 OK 的含义类似——
 * 只表示"请求收到并且格式正确"，不表示"请求已经执行完毕"。</p>
 *
 * @see com.example.smartroom.client.LightProxy#setEnabled(boolean)
 * @see com.example.smartroom.ipc.ILightCallback
 */
public final class ResultCode {

    /**
     * 请求已被服务端接受，正在排队等待执行。
     * 这是最常见的返回值，表示参数校验通过、命令已加入服务端执行队列。
     * 最终的执行结果需要等待异步 Callback 通知。
     */
    public static final int ACCEPTED = 0;

    /**
     * 参数不合法，服务端拒绝了本次请求。
     * 例如设置亮度时传入了 -1 或 101（亮度允许范围是 0～100）。
     * 此时不会产生异步回调，因为请求根本没被处理。
     */
    public static final int INVALID_ARGUMENT = -2;

    /**
     * 业务二级 Binder 尚未就绪。
     * 通常发生在客户端刚刚启动、连接尚未建立时，
     * 或者远端进程崩溃后正在重连的过程中。
     */
    public static final int NOT_CONNECTED = -10;

    /**
     * 远程调用过程中发生异常（如远端进程崩溃）。
     * 此时 RoomConnection 会自动触发重新绑定机制。
     * 应用层只需根据返回值提示用户稍后重试。
     */
    public static final int REMOTE_ERROR = -11;

    // 工具类只提供静态常量和静态方法，不允许外部创建 ResultCode 实例
    private ResultCode() {
    }

    /**
     * 把数字返回码转换为人类可读的中文描述，方便在日志或 UI 中展示。
     *
     * @param code 从远程方法接收到的 int 返回值
     * @return 中文描述字符串
     */
    public static String describe(int code) {
        switch (code) {
            case ACCEPTED:
                return "请求已受理（等异步回调确认最终状态）";
            case INVALID_ARGUMENT:
                return "参数非法（超出服务端允许范围）";
            case NOT_CONNECTED:
                return "业务 Binder 未连接（请等待连接就绪）";
            case REMOTE_ERROR:
                return "远端进程调用失败（即将自动重连）";
            default:
                return "未知返回码 " + code;
        }
    }
}