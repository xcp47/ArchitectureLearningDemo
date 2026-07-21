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
 * UI 层只依赖三个本地对象：RoomConnection、LightProxy、TimerProxy。
 * 这里没有 bindService、IBinder、AIDL Stub 或 RemoteException，这就是代理层的价值。
 */
public class MainActivity extends Activity implements
        RoomConnection.Listener,
        LightProxy.Listener,
        TimerProxy.Listener {

    /*
     * Activity 只持有三个“本地对象”。真正的 bindService、IBinder 和
     * RemoteException 都被它们封装起来。
     */
    private final RoomConnection connection = RoomConnection.getInstance();
    private final LightProxy lightProxy = LightProxy.getInstance();
    private final TimerProxy timerProxy = TimerProxy.getInstance();
    private final SimpleDateFormat timeFormat = new SimpleDateFormat("HH:mm:ss.SSS", Locale.CHINA);
    private final StringBuilder logBuffer = new StringBuilder();

    // 下面这些字段只是布局中的 View 引用，集中声明便于后续更新页面。
    private TextView connectionStatus;
    private TextView processInfo;
    private TextView lightStatus;
    private TextView brightnessLabel;
    private TextView timerStatus;
    private TextView eventLog;
    private Button toggleLightButton;
    private Button start10Button;
    private Button start30Button;
    private Button cancelTimerButton;
    private SeekBar brightnessSeekBar;
    private ProgressBar timerProgress;

    /*
     * 这里缓存的是最近一次 Callback 确认的状态，而不是用户刚点击按钮时
     * 猜测的状态。远端尚未回调前，页面不会假装硬件已经完成。
     */
    private boolean lightEnabled;
    private int currentBrightness = 50;
    private int timerTotal = 30;
    private int remotePid = -1;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        // onCreate 只完成一次性的布局加载和点击事件绑定。
        setContentView(R.layout.activity_main);
        bindViews();
        bindActions();
        processInfo.setText("UI 进程 PID=" + Process.myPid() + "；远端 PID=等待回调");
        appendLog("Activity 创建，UI PID=" + Process.myPid());
    }

    @Override
    protected void onStart() {
        super.onStart();

        // 页面可见时开始观察状态，再启动 Proxy 和一级连接。
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
         * 页面不可见后成对注销 Listener。这样 Activity 不会被单例长期引用，
         * 也不会在后台继续收到 UI 更新。
         */
        lightProxy.removeListener(this);
        timerProxy.removeListener(this);
        connection.removeListener(this);
        lightProxy.stop();
        timerProxy.stop();
        connection.stop();
        super.onStop();
    }

    /** 用 findViewById 把 XML 控件保存到字段中，后续无需重复查找。 */
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

    /** 集中绑定所有按钮和滑块事件，onCreate 因而保持简洁。 */
    private void bindActions() {
        findViewById(R.id.connectButton).setOnClickListener(view -> {
            appendLog("用户请求连接一级 Binder");
            connection.start(this);
        });
        findViewById(R.id.disconnectButton).setOnClickListener(view -> {
            appendLog("用户主动断开一级 Binder");
            connection.stop();
        });
        findViewById(R.id.clearLogButton).setOnClickListener(view -> {
            logBuffer.setLength(0);
            eventLog.setText("");
        });
        findViewById(R.id.openDevicePreviewButton).setOnClickListener(view -> {
            appendLog("打开运行在 :room_core 进程的被控端效果页");
            startActivity(new Intent(this, DevicePreviewActivity.class));
        });

        toggleLightButton.setOnClickListener(view -> {
            /*
             * 这里只发送“切换”请求，不直接改 lightEnabled。
             * 等远端 onLightChanged 回来后，才更新最终状态。
             */
            int result = lightProxy.setEnabled(!lightEnabled);
            appendSyncResult("LightProxy.setEnabled(" + !lightEnabled + ")", result);
        });

        brightnessSeekBar.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
                brightnessLabel.setText("亮度：" + progress + "%");
            }

            @Override
            public void onStartTrackingTouch(SeekBar seekBar) {
            }

            @Override
            public void onStopTrackingTouch(SeekBar seekBar) {
                // 用户松手后再调用远端，避免拖动过程中产生大量 Binder 请求。
                int result = lightProxy.setBrightness(seekBar.getProgress());
                appendSyncResult("LightProxy.setBrightness(" + seekBar.getProgress() + ")", result);
            }
        });

        start10Button.setOnClickListener(view -> startTimer(10));
        start30Button.setOnClickListener(view -> startTimer(30));
        cancelTimerButton.setOnClickListener(view -> {
            int result = timerProxy.cancelTimer();
            appendSyncResult("TimerProxy.cancelTimer()", result);
        });
    }

    private void startTimer(int seconds) {
        timerTotal = seconds;
        timerProgress.setMax(seconds);
        timerProgress.setProgress(0);
        int result = timerProxy.startTimer(seconds);
        appendSyncResult("TimerProxy.startTimer(" + seconds + ")", result);
    }

    /** 记录远程方法的立即返回值，用来和稍后的异步 Callback 做对比。 */
    private void appendSyncResult(String call, int result) {
        appendLog("同步返回 ← " + call + "：" + result + "（" + ResultCode.describe(result) + "）");
    }

    @Override
    public void onConnectionStateChanged(int state, String detail) {
        // 连接层目前在主线程通知；runOnUiThread 让这个边界更明确、更安全。
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
    /** 一级 Router 就绪不等于业务就绪，Proxy 还要分别取得二级 Binder。 */
    public void onRouterReady(IServiceRouter router) {
        appendLog("一级 Binder 就绪；两个 Proxy 将分别请求 LIGHT、TIMER 二级 Binder");
    }

    @Override
    public void onRouterLost() {
        appendLog("一级 Binder 丢失；业务 Proxy 清空失效代理");
    }

    @Override
    /** 二级灯光 Binder 可用时才允许用户点击灯光相关控件。 */
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
     * 这是服务端完成操作后的最终结果。
     * 只有这里才修改本地灯光状态并刷新页面。
     */
    public void onLightChanged(boolean enabled, int brightness, int servicePid) {
        lightEnabled = enabled;
        currentBrightness = brightness;
        rememberRemotePid(servicePid);
        lightStatus.setText("灯光=" + (enabled ? "开启" : "关闭") + "，亮度=" + brightness + "%");
        if (!brightnessSeekBar.isPressed()) {
            brightnessSeekBar.setProgress(brightness);
        }
        brightnessLabel.setText("亮度：" + currentBrightness + "%");
        appendLog("异步回调 → onLightChanged(" + enabled + ", " + brightness + ")，来自 PID=" + servicePid);
    }

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
     * 每秒收到一次倒计时状态。totalSeconds 作为进度条上限，
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

    /** 同时显示两个 PID，帮助确认当前调用确实跨越了进程。 */
    private void rememberRemotePid(int servicePid) {
        remotePid = servicePid;
        processInfo.setText("UI 进程 PID=" + Process.myPid() + "；远端 :room_core PID=" + remotePid);
    }

    /** 把事件追加到页面日志，并限制长度，避免 TextView 无限增长。 */
    private void appendLog(String message) {
        runOnUiThread(() -> {
            logBuffer.append(timeFormat.format(new Date())).append("  ").append(message).append('\n');
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
