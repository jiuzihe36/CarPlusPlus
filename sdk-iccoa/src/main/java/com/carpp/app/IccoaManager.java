package com.carpp.app;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.Handler;
import android.os.Looper;
import android.util.Base64;
import android.util.Log;
import android.view.Surface;

import com.share.connect.WifiOwnerConfig;
import com.ucar.vehiclesdk.ICarConnectListener;
import com.ucar.vehiclesdk.ICarInitCallback;
import com.ucar.vehiclesdk.IPhoneDataListener;
import com.ucar.vehiclesdk.UCarAdapter;
import com.ucar.vehiclesdk.UCarCommon;
import com.ucar.vehiclesdk.UCarConfig;
import com.ucar.vehiclesdk.common.UCarNotification;

import java.util.Collections;
import java.util.List;
import java.util.Random;

/**
 * Car++ 车机端 ICCOA CarLink（小米 CarWith）SDK 生命周期管理器。
 *
 * <p>本类只管 SDK：初始化 / 广播 / 投屏 Surface / 回调转发。UI 与 BYD 输出通过
 * {@link Listener} 解耦（由 Activity 或 {@code IccoaNavigationBridge} 实现）。
 *
 * <p><b>★ init-once 状态机</b>：{@link UCarAdapter#init} 在 SDK 内部是「进程一次性」
 * （{@code sIsServiceInitialized} 守门）。重启只 {@code startAdvertise()}，绝不重复 init，
 * 否则 BLE 广播状态残留，手机扫不到、{@code onPinCode} 不再触发。
 * {@link #stop()} 断开但保留初始化标志。
 */
public class IccoaManager {

    private static final String TAG = "Car++/ICCOA";

    /** 事件回调。由 UI / 导航桥接实现。 */
    public interface Listener {
        void onLog(String msg);
        /** 已连接状态变化：0 断开 / 1 连接中 / 2 已连接 / 3 已建立 */
        void onConnectionState(int state);
        void onPinCode(String pinCode, String carDeviceName);
        /** 协商阶段的真实连接进度（0–100）；仅在手机配对 / 建立数据通道时回调。 */
        void onConnectingProgress(int progress);
        void onCastStarted();
        /** 手机导航信息（POC-2 / BYD 仪表与 HUD 的数据源）。 */
        void onNavigation(UCarCommon.NavigationInfo info);
        /** 手机新增 / 更新了一条通知。 */
        void onNotificationAdded(UCarNotification notification);
        /** 手机上把通知划掉了（用户在手机侧操作）。 */
        void onNotificationRemoved(List<Integer> ids);
    }

    private final Context appCtx;
    private final Listener listener;
    private final Handler main = new Handler(Looper.getMainLooper());

    private UCarAdapter adapter = null;
    private Surface surface = null;

    /** {@link #tryStartCast()} 的重试预算（×500ms = 最长 10 秒）。 */
    private static final int CAST_RETRY_MAX = 20;
    private int castRetry = 0;

    // ---- 状态机 ----
    private volatile boolean sdkInitialized = false;
    private volatile boolean sdkReady = false;
    private volatile boolean castStarted = false;
    private volatile String lastPinCode = null;

    /** 会话结束后重新广播的延时：留出 SDK 内部收尾时间，避免与断连流程竞争。 */
    private static final long RE_ADVERTISE_DELAY_MS = 1200L;

    /**
     * 配对码自动刷新周期。
     *
     * <p>车机一直在广播并不足以把手机叫回来：BLE 广播帧内容不变时，手机端 CarWith 不会重新
     * 弹出配对界面。用户一旦关掉那个弹窗，之后就必须手动去 CarWith 里找车机才能重连。
     * 荣耀车联 / HiCar 的车机端做法是周期性重播，本常量即那个周期。
     */
    private static final long AUTO_REFRESH_INTERVAL_MS = 30_000L;

    /** 会话是否正在进行（手机连接中或已连接）。由连接回调维护，看门狗据此避让。 */
    private volatile boolean sessionActive = false;

