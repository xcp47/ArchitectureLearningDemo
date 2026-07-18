package com.example.smartroom;

import android.app.Activity;
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

    private final RoomConnection connection = RoomConnection.getInstance();
    private final LightProxy lightProxy = LightProxy.getInstance();
    private final TimerProxy timerProxy = TimerProxy.getInstance();
    private final SimpleDateFormat timeFormat = new SimpleDateFormat("HH:mm:ss.SSS", Locale.CHINA);
    private final StringBuilder logBuffer = new StringBuilder();

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

    private boolean lightEnabled;
    private int currentBrightness = 50;
    private int timerTotal = 30;
    private int remotePid = -1;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);
        bindViews();
        bindActions();
        processInfo.setText("UI 进程 PID=" + Process.myPid() + "；远端 PID=等待回调");
        appendLog("Activity 创建，UI PID=" + Process.myPid());
    }

    @Override
    protected void onStart() {
        super.onStart();
        connection.addListener(this);
        lightProxy.addListener(this);
        timerProxy.addListener(this);
        lightProxy.start();
        timerProxy.start();
        connection.start(this);
    }

    @Override
    protected void onStop() {
        lightProxy.removeListener(this);
        timerProxy.removeListener(this);
        connection.removeListener(this);
        lightProxy.stop();
        timerProxy.stop();
        connection.stop();
        super.onStop();
    }

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

        toggleLightButton.setOnClickListener(view -> {
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

    private void appendSyncResult(String call, int result) {
        appendLog("同步返回 ← " + call + "：" + result + "（" + ResultCode.describe(result) + "）");
    }

    @Override
    public void onConnectionStateChanged(int state, String detail) {
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
    public void onRouterReady(IServiceRouter router) {
        appendLog("一级 Binder 就绪；两个 Proxy 将分别请求 LIGHT、TIMER 二级 Binder");
    }

    @Override
    public void onRouterLost() {
        appendLog("一级 Binder 丢失；业务 Proxy 清空失效代理");
    }

    @Override
    public void onLightAvailabilityChanged(boolean available) {
        toggleLightButton.setEnabled(available);
        brightnessSeekBar.setEnabled(available);
        if (!available) {
            lightStatus.setText("灯光二级 Binder 不可用");
        }
        appendLog("灯光二级 Binder：" + (available ? "可用" : "不可用"));
    }

    @Override
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
    public void onTimerChanged(int remainingSeconds, boolean running, int servicePid) {
        rememberRemotePid(servicePid);
        if (running && remainingSeconds > timerTotal) {
            timerTotal = remainingSeconds;
            timerProgress.setMax(timerTotal);
        }
        timerStatus.setText(running ? "剩余 " + remainingSeconds + " 秒" :
                (remainingSeconds == 0 ? "未运行 / 已结束" : "已暂停"));
        timerProgress.setProgress(running ? Math.max(0, timerTotal - remainingSeconds) : 0);
        appendLog("异步回调 → onTimerChanged(" + remainingSeconds + ", running=" + running
                + ")，来自 PID=" + servicePid);
    }

    private void rememberRemotePid(int servicePid) {
        remotePid = servicePid;
        processInfo.setText("UI 进程 PID=" + Process.myPid() + "；远端 :room_core PID=" + remotePid);
    }

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
