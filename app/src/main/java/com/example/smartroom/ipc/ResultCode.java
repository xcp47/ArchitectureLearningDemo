package com.example.smartroom.ipc;

/** 同步返回值只说明请求是否被 Binder 服务受理，最终状态看异步回调。 */
public final class ResultCode {
    public static final int ACCEPTED = 0;
    public static final int INVALID_ARGUMENT = -2;
    public static final int NOT_CONNECTED = -10;
    public static final int REMOTE_ERROR = -11;

    private ResultCode() {
    }

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