    /**
     * 会话结束后的重新广播。
     *
     * <p>车机端必须一直可被发现：手机断开后若不再广播，用户就得手动重启 App 才能重连。
     * 重新广播时会清掉旧配对码，手机因此走一次全新配对并下发【新的】配对码 —— 这就是
     * 「断开后自动刷新链接码」。
     */
    private final Runnable reAdvertiseRunnable = () -> {
        if (sdkReady && adapter != null) {
            log("会话结束 → 重新广播（等待手机再次连接，届时下发新的配对码）");
            reAdvertise();
        }
    };

    private void scheduleReAdvertise() {
        main.removeCallbacks(reAdvertiseRunnable);
        main.postDelayed(reAdvertiseRunnable, RE_ADVERTISE_DELAY_MS);
    }

    /**
     * 配对码看门狗：每隔 {@link #AUTO_REFRESH_INTERVAL_MS} 检查一次，仍未连上就重播一次。
     *
     * <p>这是「刷新出来的新码没人来连，之后就再也不刷新」的解法 —— 那种情况下手机端弹窗
     * 已经关掉，车机只能靠重播把它重新叫出来，否则用户点开 CarWith 也发现不了车机。
     *
     * <p>避让规则：手机正在连接或已连上（{@link #sessionActive}）、或画面已经投出去
     * （{@code castStarted}）时一律跳过，绝不打断进行中的会话。新码下发时计时会重置，
     * 保证用户拿到完整的 30 秒去输入。
     */
    private final Runnable autoRefreshRunnable = () -> {
        if (!sdkReady || adapter == null) return;
        if (sessionActive || castStarted) {
            log("自动刷新：会话进行中，跳过本轮");
            scheduleAutoRefresh();
            return;
        }
        log("★ 自动刷新：超过 " + (AUTO_REFRESH_INTERVAL_MS / 1000) + " 秒未连接 → 重播以重新触发手机弹窗");
        reAdvertise();
        scheduleAutoRefresh();
    };

    private void scheduleAutoRefresh() {
        main.removeCallbacks(autoRefreshRunnable);
        main.postDelayed(autoRefreshRunnable, AUTO_REFRESH_INTERVAL_MS);
    }

    private void cancelAutoRefresh() {
        main.removeCallbacks(autoRefreshRunnable);
    }

    // ---- 屏幕尺寸 ----
    private int screenW = 1920, screenH = 720, videoW = 1920, videoH = 720;

    /**
     * 投屏画面所在 View 的像素尺寸（0 = 还不知道，回落到面板尺寸）。
     *
     * <p>SDK 的 {@code UCarSurfaceView} 上报的是 View 坐标，UIBC 下发前 SDK 会按
     * 「显示区 → 码流」缩放，而显示区尺寸就是 {@code startCast(surface, w, h)} 的 w/h。
     * 面板分辨率与 View 尺寸并不总是相等（车机横向、测试机纵向时正好差 90°），
     * 用错了点到的位置和画面里的位置就对不上。
     */
    private int viewW = 0, viewH = 0;

    public IccoaManager(Context ctx, Listener listener) {
        this.appCtx = ctx.getApplicationContext();
        this.listener = listener;
    }

    public void setScreenSize(int w, int h) {
        screenW = w; screenH = h; videoW = w; videoH = h;
    }

    /**
     * 投屏 View 的像素尺寸 = 触摸坐标空间。必须在投屏开始前调用（SDK 只在
     * {@code startCast} 里设置 UIBC 的显示区尺寸，之后没有单独的入口改它）。
     */
    public void setProjectionViewSize(int w, int h) {
        if (w <= 0 || h <= 0) return;
        if (w == viewW && h == viewH) return;
        // SDK 只在 startCast 里设置 UIBC 的显示区尺寸，投屏起来之后没有入口改它：
        // 画面区域变了（旋转 / 分屏）而投屏已经在跑，触摸坐标就会按旧尺寸换算而整体偏移，
        // 只能断开重连。投影页因此锁了横向（车机本身就是横向画布），这里再吼一声方便定位。
        if (castStarted) {
            log("! 投屏进行中画面区域从 " + viewW + "x" + viewH + " 变成 " + w + "x" + h
                    + " —— SDK 无法热更新触摸坐标空间，需断开重连才生效");
        }
        viewW = w; viewH = h;
        log("投屏视图尺寸 = " + w + "x" + h + "（触摸坐标空间）");
    }

