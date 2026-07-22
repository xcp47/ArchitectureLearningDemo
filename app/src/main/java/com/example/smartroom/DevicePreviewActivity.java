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
 * <b>被控端模拟屏（运行在 :room_core 进程）。</b>
 *
 * <p><b>设计目的：</b>展示 RoomCoreService 所在进程的 UI。
 * 这个 Activity 虽然和 Service 在同一个进程（:room_core），
 * 但仍沿用 Connection + Proxy + Listener 的分层架构，
 * 证明这套分层方式在同一进程内也同样适用。</p>
 *
 * <p><b>关键区别：</b></p>
 * <ul>
 *   <li>控制端（MainActivity）—— 点击按钮发起请求，改变灯光/计时状态</li>
 *   <li>被控端（DevicePreviewActivity）—— 只展示最终状态，不主动修改任何设置</li>
 *   <li>被控端页面显示的是服务端真正执行后的结果，而不是按钮触发时在本地伪造的效果</li>
 * </ul>
 *
 * <p><b>双进程验证：</b>页面顶部的 PID 信息会显示当前进程 ID 和服务端进程 ID。
 * 如果看到两个不同的 PID，就说明通信确实跨越了进程边界。</p>
 *
 * @see MainActivity 运行在主进程的控制端 Activity
 */
public final class DevicePreviewActivity extends Activity implements
        RoomConnection.Listener,
        LightProxy.Listener,
        TimerProxy.Listener {

    /*
     * 虽然 Activity 和 Service 在同一个 :room_core 进程，
     * 但仍使用 Connection + Proxy + Listener 的模式。
     * 这演示了 proxy 层在单一进程中也能正常工作（AIDL 的 asInterface
     * 在同一个进程时返回本地对象，不走跨进程序列化）。
     */
    private final RoomConnection connection = RoomConnection.getInstance();
    private final LightProxy lightProxy = LightProxy.getInstance();
    private final TimerProxy timerProxy = TimerProxy.getInstance();

    //============================================================
    // UI 控件引用
    //============================================================

    // 布局 activity_device_preview.xml 中的各个控件
    private View roomPanel;              // 房间背景区域
    private View lightOrb;               // 灯泡图标
    private TextView deviceConnectionStatus;  // 连接状态文字
    private TextView deviceProcessInfo;       // 进程信息文字
    private TextView brightMode;         // "明亮模式"标签
    private TextView darkMode;           // "暗黑模式"标签
    private TextView roomStatus;         // 房间状态文字
    private TextView brightnessStatus;   // 亮度百分比文字
    private TextView timerDigits;        // 倒计时数字（XX / XX）
    private TextView timerRunState;      // 倒计时运行状态文字

    //============================================================
    // Activity 生命周期
    //============================================================

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        // 初次创建时先显示默认暗色和 00 / 00，
        // 等 Callback 到达后再替换为真实状态。
        setContentView(R.layout.activity_device_preview);
        bindViews();

        // "关闭"按钮点击后返回控制端页面
        findViewById(R.id.closePreviewButton).setOnClickListener(view -> finish());

        // 显示当前进程 ID，与回调中的 servicePid 做对比
        deviceProcessInfo.setText("被控端页面 PID=" + Process.myPid());

        // 初始状态：灯光关闭、亮度 0
        renderLight(false, 0);
        renderTimer(0, 0, false);
    }

    @Override
    protected void onStart() {
        super.onStart();

        /*
         * 注册顺序很关键：先注册 Listener，再启动 Proxy，
         * 可以确保不会漏掉 addListener 时补发的初始状态回调。
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
        // 与 onStart 成对清理，避免不可见页面继续接收回调更新
        lightProxy.removeListener(this);
        timerProxy.removeListener(this);
        connection.removeListener(this);
        lightProxy.stop();
        timerProxy.stop();
        connection.stop();
        super.onStop();
    }

    /** 把所有 View 引用与 XML 控件关联起来 */
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

    //============================================================
    // RoomConnection.Listener 实现
    //============================================================

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
        // 主连接就绪后，两个业务 Proxy 会自动通过 router 获取各自的二级 Binder
    }

    @Override
    public void onRouterLost() {
        deviceConnectionStatus.setText("设备服务连接已丢失");
        deviceConnectionStatus.setTextColor(getColor(R.color.danger));
    }

    //============================================================
    // LightProxy.Listener 实现
    //============================================================

    @Override
    public void onLightAvailabilityChanged(boolean available) {
        if (!available) {
            roomStatus.setText("等待灯光状态…");
        }
    }

    @Override
    /**
     * <b>最终灯光状态到达。</b>
     * 被控端页面只在收到这个回调后才更新明暗视觉效果。
     * 这与控制端（由按钮触发）形成对比——被控端完全被动，只反映最终结果。
     */
    public void onLightChanged(boolean enabled, int brightness, int servicePid) {
        deviceProcessInfo.setText("被控端页面 / Service PID=" + servicePid);
        renderLight(enabled, brightness);
    }

    //============================================================
    // TimerProxy.Listener 实现
    //============================================================

    @Override
    public void onTimerAvailabilityChanged(boolean available) {
        if (!available) {
            timerRunState.setText("等待计时服务…");
        }
    }

    @Override
    /**
     * TimerBinder 每秒推送一次倒计时状态，页面据此重画 XX / XX 数字。
     * 被控端页面只负责展示，不提供计时控制按钮。
     */
    public void onTimerChanged(int remainingSeconds, int totalSeconds, boolean running,
                               int servicePid) {
        deviceProcessInfo.setText("被控端页面 / Service PID=" + servicePid);
        renderTimer(remainingSeconds, totalSeconds, running);
    }

    //============================================================
    // UI 渲染方法
    //============================================================

    /**
     * 根据 enabled 切换明亮/暗黑两套背景和配色方案。
     * 亮度值只展示服务端确认后的结果，不展示用户猜测的数值。
     */
    private void renderLight(boolean enabled, int brightness) {
        // 房间背景：开灯 → 明亮背景 / 关灯 → 暗色背景
        roomPanel.setBackgroundResource(enabled
                ? R.drawable.device_room_bright
                : R.drawable.device_room_dark);

        // 灯泡图标：开灯 → 发光球 / 关灯 → 灰色球
        lightOrb.setBackgroundResource(enabled
                ? R.drawable.light_orb_on
                : R.drawable.light_orb_off);

        // 明亮/暗黑模式标签的切换
        brightMode.setBackgroundResource(enabled
                ? R.drawable.mode_selected_background
                : R.drawable.mode_unselected_background);
        darkMode.setBackgroundResource(enabled
                ? R.drawable.mode_unselected_background
                : R.drawable.mode_selected_background);
        brightMode.setTextColor(enabled ? Color.WHITE : getColor(R.color.text_secondary));
        darkMode.setTextColor(enabled ? getColor(R.color.text_secondary) : Color.WHITE);

        // 文字状态
        roomStatus.setText(enabled ? "房间灯光已开启" : "房间灯光已关闭");
        roomStatus.setTextColor(enabled ? getColor(R.color.text_primary) : Color.WHITE);
        brightnessStatus.setText("亮度 " + brightness + "%");
        brightnessStatus.setTextColor(enabled
                ? getColor(R.color.text_secondary)
                : Color.rgb(190, 200, 216));
    }

    /**
     * 渲染倒计时数字和状态文字。
     * %02d 格式化保证个位数时前面补 0（如 08 / 10），形成稳定的双数字布局。
     */
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