package com.example.smartroom;

import android.app.Activity;
import android.graphics.Color;
import android.os.Bundle;
import android.os.Process;
import android.view.View;
import android.widget.TextView;

import com.example.smartroom.client.LightProxy;
import com.example.smartroom.client.RoomConnection;
import com.example.smartroom.client.TimerProxy;
import com.example.smartroom.ipc.IServiceRouter;

import java.util.Locale;

/**
 * 运行在 :room_core 进程的被控端模拟屏。
 *
 * <p>它不主动修改灯光或倒计时，只订阅 RoomCoreService 的最终状态。这样页面
 * 展示的是被控端真正执行后的结果，而不是控制按钮触发时在本地伪造的效果。</p>
 */
public final class DevicePreviewActivity extends Activity implements
        RoomConnection.Listener,
        LightProxy.Listener,
        TimerProxy.Listener {

    private final RoomConnection connection = RoomConnection.getInstance();
    private final LightProxy lightProxy = LightProxy.getInstance();
    private final TimerProxy timerProxy = TimerProxy.getInstance();

    private View roomPanel;
    private View lightOrb;
    private TextView deviceConnectionStatus;
    private TextView deviceProcessInfo;
    private TextView brightMode;
    private TextView darkMode;
    private TextView roomStatus;
    private TextView brightnessStatus;
    private TextView timerDigits;
    private TextView timerRunState;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_device_preview);
        bindViews();
        findViewById(R.id.closePreviewButton).setOnClickListener(view -> finish());
        deviceProcessInfo.setText("被控端页面 PID=" + Process.myPid());
        renderLight(false, 0);
        renderTimer(0, 0, false);
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
        roomPanel = findViewById(R.id.roomPanel);
        lightOrb = findViewById(R.id.lightOrb);
        deviceConnectionStatus = findViewById(R.id.deviceConnectionStatus);
        deviceProcessInfo = findViewById(R.id.deviceProcessInfo);
        brightMode = findViewById(R.id.brightMode);
        darkMode = findViewById(R.id.darkMode);
        roomStatus = findViewById(R.id.roomStatus);
        brightnessStatus = findViewById(R.id.deviceBrightnessStatus);
        timerDigits = findViewById(R.id.timerDigits);
        timerRunState = findViewById(R.id.timerRunState);
    }

    @Override
    public void onConnectionStateChanged(int state, String detail) {
        if (state == RoomConnection.CONNECTED) {
            deviceConnectionStatus.setText("设备服务已连接");
            deviceConnectionStatus.setTextColor(getColor(R.color.success));
        } else if (state == RoomConnection.CONNECTING) {
            deviceConnectionStatus.setText("正在连接设备服务…");
            deviceConnectionStatus.setTextColor(Color.rgb(184, 112, 0));
        } else {
            deviceConnectionStatus.setText("设备服务未连接 — " + detail);
            deviceConnectionStatus.setTextColor(getColor(R.color.danger));
        }
    }

    @Override
    public void onRouterReady(IServiceRouter router) {
        // 两个 Proxy 会从 Router 分别取得 LIGHT 和 TIMER Binder。
    }

    @Override
    public void onRouterLost() {
        deviceConnectionStatus.setText("设备服务连接已丢失");
        deviceConnectionStatus.setTextColor(getColor(R.color.danger));
    }

    @Override
    public void onLightAvailabilityChanged(boolean available) {
        if (!available) {
            roomStatus.setText("等待灯光状态…");
        }
    }

    @Override
    public void onLightChanged(boolean enabled, int brightness, int servicePid) {
        deviceProcessInfo.setText("被控端页面 / Service PID=" + servicePid);
        renderLight(enabled, brightness);
    }

    @Override
    public void onTimerAvailabilityChanged(boolean available) {
        if (!available) {
            timerRunState.setText("等待计时服务…");
        }
    }

    @Override
    public void onTimerChanged(int remainingSeconds, int totalSeconds, boolean running,
                               int servicePid) {
        deviceProcessInfo.setText("被控端页面 / Service PID=" + servicePid);
        renderTimer(remainingSeconds, totalSeconds, running);
    }

    private void renderLight(boolean enabled, int brightness) {
        roomPanel.setBackgroundResource(enabled
                ? R.drawable.device_room_bright
                : R.drawable.device_room_dark);
        lightOrb.setBackgroundResource(enabled
                ? R.drawable.light_orb_on
                : R.drawable.light_orb_off);

        brightMode.setBackgroundResource(enabled
                ? R.drawable.mode_selected_background
                : R.drawable.mode_unselected_background);
        darkMode.setBackgroundResource(enabled
                ? R.drawable.mode_unselected_background
                : R.drawable.mode_selected_background);
        brightMode.setTextColor(enabled ? Color.WHITE : getColor(R.color.text_secondary));
        darkMode.setTextColor(enabled ? getColor(R.color.text_secondary) : Color.WHITE);

        roomStatus.setText(enabled ? "房间灯光已开启" : "房间灯光已关闭");
        roomStatus.setTextColor(enabled ? getColor(R.color.text_primary) : Color.WHITE);
        brightnessStatus.setText("亮度 " + brightness + "%");
        brightnessStatus.setTextColor(enabled
                ? getColor(R.color.text_secondary)
                : Color.rgb(190, 200, 216));
    }

    private void renderTimer(int remainingSeconds, int totalSeconds, boolean running) {
        timerDigits.setText(String.format(Locale.CHINA, "%02d / %02d",
                Math.max(0, remainingSeconds), Math.max(0, totalSeconds)));
        if (running) {
            timerRunState.setText("计时中 · 状态由 TimerBinder 每秒推送");
            timerRunState.setTextColor(getColor(R.color.success));
        } else if (totalSeconds > 0 && remainingSeconds == 0) {
            timerRunState.setText("倒计时已结束或已取消");
            timerRunState.setTextColor(getColor(R.color.text_secondary));
        } else {
            timerRunState.setText("等待控制端启动倒计时");
            timerRunState.setTextColor(getColor(R.color.text_secondary));
        }
    }
}