    public String getLastPinCode() { return lastPinCode; }

    /**
     * 让手机把某条通知划掉（车机界面上的「✕」）。
     *
     * 走 SDK 的 {@code UCarAdapter.removeNotification}，手机侧会真的移除该通知，
     * 随后 SDK 回调 {@code onNotificationRemoved} 同步回来（本地先收是幂等的）。
     *
     * @return 是否成功交给 SDK；SDK 未初始化 / 未连接时返回 false。
     */
    public boolean removeNotification(int id) {
        UCarAdapter a = adapter;
        if (a == null) {
            log("! removeNotification(" + id + ") 被忽略：SDK 未初始化");
            return false;
        }
        try {
            boolean ok = a.removeNotification(Collections.singletonList(id));
            log((ok ? "✓ " : "✗ ") + "removeNotification(" + id + ")");
            return ok;
        } catch (Throwable t) {
            log("! removeNotification(" + id + ") 失败：" + t);
            return false;
        }
    }

    /** Surface 就绪 / 变化（Activity 的 surfaceChanged 回调里调用）。 */
    public void setSurface(Surface s) {
        this.surface = s;
        castRetry = 0;          // 新 Surface 到位，重试预算重置
        tryStartCast();
    }

    // ==================================================================
    // 主入口
    // ==================================================================

    /** 启动（或重启）投屏链路。权限由调用方（Activity）负责。 */
    public void start() {
        log("=== 启动（ICCOA CarWith）===");
        if (sdkInitialized && adapter != null) {
            log("重启：SDK 已初始化，仅重新广播");
            reAdvertise();
            return;
        }
        UCarConfig config;
        try {
            config = buildConfig();
        } catch (Throwable t) {
            log("✗ 配置构造失败: " + t);
            return;
        }
        try {
            adapter = UCarAdapter.getInstance();
            adapter.registerCarConnectListener(connectListener);
            adapter.registerPhoneDataListener(phoneDataListener);
        } catch (Throwable t) {
            log("✗ getInstance/register 异常: " + t);
            return;
        }
        initSdk(config);
    }

    private void initSdk(UCarConfig config) {
        boolean posted = adapter.init(appCtx, config, new ICarInitCallback() {
            @Override
            public void onInitSuccess() {
                log("★ onInitSuccess()");
                sdkReady = true;
                sdkInitialized = true;   // ★ init-once 守门
                main.post(() -> safe("doAdvertise", IccoaManager.this::doAdvertise));
                main.post(IccoaManager.this::scheduleAutoRefresh);   // ★ 启动配对码看门狗
            }

            @Override
            public void onInitFailed(int errCode) {
                log("✗ onInitFailed(" + errCode + ")"
                        + (errCode == 10001 ? " ← HandlerThread 启动失败" : ""));
            }
        });
        if (!posted) log("✗ init() 返回 false —— 任务未提交");
    }

    /**
     * 重播：先停旧广播，再启新广播。
     *
     * <p><b>必须先 {@code stopAdvertise()}</b> —— 对一条已经在广播中的链路重复调用
     * {@code startAdvertise()} 会被 SDK 直接忽略，广播帧内容不变，手机端因此不会重新发现
     * 车机、也就不会重新弹窗。只有「停-启」才是一次真正的重播。
     */
    private void reAdvertise() {
        if (adapter == null) return;
        try {
            adapter.registerCarConnectListener(connectListener);
            adapter.registerPhoneDataListener(phoneDataListener);
        } catch (Throwable t) {
            log("reAdvertise 重新注册异常: " + t);
        }
        try { adapter.stopAdvertise(); } catch (Throwable t) { log("stopAdvertise(重播前) 异常: " + t); }
        sdkReady = true;
        castStarted = false;
        castRetry = 0;
        lastPinCode = null;
        doAdvertise();
    }

