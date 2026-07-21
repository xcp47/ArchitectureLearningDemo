package com.example.smartroom.ipc;

/**
 * 远程方法的同步返回码。
 *
 * <p>请注意：同步返回值只说明“请求有没有被受理”，最终状态要看异步 Callback。
 * 例如开灯返回 ACCEPTED 时，灯光可能还要等待模拟硬件执行 250ms。</p>
 */
public final class ResultCode {
    /** 参数合法，服务端已经把任务加入执行队列。 */
    public static final int ACCEPTED = 0;

    /** 参数超出服务端允许范围，例如亮度不是 0～100。 */
    public static final int INVALID_ARGUMENT = -2;

    /** Proxy 还没有取得对应的二级 Binder。 */
    public static final int NOT_CONNECTED = -10;

    /** Binder 调用过程中远端进程发生异常或已经断开。 */
    public static final int REMOTE_ERROR = -11;

    // 这里只提供静态常量和方法，不允许 new ResultCode()。
    private ResultCode() {
    }

    /** 把数字返回码转换为适合展示给初学者阅读的文字。 */
    public static String describe(int code) {
        switch (code) {
            case ACCEPTED:
                return "请求已受理";
            case INVALID_ARGUMENT:
                return "参数非法";
            case NOT_CONNECTED:
                return "业务 Binder 未连接";
            case REMOTE_ERROR:
                return "远端进程调用失败";
            default:
                return "未知返回码 " + code;
        }
    }
}