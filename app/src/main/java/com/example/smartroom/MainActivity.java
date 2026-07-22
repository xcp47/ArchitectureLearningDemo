package com.example.smartroom;

import android.app.Activity;
import android.content.Intent;
import android.graphics.Color;
import android.os.Bundle;
import android.os.Process;
import android.widget.Button;
import android.widget.ProgressBar;
import android.widget.SeekBar;
import android.widget.TextView;

import com.example.smartroom.client.LightProxy;
import com.example.smartroom.client.RoomConnection;
import com.example.smartroom.client.TimerProxy;
import com.example.smartroom.ipc.IServiceRouter;
import com.example.smartroom.ipc.ResultCode;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

/**
 * <b>主页面 — 控制端界面（运行在 UI 主进程）。</b>
 *
 * <p><b>设计原则：</b>Activity 只依赖三个本地对象——{@link RoomConnection}、
 * {@link LightProxy}、{@link TimerProxy}。
 * 这里没有 bindService、IBinder、AIDL Stub 或 RemoteException 等复杂概念，
 * 这就是 Proxy 代理层的价值——把跨进程通信的复杂性封装在底层。</p>
 *
 * <p><b>教学目的：</b></p>
 * <ul>
 *   <li>展示"同步受理 + 异步回调"的 IPC 通信模式</li>
 *   <li>展示一级 Binder（Router）和二级 Binder（灯光/计时）的分层架构</li>
 *   <li>通过 PID 展示两个进程之间真实的跨进程通信</li>
 * </ul>
 *
 * @see DevicePreviewActivity 运行在 :room_core 进程的被控端页面
 */