    /**
     * 用户主动「刷新配对码」：立刻重播一次，不必等看门狗的 30 秒周期。
     *
     * <p>与自动刷新走同一条路径，重播后看门狗重新计时。
     */
    public void refreshPairing() {
        log("=== 手动刷新配对码 ===");
        if (!sdkReady || adapter == null) {
            log("刷新失败：SDK 尚未就绪");
            return;
        }
        reAdvertise();
        scheduleAutoRefresh();
    }

    public void stop() {
        log("=== 停止 ===");
        if (adapter != null) {
            try { adapter.pauseCast(); } catch (Throwable t) { log("pauseCast 异常: " + t); }
            try { adapter.disconnect(); } catch (Throwable t) { log("disconnect 异常: " + t); }
            try { adapter.stopAdvertise(); } catch (Throwable t) { log("stopAdvertise 异常: " + t); }
            try {
                adapter.unregisterCarConnectListener(connectListener);
                adapter.unregisterPhoneDataListener(phoneDataListener);
            } catch (Throwable ignored) { }
        }
        main.removeCallbacks(reAdvertiseRunnable);
        cancelAutoRefresh();
        sessionActive = false;
        castStarted = false;
        sdkReady = false;
        lastPinCode = null;
        // 保留 sdkInitialized —— init() 进程一次性，重启只走 reAdvertise()。
    }

    /**
     * 仅断开当前会话（用户主动点「断开」），保持广播 —— 手机可立即重新连接。
     *
     * <p>与 {@link #stop()} 的区别：{@code stop()} 会停掉广播（相当于彻底关机位），
     * 本方法只拆会话，车机仍可被发现。
     */
    public void disconnectSession() {
        log("=== 断开当前会话（保持广播）===");
        if (adapter != null) {
            try { adapter.pauseCast(); } catch (Throwable t) { log("pauseCast 异常: " + t); }
            try { adapter.disconnect(); } catch (Throwable t) { log("disconnect 异常: " + t); }
        }
        sessionActive = false;
        castStarted = false;
        lastPinCode = null;
        scheduleReAdvertise();
        scheduleAutoRefresh();
    }

    // ==================================================================
    // 广播 / 投屏
    // ==================================================================

    private void doAdvertise() {
        if (adapter == null) return;
        boolean ok = adapter.startAdvertise();
        log((ok ? "✓ " : "✗ ") + "startAdvertise() = " + ok);
        tryStartCast();
    }

    /**
     * 尝试起投。
     *
     * <p>重试有上限：停车待机页没有 Surface，无上限重试会变成一个永不停止的 500ms 定时器
     * （待机页常驻，等于白耗电）。Surface 稍后就绪的常见情形由 {@link #setSurface} 兜底，
     * 这里的十秒预算只用于「Surface 已设但尚未 valid」的短暂窗口。
     */
    private void tryStartCast() {
        if (!sdkReady || castStarted || adapter == null) return;
        Surface s = surface;
        if (s == null || !s.isValid()) {
            if (castRetry < CAST_RETRY_MAX) {
                castRetry++;
                main.postDelayed(this::tryStartCast, 500);
            }
            return;
        }
        castRetry = 0;
        // startCast 的 w/h 是 UIBC 的显示区尺寸（触摸坐标空间）：知道 View 真实尺寸时用它，
        // 否则回落到面板尺寸。视频码流尺寸由 SDK 自己从链路里取，与这里的 w/h 无关。
        int cw = viewW > 0 ? viewW : videoW;
        int ch = viewH > 0 ? viewH : videoH;
        boolean ok = adapter.startCast(s, cw, ch);
        castStarted = ok;
        log((ok ? "✓ " : "✗ ") + "startCast(" + cw + "x" + ch + ") = " + ok);
        if (ok) {
            main.post(listener::onCastStarted);
        }
    }

    // ==================================================================
    // SDK 回调
    // ==================================================================

