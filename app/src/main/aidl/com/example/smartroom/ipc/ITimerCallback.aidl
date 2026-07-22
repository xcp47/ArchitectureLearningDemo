package com.example.smartroom.ipc;

/**
 * <b>倒计时状态异步回调接口（服务端 → 客户端的反向通知）。</b>
 *
 * <p><b>回调频率：</b>服务端每秒调用一次此接口，推送最新的倒计时数据。
 * 客户端 UI 收到回调后更新 {@code XX / XX} 格式的数字时钟。</p>
 *
 * <p><b>为什么同时传递 remainingSeconds 和 totalSeconds？</b>
 * 因为 totalSeconds 让 UI 知道"这次的倒计时总长是多少"，
 * 从而能计算出进度百分比；remainingSeconds 则是当前剩余秒数。
 * 这样新加入的观察者也能正确显示完整信息，而不需要额外查询。</p>
 *
 * @see com.example.smartroom.client.TimerProxy 客户端定义了这个 callback 的具体行为
 * @see com.example.smartroom.service.RoomCoreService.TimerBinder 服务端每秒调用一次
 */
oneway interface ITimerCallback {
    /**
     * 倒计时状态更新通知。
     *
     * @param remainingSeconds 剩余秒数
     * @param totalSeconds 本次倒计时的总秒数
     * @param running true 表示倒计时正在运行，false 表示已停止或从未开始
     * @param servicePid 服务端进程 ID，用于教学演示确认跨进程通信
     */
    void onTimerChanged(int remainingSeconds, int totalSeconds, boolean running, int servicePid);
}