public class MainActivity extends Activity implements
        RoomConnection.Listener,
        LightProxy.Listener,
        TimerProxy.Listener {

    //============================================================
    // 依赖的三个本地对象（单例，由 getInstance() 获取）
    //============================================================

    /*
     * Activity 只持有三个"本地对象"，所有与远端 Service 的交互
     * （bindService、IBinder 转换、RemoteException 处理）
     * 都被它们封装在底层，Activity 不需要关心。
     */
    private final RoomConnection connection = RoomConnection.getInstance();
    private final LightProxy lightProxy = LightProxy.getInstance();
    private final TimerProxy timerProxy = TimerProxy.getInstance();

    /** 日志时间格式化器 */
    private final SimpleDateFormat timeFormat = new SimpleDateFormat("HH:mm:ss.SSS", Locale.CHINA);

    /** 事件日志的缓冲区 */
    private final StringBuilder logBuffer = new StringBuilder();

    //============================================================
    // UI 控件引用
    //============================================================

    // 集中声明所有 View 引用，便于后续在方法中更新页面
    private TextView connectionStatus;         // 连接状态文字
    private TextView processInfo;              // 进程信息文字
    private TextView lightStatus;              // 灯光状态文字
    private TextView brightnessLabel;          // 亮度标签
    private TextView timerStatus;              // 计时器状态文字
    private TextView eventLog;                 // 事件日志
    private Button toggleLightButton;          // 开关灯按钮
    private Button start10Button;              // 开始 10 秒倒计时
    private Button start30Button;              // 开始 30 秒倒计时
    private Button cancelTimerButton;          // 取消倒计时按钮
    private SeekBar brightnessSeekBar;         // 亮度滑块
    private ProgressBar timerProgress;         // 倒计时进度条

    //============================================================
    // 缓存的状态值
    //============================================================

    /*
     * 下面缓存的是最近一次 Callback 确认的状态，
     * 而不是用户刚点击按钮时猜测的状态。
     * 远端尚未回调前，页面不会假装硬件已经完成。
     * 这是"以服务端状态为准"的设计原则。
     */
    private boolean lightEnabled;         // 灯光是否开启（以回调为准）
    private int currentBrightness = 50;   // 当前亮度（以回调为准）
    private int timerTotal = 30;          // 计时总秒数
    private int remotePid = -1;           // 远端进程 ID

    //============================================================
    // Activity 生命周期
    //============================================================

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        // onCreate 只做一次性的布局加载和点击事件绑定
        setContentView(R.layout.activity_main);
        bindViews();        // 关联 XML 控件
        bindActions();      // 绑定按钮点击事件

        processInfo.setText("UI 进程 PID=" + Process.myPid() + "；远端 PID=等待回调");
        appendLog("Activity 创建，UI PID=" + Process.myPid());
    }

    @Override
    protected void onStart() {
        super.onStart();

        /*
         * 页面可见时开始观察状态。
         * 添加 Listener → 启动 Proxy → 启动连接：这个顺序保证不会漏掉补发的初始状态。
         */
        connection.addListener(this);
        lightProxy.addListener(this);
        timerProxy.addListener(this);
        lightProxy.start();
        timerProxy.start();
        connection.start(this);
    }

    @Override
    protected void onStop() {
        /*
         * 页面不可见时成对注销 Listener。
         * 这可以防止：
         * 1. Activity 被单例长期引用导致内存泄漏
         * 2. Activity 在后台继续收到 UI 更新回调浪费资源
         */
        lightProxy.removeListener(this);
        timerProxy.removeListener(this);
        connection.removeListener(this);
        lightProxy.stop();
        timerProxy.stop();
        connection.stop();
        super.onStop();
    }

    //============================================================
    // 初始化辅助方法
    //============================================================

    /** 用 findViewById 一次性关联所有 XML 控件 */
    private void bindViews() {
        connectionStatus = findViewById(R.id.connectionStatus);
        processInfo = findViewById(R.id.processInfo);
        lightStatus = findViewById(R.id.lightStatus);
        brightnessLabel = findViewById(R.id.brightnessLabel);
        timerStatus = findViewById(R.id.timerStatus);
        eventLog = findViewById(R.id.eventLog);
        toggleLightButton = findViewById(R.id.toggleLightButton);
        start10Button = findViewById(R.id.start10Button);
        start30Button = findViewById(R.id.start30Button);
        cancelTimerButton = findViewById(R.id.cancelTimerButton);
        brightnessSeekBar = findViewById(R.id.brightnessSeekBar);
        timerProgress = findViewById(R.id.timerProgress);
    }

    /** 集中绑定所有按钮点击事件和滑块的监听器 */
    private void bindActions() {
        // "连接"按钮：手动发起一级绑定
        findViewById(R.id.connectButton).setOnClickListener(view -> {
            appendLog("用户请求连接一级 Binder");
            connection.start(this);
        });

        // "断开"按钮：手动断开一级连接
        findViewById(R.id.disconnectButton).setOnClickListener(view -> {
            appendLog("用户主动断开一级 Binder");
            connection.stop();
        });

        // "清空日志"按钮
        findViewById(R.id.clearLogButton).setOnClickListener(view -> {
            logBuffer.setLength(0);
            eventLog.setText("");
        });

        // "查看被控端效果"按钮：打开 :room_core 进程的预览页面
        findViewById(R.id.openDevicePreviewButton).setOnClickListener(view -> {
            appendLog("打开运行在 :room_core 进程的被控端效果页");
            startActivity(new Intent(this, DevicePreviewActivity.class));
        });

        // "开/关灯"按钮：通过 LightProxy 发送命令
        toggleLightButton.setOnClickListener(view -> {
            /*
             * 这里只发送"切换"请求，不直接修改 lightEnabled 字段。
             * 等远端通过 onLightChanged 回调回来后，才更新最终状态。
             * 这是"以服务端确认为准"的设计原则。
             */
            int result = lightProxy.setEnabled(!lightEnabled);
            appendSyncResult("LightProxy.setEnabled(" + !lightEnabled + ")", result);
        });

        // 亮度滑块：用户松手后才触发远程调用
        brightnessSeekBar.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
                // 拖动过程中实时更新文字显示，但不发起跨进程调用
                brightnessLabel.setText("亮度：" + progress + "%");
            }

            @Override
            public void onStartTrackingTouch(SeekBar seekBar) {
            }

            @Override
            public void onStopTrackingTouch(SeekBar seekBar) {
                // 用户松手后才调用远端，避免拖动过程中产生大量 Binder 请求
                int result = lightProxy.setBrightness(seekBar.getProgress());
                appendSyncResult("LightProxy.setBrightness(" + seekBar.getProgress() + ")", result);
            }
        });

        // 倒计时按钮
        start10Button.setOnClickListener(view -> startTimer(10));
        start30Button.setOnClickListener(view -> startTimer(30));
        cancelTimerButton.setOnClickListener(view -> {
            int result = timerProxy.cancelTimer();
            appendSyncResult("TimerProxy.cancelTimer()", result);
        });
    }

    /** 启动倒计时的辅助方法 */
    private void startTimer(int seconds) {
        timerTotal = seconds;
        timerProgress.setMax(seconds);
        timerProgress.setProgress(0);
        int result = timerProxy.startTimer(seconds);
        appendSyncResult("TimerProxy.startTimer(" + seconds + ")", result);
    }

    //============================================================
    // RoomConnection.Listener 实现（一级连接状态）
    //============================================================

    /**
     * 记录远程方法的同步返回值，方便和稍后的异步 Callback 做对比。
     * 这有助于理解"同步受理 ≠ 异步执行完成"的概念。
     */
    private void appendSyncResult(String call, int result) {
        appendLog("同步返回 ← " + call + "：" + result + "（" + ResultCode.describe(result) + "）");
    }

    @Override
    public void onConnectionStateChanged(int state, String detail) {
        // runOnUiThread 确保在任何线程调用都能正确更新 UI
        runOnUiThread(() -> {
            String stateName;
            int color;
            if (state == RoomConnection.CONNECTED) {
                stateName = "已连接";
                color = getColor(R.color.success);
            } else if (state == RoomConnection.CONNECTING) {
                stateName = "连接中";
                color = Color.rgb(184, 112, 0);
            } else {
                stateName = "未连接";
                color = getColor(R.color.danger);
            }
            connectionStatus.setText(stateName + " — " + detail);
            connectionStatus.setTextColor(color);
            appendLog("连接状态：" + stateName + " / " + detail);
        });
    }

    @Override
    /**
     * 一级 Router 就绪不等于业务就绪。
     * LightProxy 和 TimerProxy 会分别通过 Router 获取各自的二级 Binder。
     * UI 可以通过 onLightAvailabilityChanged / onTimerAvailabilityChanged 知道
     * 具体哪个业务已经就绪。
     */
    public void onRouterReady(IServiceRouter router) {
        appendLog("一级 Binder 就绪；两个 Proxy 将分别请求 LIGHT、TIMER 二级 Binder");
    }

    @Override
    public void onRouterLost() {
        appendLog("一级 Binder 丢失；业务 Proxy 清空失效代理");
    }

    //============================================================
    // LightProxy.Listener 实现（二级灯光业务状态）
    //============================================================

    @Override
    /**
     * 二级灯光 Binder 的可用性通知。
     * 只有当灯光业务可用时，才允许用户点击灯光相关控件。
     */
    public void onLightAvailabilityChanged(boolean available) {
        toggleLightButton.setEnabled(available);
        brightnessSeekBar.setEnabled(available);
        if (!available) {
            lightStatus.setText("灯光二级 Binder 不可用");
        }
        appendLog("灯光二级 Binder：" + (available ? "可用" : "不可用"));
    }

    @Override
    /**
     * <b>（重要）服务端完成操作后的最终结果回调。</b>
     *
     * <p>只有在这个回调里才修改本地缓存的灯光状态并刷新页面。
     * 注意：用户点击开关灯按钮时，我们并没有立即修改 lightEnabled，
     * 而是等待服务端执行完毕推回来的这个 onLightChanged 回调。
     * 这就是"以服务端状态为准"的原则。</p>
     */
    public void onLightChanged(boolean enabled, int brightness, int servicePid) {
        lightEnabled = enabled;
        currentBrightness = brightness;
        rememberRemotePid(servicePid);
        lightStatus.setText("灯光=" + (enabled ? "开启" : "关闭") + "，亮度=" + brightness + "%");
        if (!brightnessSeekBar.isPressed()) {
            // 如果用户没有正在拖动滑块，才更新滑块位置
            brightnessSeekBar.setProgress(brightness);
        }
        brightnessLabel.setText("亮度：" + currentBrightness + "%");
        appendLog("异步回调 → onLightChanged(" + enabled + ", " + brightness + ")，来自 PID=" + servicePid);
    }

    //============================================================
    // TimerProxy.Listener 实现（二级计时业务状态）
    //============================================================

    @Override
    public void onTimerAvailabilityChanged(boolean available) {
        start10Button.setEnabled(available);
        start30Button.setEnabled(available);
        cancelTimerButton.setEnabled(available);
        if (!available) {
            timerStatus.setText("计时器二级 Binder 不可用");
        }
        appendLog("计时器二级 Binder：" + (available ? "可用" : "不可用"));
    }

    @Override
    /**
     * 每秒收到一次倒计时状态更新。
     * totalSeconds 作为进度条上限，
     * remainingSeconds 用于计算已经走过的进度。
     */
    public void onTimerChanged(int remainingSeconds, int totalSeconds, boolean running,
                               int servicePid) {
        rememberRemotePid(servicePid);
        if (totalSeconds > 0) {
            timerTotal = totalSeconds;
            timerProgress.setMax(timerTotal);
        }
        timerStatus.setText(running ? "剩余 " + remainingSeconds + " 秒" :
                (remainingSeconds == 0 ? "未运行 / 已结束" : "已暂停"));
        timerProgress.setProgress(running ? Math.max(0, timerTotal - remainingSeconds) : 0);
        appendLog("异步回调 → onTimerChanged(" + remainingSeconds + "/" + totalSeconds
                + ", running=" + running + ")，来自 PID=" + servicePid);
    }

    //============================================================
    // 工具方法
    //============================================================

    /**
     * 同时显示两个 PID，帮助确认当前调用确实跨越了进程。
     * 如果 UI 进程 PID 和远端 PID 不同，就说明通信确实走了跨进程 Binder。
     */
    private void rememberRemotePid(int servicePid) {
        remotePid = servicePid;
        processInfo.setText("UI 进程 PID=" + Process.myPid() + "；远端 :room_core PID=" + remotePid);
    }

    /**
     * 把事件追加到页面底部的日志区，并限制长度避免 TextView 无限增长。
     * 日志格式：时间戳 + 消息，按时间顺序排列。
     */
    private void appendLog(String message) {
        runOnUiThread(() -> {
            logBuffer.append(timeFormat.format(new Date())).append("  ").append(message).append('\n');
            // 当日志超过 9000 字符时，裁剪掉最旧的部分
            if (logBuffer.length() > 9000) {
                int firstLineEnd = logBuffer.indexOf("\n", 1500);
                if (firstLineEnd > 0) {
                    logBuffer.delete(0, firstLineEnd + 1);
                }
            }
            eventLog.setText(logBuffer.toString());
        });
    }
}