    private final ICarConnectListener connectListener = new ICarConnectListener() {
        @Override
        public void onPinCode(String pinCode, String carDeviceName) {
            lastPinCode = pinCode;
            log("★ onPinCode pinCode=" + pinCode + " carDeviceName=" + carDeviceName);
            // 新码下发 → 看门狗重新计时，保证用户拿到完整的有效期去确认，不会刚出码就被重播顶掉。
            scheduleAutoRefresh();
            main.post(() -> listener.onPinCode(pinCode, carDeviceName));
        }

        @Override
        public void onConnectStateChanged(String deviceID, int state, int extra) {
            // ★ SDK 的签名是 (deviceId, state, extra)：**第 2 个参数才是状态**，第 3 个是附加码
            //   （连接失败时是错误码如 10008，其余情况为 -1）。
            //   早先按 (oldState, newState) 来读，于是「连接成功」那次回调 (state=2, extra=-1)
            //   被当成新状态 -1，落进 else 分支回落成「广播中」——UI 因此永远停在「等待手机连接」，
            //   而底层其实早已连上并在传视频。
            log("onConnectStateChanged " + stateName(state) + " extra=" + extra);

            if (state == STATE_DISCONNECT || state == STATE_CAST_DISCONNECTED
                    || state == STATE_DISCONNECT_BY_USER) {
                // 会话结束：清掉已过期的配对码并延时重新广播，手机端 CarWith 会重新发现车机
                // 并触发一次全新配对（新的 onPinCode）。
                sessionActive = false;
                lastPinCode = null;
                castStarted = false;
                scheduleReAdvertise();
                scheduleAutoRefresh();
            } else if (state == STATE_CONNECTED || state == STATE_CAST_CONNECTED
                    || state == STATE_GO_TO_HOME || state == STATE_BACK_TO_FOREGROUND) {
                // 已连上（含投屏建立、前后台切换）：撤销任何待执行的重新广播，免得刚连上就被
                // 重启广播打断；看门狗也会因此空转。
                sessionActive = true;
                main.removeCallbacks(reAdvertiseRunnable);
            } else if (state == STATE_CONNECT_FAILED) {
                log("✗ 连接失败 errorCode=" + extra);
                sessionActive = false;
                scheduleReAdvertise();
            }
            // CONNECTING 等中间态刻意不置 sessionActive：若协商卡死超过一个刷新周期，
            // 看门狗需要能重播把它救回来。正常协商只需数秒，30 秒的余量足够。

            final int st = state;
            main.post(() -> listener.onConnectionState(st));
        }

        @Override
        public void onConnectingProgress(String phoneId, int progress) {
            log("onConnectingProgress " + progress);
            main.post(() -> listener.onConnectingProgress(progress));
        }

        @Override
        public void onReconfigureWifiInfo(WifiOwnerConfig config) {
            log("onReconfigureWifiInfo ssid=" + (config == null ? "?" : config.getSsid()));
        }

        @Override
        public void onRethinkBluetoothConnect(boolean rethink) {
            log("onRethinkBluetoothConnect=" + rethink);
        }

        @Override
        public UCarCommon.WorkMode onSelectWorkMode(String deviceID, UCarCommon.WorkMode supported) {
            UCarCommon.WorkMode chosen;
            if (supported == null) {
                chosen = UCarCommon.WorkMode.NONE;
            } else if (supported.isSupportAppWindowCast()) {
                chosen = UCarCommon.WorkMode.APP_WINDOW_CAST;
            } else if (supported.isSupportAppFullScreenCast()) {
                chosen = UCarCommon.WorkMode.APP_FULLSCREEN_CAST;
            } else if (supported.isSupportNormalCast()) {
                chosen = UCarCommon.WorkMode.NORMAL_CAST;
            } else if (supported.isSupportDataTransfer()) {
                chosen = UCarCommon.WorkMode.DATA_TRANSFER;
            } else {
                chosen = UCarCommon.WorkMode.NONE;
            }
            log("onSelectWorkMode → " + chosen);
            return chosen;
        }
    };

    private final IPhoneDataListener phoneDataListener = new IPhoneDataListener() {
        @Override
        public void onNavigationInfoReceived(String phoneId, UCarCommon.NavigationInfo info) {
            listener.onNavigation(info);
        }

        @Override
        public void onNotificationAdded(String phoneId, UCarNotification notification) {
            listener.onNotificationAdded(notification);
        }

        @Override
        public void onNotificationRemoved(String phoneId, List<Integer> ids) {
            listener.onNotificationRemoved(ids);
        }

        @Override public void onMusicInfoReceived(String phoneId, UCarCommon.MusicInfo info) { }
        @Override public void onCallInfoReceived(String phoneId, UCarCommon.CallInfo info) { }
        @Override public void onPhoneStateInfoReceived(String phoneId, UCarCommon.PhoneStateInfo info) { }
        @Override public void onPOIReceived(String phoneId, UCarCommon.POIAddress poi) { }
        @Override public void onAudioFocusChanged(UCarCommon.AudioType type, int focus) { }

        @Override
        @SuppressWarnings("rawtypes")
        public void onAppListStateChanged(String phoneId, List list, UCarCommon.AppListState state) { }

        @Override
        public void onAppStateChanged(String phoneId, int state, boolean flag,
                                      int a, int b, UCarCommon.AppState appState,
                                      UCarCommon.DisplayMode displayMode) { }

        @Override
        public UCarCommon.AudioAttributes getAudioAttributesByType(UCarCommon.AudioType type) {
            return null;
        }
    };

    // ==================================================================
    // 配置构造（SDK 硬校验，越界即抛）
    // ==================================================================

    private UCarConfig buildConfig() {
        byte[] carId = generateCarId();
        if (carId == null || carId.length != 6) {
            carId = new byte[6];
            new Random().nextBytes(carId);
            log("! carId 长度异常，强制随机 6 字节");
        }
        android.util.DisplayMetrics dm = appCtx.getResources().getDisplayMetrics();
        int densityDpi = dm.densityDpi;
        int dpi = clamp(320, 120, 640);
        int realDpi = clamp(densityDpi, 120, 640);
        int fps = clamp(60, 15, 60);
        int sw = Math.max(1, screenW), sh = Math.max(1, screenH);
        int vw = Math.max(1, videoW), vh = Math.max(1, videoH);

        UCarCommon.WorkMode workMode = new UCarCommon.WorkMode.Builder()
                .setSupportNormalCast(true)
                .setSupportAppFullScreenCast(false)
                .setSupportAppWindowCast(false)
                .build();

        // 连接方式跟随设置里的「网络传输方式」(Settings.nativeApTransport)：
        //   0 = WiFi Direct(P2P) —— 车机与手机直连组网，普通应用权限即可
        //   1 = 热点(SoftAP)     —— 车机自己开热点，需 TETHER_PRIVILEGED 系统签名权限
        // 注意：SDK 是 init-once，改设置后需重启 App 生效。
        boolean useSoftAp = false;
        try {
            useSoftAp = new com.carpp.sdk.utils.Settings(appCtx).getNativeApTransport() == 1;
        } catch (Throwable t) {
            log("! 读取 nativeApTransport 失败，回落 WiFi Direct: " + t);
        }
        log("连接方式 = " + (useSoftAp ? "热点(SoftAP)" : "WiFi Direct(P2P)"));

        return new UCarConfig.Builder()
                .setCarBrMac(carId)                  // 必须恰好 6 字节
                .setDpi(dpi)                         // 120..640
                .setRealDpi(realDpi)                 // 120..640
                .setFps(fps)                         // 15..60
                .setScreenWidth(sw)
                .setScreenHeight(sh)
                .setVideoDisplayWidth(vw)
                .setVideoDisplayHeight(vh)
                .setSupportP2P(!useSoftAp)           // 设置选 WiFi Direct 时走 P2P
                .setSupportSoftAP(useSoftAp)         // 设置选「热点」时才用 SoftAP
                .setSupportCamera(false)
                .setSupportHevc(false)
                .setSupportMic(true)
                .setUseCustomAudioRecord(false)
                .setSupportStereoRecord(false)
                .setSupportLowLatencyDecodingMode(false)
                .setSupportPOINav(false)
                .setCcdFilePath("ccd.data")
                .setSupportedWorkModes(workMode)
                .build();
    }

    private static int clamp(int v, int lo, int hi) {
        return v < lo ? lo : (v > hi ? hi : v);
    }

    private byte[] generateCarId() {
        SharedPreferences prefs = appCtx.getSharedPreferences("car_id_prefs", Context.MODE_PRIVATE);
        String saved = prefs.getString("car_id_key", "");
        if (saved != null && !saved.isEmpty()) {
            try {
                byte[] cached = Base64.decode(saved, Base64.NO_WRAP);
                if (cached != null && cached.length == 6) return cached;
                log("carId 缓存长度≠6，作废重生成");
            } catch (Throwable t) {
                log("carId 缓存解码失败: " + t);
            }
        }
        byte[] raw = getBluetoothMac();
        if (raw == null || raw.length != 6) {
            raw = new byte[6];
            new Random().nextBytes(raw);
        }
        prefs.edit().putString("car_id_key", Base64.encodeToString(raw, Base64.NO_WRAP)).apply();
        return raw;
    }

    @android.annotation.SuppressLint("MissingPermission")
    private byte[] getBluetoothMac() {
        byte[] macBytes = new byte[6];
        try {
            android.bluetooth.BluetoothManager bm =
                    (android.bluetooth.BluetoothManager) appCtx.getSystemService(Context.BLUETOOTH_SERVICE);
            android.bluetooth.BluetoothAdapter a = (bm == null) ? null : bm.getAdapter();
            String mac = (a == null) ? null : a.getAddress();
            if (mac != null) {
                String[] seg = mac.split(":");
                if (seg.length == 6) {
                    for (int i = 0; i < 6; i++) macBytes[i] = (byte) Integer.parseInt(seg[i], 16);
                    return macBytes;
                }
            }
        } catch (Throwable t) {
            log("读蓝牙 MAC 被拒（缺 BLUETOOTH_CONNECT？）: " + t);
        }
        new Random().nextBytes(macBytes);
        return macBytes;
    }

    private void log(String msg) {
        Log.i(TAG, msg);
        try { listener.onLog(msg); } catch (Throwable ignored) { }
    }

    private void safe(String where, Runnable r) {
        try { r.run(); } catch (Throwable t) { log("!!! " + where + " 异常: " + t); }
    }

    // SDK 的 UCarConnectState 状态常量（第 2 个回调参数），见 com.ucar.vehiclesdk.UCarConnectState
    private static final int STATE_DISCONNECT = 1;              // UCAR_DEVICE_DISCONNECT
    private static final int STATE_CONNECTED = 2;               // UCAR_DEVICE_CONNECTED
    private static final int STATE_CONNECT_FAILED = 3;          // UCAR_DEVICE_CONNECT_FAILED
    private static final int STATE_CAST_CONNECTED = 4;          // UCAR_DEVICE_CAST_CONNECTED
    private static final int STATE_CAST_DISCONNECTED = 5;       // UCAR_DEVICE_CAST_DISCONNECTED
    private static final int STATE_GO_TO_HOME = 6;              // UCAR_DEVICE_GO_TO_HOME
    private static final int STATE_BACK_TO_FOREGROUND = 7;      // UCAR_DEVICE_BACK_TO_FOREGROUND
    private static final int STATE_DISCONNECT_BY_USER = 11;     // DISCONNECT_BY_USER

    private static String stateName(int s) {
        switch (s) {
            case STATE_DISCONNECT: return "DISCONNECT";
            case STATE_CONNECTED: return "CONNECTED";
            case STATE_CONNECT_FAILED: return "CONNECT_FAILED";
            case STATE_CAST_CONNECTED: return "CAST_CONNECTED";
            case STATE_CAST_DISCONNECTED: return "CAST_DISCONNECTED";
            case STATE_GO_TO_HOME: return "GO_TO_HOME";
            case STATE_BACK_TO_FOREGROUND: return "BACK_TO_FOREGROUND";
            case STATE_DISCONNECT_BY_USER: return "DISCONNECT_BY_USER";
            default: return "STATE_" + s;
        }
    }
}
