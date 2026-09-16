package com.phonecam;

import android.Manifest;
import android.app.Activity;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.graphics.Color;
import android.graphics.Matrix;
import android.graphics.Point;
import android.graphics.Rect;
import android.graphics.SurfaceTexture;
import android.hardware.camera2.CameraCaptureSession;
import android.hardware.camera2.CameraCharacteristics;
import android.hardware.camera2.CameraConstrainedHighSpeedCaptureSession;
import android.hardware.camera2.CameraDevice;
import android.hardware.camera2.CameraManager;
import android.hardware.camera2.CaptureRequest;
import android.hardware.camera2.CaptureResult;
import android.hardware.camera2.TotalCaptureResult;
import android.hardware.camera2.params.MeteringRectangle;
import android.hardware.camera2.params.StreamConfigurationMap;
import android.media.MediaCodec;
import android.media.MediaCodecInfo;
import android.media.MediaFormat;
import android.os.BatteryManager;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.HandlerThread;
import android.util.Log;
import android.util.Range;
import android.util.Size;
import android.util.SizeF;
import android.view.Gravity;
import android.view.Surface;
import android.view.TextureView;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowInsets;
import android.view.WindowInsetsController;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.PopupMenu;
import android.widget.SeekBar;
import android.widget.TextView;

import java.io.InputStream;
import java.io.OutputStream;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * PhoneCam —— 把手机摄像头变成 OBS 可直接使用的网络视频源。
 *
 * 链路：
 *   Camera2 (sensor)
 *     ├─▶ MediaCodec H.264 硬件编码 (Surface 零拷贝) ─▶ MPEG-TS ─▶ TCP :8555 ─▶ adb forward ─▶ OBS
 *     └─▶ TextureView（手机端实时预览，用于取景构图）
 *
 * 功能：
 *   · 实时预览（可开关，16:9 自适应，横竖屏旋转正确）
 *   · 多摄像头选择（前/后、主摄/超广/长焦，运行中热切换，选择持久化）
 *   · 对焦方式选择（连续视频 / 连续拍照 / 单次自动 / 微距 / 手动 / EDOF）+ 点击预览点对焦
 *   · 屏幕亮度三档（最暗 / 暗 / 正常），仅作用于本窗口，退出自动还原
 *   · 空闲自动暂停采集省电（保留低帧率预览，客户端一连上立刻恢复）
 *
 * 可通过 Intent extra 覆盖全部参数，例如：
 *   am start -n com.phonecam/.CamServer \
 *     --ei w 1920 --ei h 1080 --ei fps 60 --ei bitrate 16000000 \
 *     --es cam 0 --ei af 3 --ez preview true --ei bright 1 --ei idle 8
 */
public class CamServer extends Activity {

    private static final String TAG = "PhoneCam";
    private static final int PORT = 8555;
    private static final String PREFS = "phonecam";

    // ---------- 编码 / 采集 ----------
    private int cfgW = 1920;
    private int cfgH = 1080;
    private int cfgFps = 60;
    private int cfgBitrate = 16_000_000;

    /** 私有通道: 0=不用 1=只 disableFPSLimits 2=只 overrideSensorFrameDuration 3=两者。
     *  实测：只有 1 能同时拿到 60fps 与正确曝光；2/3 会覆盖曝光时间导致画面全白
     *  （该 tag 虽在 manualExposure 命名空间、名字像「帧时长」，语义其实是强制曝光）。 */
    private int cfgVmode = 1;
    private boolean cfgSetFpsRange = true;
    private int cfgHs = 0;
    private long cfgExpCap = 0;

    // ---------- 省电 ----------
    /** 无客户端连接时，等待多少秒后暂停采集（省电、降温）。0 = 永不暂停 */
    private int cfgIdle = 8;
    /** 暂停期间预览的帧率（编码器完全不出帧，只有预览在跑） */
    private int cfgIdleFps = 10;
    private boolean cfgDim = true;
    /** 0=最暗 1=暗 2=正常 */
    private int cfgBright = 1;
    private static final float BRIGHT_MIN = 0.01f;
    private static final float BRIGHT_LOW = 0.15f;

    // ---------- 预览 ----------
    private boolean cfgPreview = true;
    private int prevW = 1280;
    private int prevH = 720;
    /**
     * 诊断串。这台 vivo 的 ROM 只在 STAT 那类稳定行上放行日志，启动阶段的日志会被丢掉，
     * 所以把关键结论攒起来挂在每秒的 STAT 行尾部输出（见 drainLoop）。
     */
    private volatile String diag = "";
    /** 预览 surface 收到的帧数（诊断用：能证明预览链路是否真的在出帧） */
    private volatile long previewFrames = 0;
    private long prevFramesAtStat = 0;
    private volatile int lastPrevFps = 0;
    /**
     * 预览 SurfaceTexture 的引用 + 上一次的时间戳。
     * 注意：不能用 setOnFrameAvailableListener 来数帧 —— TextureView 内部靠同一个
     * 监听器触发重绘，被覆盖后预览会永久黑屏。所以这里改成轮询 getTimestamp()：
     * 它由相机生产端写入，时间戳在变就说明预览链路真的在出帧。
     */
    private volatile SurfaceTexture previewST;
    private long lastPreviewTs = 0;
    /** 预览 View 的实际像素尺寸与屏幕坐标（诊断用） */
    private volatile int viewW = 0, viewH = 0, viewX = 0, viewY = 0;
    /** 诊断：纹理像素均值（-1 未采到） */
    private volatile double probeMean = -1;
    private boolean cfgProbe = false;
    /** 诊断：临时关闭预览变换，用来判断黑屏是变换问题还是纹理问题 */
    private boolean cfgNoTransform = false;
    /**
     * 每个屏幕方向（0 / 90 / 180 / 270）各自校准过的预览旋转角，-1 = 该方向还没校准过。
     *
     * 为什么要按方向分开存：Camera2 那套「传感器方向 − 屏幕方向」的标准公式在这台机器上
     * 算出来是躺的（走高通私有 HFR 通道时 HAL 输出方向与标准约定差了 90°），而且实测
     * 「横屏要 270、竖屏要 180」—— 差值是随屏幕方向变的，用一个固定偏移量补不回来。
     * 所以干脆让用户在某个方向点一次正确的角度，记在这个方向的槽位里。
     */
    private final int[] rotByDisp = new int[] { -1, -1, -1, -1 };
    /** 一次都没校准过时的兜底值：本机（vivo V2054A）实测横屏正立 = 270° */
    private static final int DEFAULT_ROT = 270;
    /** intent 传来的绝对角度，等 UI 建好后再应用 */
    private int pendingRot = -1;

    // ---------- 运行时状态 ----------
    private volatile boolean idlePaused = false;
    private long idleMs = 0;
    private volatile String lastErr = "";
    private BroadcastReceiver batteryRx;
    private volatile float battTemp = 0;

    // ---------- 相机 ----------
    private CameraManager camMgr;
    private CameraDevice camera;
    private CameraCaptureSession session;
    private String camId = "0";
    private int camFacing = CameraCharacteristics.LENS_FACING_BACK;
    private int camSensorOrientation = 90;
    private Rect activeArray = new Rect(0, 0, 1920, 1080);
    private int maxAfRegions = 0;
    private float minFocusDiopter = 0f;
    private boolean manualFocusSupported = false;
    private final List<CamInfo> camList = new ArrayList<CamInfo>();

    /** 对焦模式（CaptureRequest.CONTROL_AF_MODE_*） */
    private int afMode = CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_VIDEO;
    /** 手动对焦距离（屈光度 diopter，0 = 无穷远） */
    private volatile float focusDistance = 0f;
    private MeteringRectangle[] afRegions = null;
    /** 用户是否手动选过对焦模式（只有选过才认持久化值） */
    private boolean userPickedAf = false;

    // ---------- 编码 / 网络 ----------
    private MediaCodec codec;
    private Surface encSurface;
    private TsMuxer muxer;
    private ServerSocket serverSocket;
    /** 支持多客户端：OBS 与自检脚本可同时连接 */
    private final List<OutputStream> clients =
            new java.util.concurrent.CopyOnWriteArrayList<OutputStream>();

    private volatile boolean running = true;

    private long encFrames = 0;
    private long encBytes = 0;
    private long totalFrames = 0;
    private long startedAt = 0;
    private long statAt = 0;
    private long uiAt = 0;
    private volatile int lastFps = 0;
    private volatile int lastKbps = 0;
    private volatile long sensorFrameNs = 0;
    private volatile long exposureNs = 0;
    private volatile int iso = 0;
    private volatile int aeState = -1;
    private volatile int afState = -1;
    private volatile float focusDiopterNow = -1f;
    private long captureFrames = 0;
    private volatile int capFps = 0;

    // ---------- UI ----------
    private TextView status;
    private TextureView preview;
    private Surface previewSurface;
    private Button btnCam, btnFocus, btnBright, btnPreview, btnRot;
    private SeekBar seekFocus;
    private LinearLayout seekRow;
    private long lastFocusApplyMs = 0;
    private final Matrix previewXform = new Matrix();

    private HandlerThread ht;
    private Handler h;

    // ==================== 生命周期 ====================

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);

        readPrefs();
        applyIntent(getIntent());
        buildUi();
        if (pendingRot >= 0) { int r = pendingRot; pendingRot = -1; applyRotation(r); }
        log("CONFIG " + cfgW + "x" + cfgH + "@" + cfgFps + " vmode=" + cfgVmode
                + " fpsRange=" + cfgSetFpsRange + " hs=" + cfgHs + " expcap=" + cfgExpCap
                + " idle=" + cfgIdle + "s dim=" + cfgDim + " bright=" + cfgBright
                + " preview=" + cfgPreview + " cam=" + camId + " af=" + afMode);

        watchBattery();
        applyDim();

        ht = new HandlerThread("cam");
        ht.start();
        h = new Handler(ht.getLooper());
        statAt = System.currentTimeMillis();
        startedAt = statAt;

        camMgr = (CameraManager) getSystemService(Context.CAMERA_SERVICE);
        buildCamList();

        startNetServer();
        try {
            startEncoder();
            maybeOpenCamera();
        } catch (Throwable t) {
            log("FATAL " + t);
            Log.e(TAG, "fatal", t);
        }
    }

    @Override
    protected void onNewIntent(Intent i) {
        super.onNewIntent(i);
        setIntent(i);
        boolean needRebuild = applyIntent(i);
        applyDim();
        refreshUi();
        if (needRebuild) rebuildCamera();
    }

    @Override
    protected void onResume() {
        super.onResume();
        applyDim();
    }

    @Override
    protected void onDestroy() {
        running = false;
        applyDimOff();   // 退出时把亮度控制权交还系统
        if (batteryRx != null) { try { unregisterReceiver(batteryRx); } catch (Throwable ignored) {} }
        try { if (session != null) session.close(); } catch (Exception ignored) {}
        try { if (camera != null) camera.close(); } catch (Exception ignored) {}
        try { if (codec != null) codec.stop(); } catch (Exception ignored) {}
        try { if (codec != null) codec.release(); } catch (Exception ignored) {}
        try { if (previewSurface != null) previewSurface.release(); } catch (Exception ignored) {}
        try { if (serverSocket != null) serverSocket.close(); } catch (Exception ignored) {}
        if (ht != null) ht.quitSafely();
        super.onDestroy();
    }

    // ==================== 参数 ====================

    private void readPrefs() {
        SharedPreferences p = getSharedPreferences(PREFS, MODE_PRIVATE);
        camId = p.getString("cam", camId);
        // afMode 只在用户手动选过时才认持久化值：否则会把「某次自动回退到固定对焦」
        // 的结果当成用户偏好存下来，之后换回主摄也一直是手动对焦
        userPickedAf = p.getBoolean("afUser", false);
        if (userPickedAf) afMode = p.getInt("af", afMode);
        cfgPreview = p.getBoolean("preview", cfgPreview);
        cfgBright = p.getInt("bright", cfgBright);
        focusDistance = p.getFloat("focus", focusDistance);
        for (int i = 0; i < 4; i++) rotByDisp[i] = p.getInt("rot" + i, -1);
    }

    private void savePrefs() {
        SharedPreferences p = getSharedPreferences(PREFS, MODE_PRIVATE);
        SharedPreferences.Editor ed = p.edit();
        ed.putString("cam", camId)
                .putInt("af", afMode)
                .putBoolean("afUser", userPickedAf)
                .putBoolean("preview", cfgPreview)
                .putInt("bright", cfgBright)
                .putFloat("focus", focusDistance);
        for (int i = 0; i < 4; i++) ed.putInt("rot" + i, rotByDisp[i]);
        ed.apply();
    }

    /** @return 是否需要重建相机（切换了摄像头 / 预览尺寸相关配置） */
    private boolean applyIntent(Intent i) {
        if (i == null) return false;
        boolean rebuild = false;
        cfgW = i.getIntExtra("w", cfgW);
        cfgH = i.getIntExtra("h", cfgH);
        cfgFps = i.getIntExtra("fps", cfgFps);
        cfgBitrate = i.getIntExtra("bitrate", cfgBitrate);
        cfgVmode = i.getIntExtra("vmode", cfgVmode);
        cfgSetFpsRange = i.getBooleanExtra("setfpsrange", cfgSetFpsRange);
        cfgHs = i.getIntExtra("hs", cfgHs);
        cfgExpCap = i.getLongExtra("expcap", cfgExpCap);
        cfgIdle = i.getIntExtra("idle", cfgIdle);
        cfgIdleFps = i.getIntExtra("idlefps", cfgIdleFps);
        cfgDim = i.getBooleanExtra("dim", cfgDim);
        int nb = i.getIntExtra("bright", cfgBright);
        if (nb != cfgBright) { cfgBright = nb; }
        boolean np = i.getBooleanExtra("preview", cfgPreview);
        if (np != cfgPreview) { cfgPreview = np; rebuild = true; }
        String nc = i.getStringExtra("cam");
        if (nc != null && !nc.equals(camId)) { camId = nc; rebuild = true; }
        int na = i.getIntExtra("af", afMode);
        if (na != afMode) { afMode = na; }
        cfgProbe = i.getBooleanExtra("probe", cfgProbe);
        cfgNoTransform = i.getBooleanExtra("notrans", cfgNoTransform);
        // rot 是「绝对角度」，直接记到当前屏幕方向的槽位里
        int nr = i.getIntExtra("rot", -1);
        if (nr >= 0) applyRotation(nr);
        return rebuild;
    }

    // ==================== UI ====================

    private android.view.ViewGroup root;
    private LinearLayout bar, sidePanel;
    private android.view.ViewGroup previewWrap;

    private boolean isLandscape() {
        return getResources().getConfiguration().orientation
                == android.content.res.Configuration.ORIENTATION_LANDSCAPE;
    }

    /** 横屏时浮层挪到右侧竖排，竖屏在底部横排；预览始终铺满整屏不受影响 */
    private void relayoutForOrientation() {
        if (root == null || previewWrap == null) return;
        boolean land = isLandscape();
        if (previewWrap.getParent() == root) {
            ((FrameLayout.LayoutParams) previewWrap.getLayoutParams()).width =
                    ViewGroup.LayoutParams.MATCH_PARENT;
            ((FrameLayout.LayoutParams) previewWrap.getLayoutParams()).height =
                    ViewGroup.LayoutParams.MATCH_PARENT;
        }
        if (sidePanel != null && sidePanel.getParent() == root) {
            FrameLayout.LayoutParams lp = (FrameLayout.LayoutParams) sidePanel.getLayoutParams();
            if (land) {
                lp.width = ViewGroup.LayoutParams.WRAP_CONTENT;
                lp.height = ViewGroup.LayoutParams.MATCH_PARENT;
                lp.gravity = Gravity.END | Gravity.CENTER_VERTICAL;
            } else {
                lp.width = ViewGroup.LayoutParams.MATCH_PARENT;
                lp.height = ViewGroup.LayoutParams.WRAP_CONTENT;
                lp.gravity = Gravity.BOTTOM;
            }
        }
        if (bar != null) {
            bar.setOrientation(land ? LinearLayout.VERTICAL : LinearLayout.HORIZONTAL);
            for (int i = 0; i < bar.getChildCount(); i++) {
                View v = bar.getChildAt(i);
                v.setLayoutParams(land
                        ? new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                                ViewGroup.LayoutParams.WRAP_CONTENT)
                        : new LinearLayout.LayoutParams(0,
                                ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
            }
        }
        if (sidePanel != null) sidePanel.setOrientation(LinearLayout.VERTICAL);
        if (preview != null) preview.requestLayout();
    }

    private void buildUi() {
        FrameLayout rootF = new FrameLayout(this);
        root = rootF;
        root.setBackgroundColor(Color.BLACK);

        // ---- 预览区（铺满整屏） ----
        FrameLayout wrap = new FrameLayout(this);
        previewWrap = wrap;
        // 视图尺寸一变就重算变换：否则手动对焦滑杆出现/消失会让画面错位
        preview = new TextureView(this) {
            @Override protected void onSizeChanged(int w, int h, int ow, int oh) {
                super.onSizeChanged(w, h, ow, oh);
                post(new Runnable() {
                    @Override public void run() { updatePreviewTransform(); }
                });
            }
            // 全屏铺满模式：视图有多大就量多大，画面比例靠变换矩阵裁剪（centerCrop）
        };
        wrap.addView(preview, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        root.addView(wrap, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        preview.setSurfaceTextureListener(new TextureView.SurfaceTextureListener() {
            @Override public void onSurfaceTextureAvailable(SurfaceTexture st, int w, int hh) {
                st.setDefaultBufferSize(prevW, prevH);
                previewST = st;
                previewSurface = new Surface(st);
                log("preview surface ready " + prevW + "x" + prevH);
                maybeOpenCamera();
                updatePreviewTransform();
            }
            @Override public void onSurfaceTextureSizeChanged(SurfaceTexture st, int w, int hh) {
                updatePreviewTransform();
            }
            @Override public boolean onSurfaceTextureDestroyed(SurfaceTexture st) {
                if (previewSurface != null) { try { previewSurface.release(); } catch (Throwable ignored) {} }
                previewSurface = null;
                return true;
            }
            @Override public void onSurfaceTextureUpdated(SurfaceTexture st) { }
        });
        // 点预览区域 = 点对焦/测光
        preview.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { /* 由 onTouch 处理 */ }
        });
        preview.setOnTouchListener(new View.OnTouchListener() {
            @Override public boolean onTouch(View v, android.view.MotionEvent e) {
                if (e.getAction() == android.view.MotionEvent.ACTION_UP) {
                    tapToFocus(e.getX(), e.getY());
                    return true;
                }
                return true;
            }
        });

        // ---- 状态 ----
        status = new TextView(this);
        status.setTextColor(Color.parseColor("#FF6B60"));
        status.setBackgroundColor(Color.parseColor("#B3000000"));   // 半透明，叠在预览上
        status.setTextSize(10);
        status.setPadding(6, 6, 6, 6);
        status.setTypeface(android.graphics.Typeface.MONOSPACE);
        // 状态条放浮层顶部（与滑杆、按钮同一块半透明面板），不再遮挡也不被遮挡

        // ---- 手动对焦滑杆 ----
        seekRow = new LinearLayout(this);
        seekRow.setOrientation(LinearLayout.HORIZONTAL);
        seekRow.setGravity(Gravity.CENTER_VERTICAL);
        seekRow.setVisibility(View.GONE);
        TextView seekHint = new TextView(this);
        seekHint.setText("手动对焦  远");
        seekHint.setTextColor(Color.parseColor("#FFD166"));
        seekHint.setTextSize(10);
        seekRow.addView(seekHint, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        seekFocus = new SeekBar(this);
        seekFocus.setMax(1000);
        seekFocus.setProgress(diopterToProgress(focusDistance));
        int tint = Color.parseColor("#FF6B60");
        try {
            seekFocus.setProgressTintList(android.content.res.ColorStateList.valueOf(tint));
            seekFocus.setThumbTintList(android.content.res.ColorStateList.valueOf(tint));
            seekFocus.setProgressBackgroundTintList(
                    android.content.res.ColorStateList.valueOf(Color.parseColor("#55FFFFFF")));
        } catch (Throwable ignored) { }
        seekFocus.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override public void onProgressChanged(SeekBar sb, int prog, boolean fromUser) {
                if (!fromUser) return;
                focusDistance = progressToDiopter(prog);
                // 拖动时每 80ms 才下发一次，避免刷爆 setRepeatingRequest
                long now = System.currentTimeMillis();
                if (now - lastFocusApplyMs >= 80) {
                    lastFocusApplyMs = now;
                    restartRepeating();
                    refreshUi();
                }
            }
            @Override public void onStartTrackingTouch(SeekBar sb) { }
            @Override public void onStopTrackingTouch(SeekBar sb) {
                lastFocusApplyMs = 0;
                restartRepeating();
                savePrefs();
                refreshUi();
            }
        });
        seekRow.addView(seekFocus, new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        TextView seekNear = new TextView(this);
        seekNear.setText("近 ");
        seekNear.setTextColor(Color.parseColor("#FFD166"));
        seekNear.setTextSize(10);
        seekRow.addView(seekNear, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        // 滑杆和按钮打包进半透明浮层：竖屏叠底部，横屏挪到右侧竖排
        sidePanel = new LinearLayout(this);
        sidePanel.setOrientation(LinearLayout.VERTICAL);
        sidePanel.addView(status, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        sidePanel.addView(seekRow, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        // ---- 按钮行 ----
        bar = new LinearLayout(this);
        bar.setOrientation(LinearLayout.HORIZONTAL);
        btnCam = addBar(bar, "", new View.OnClickListener() {
            @Override public void onClick(View v) { showCamMenu(v); }
        });
        btnFocus = addBar(bar, "", new View.OnClickListener() {
            @Override public void onClick(View v) { showFocusMenu(v); }
        });
        btnBright = addBar(bar, "", new View.OnClickListener() {
            @Override public void onClick(View v) {
                cfgBright = (cfgBright + 1) % 3;
                applyDim();
                savePrefs();
                refreshUi();
            }
        });
        btnPreview = addBar(bar, "", new View.OnClickListener() {
            @Override public void onClick(View v) {
                cfgPreview = !cfgPreview;
                savePrefs();
                rebuildCamera();
                refreshUi();
            }
        });
        btnRot = addBar(bar, "", new View.OnClickListener() {
            @Override public void onClick(View v) {
                // 每点一次顺时针 90°，选中的角度会被记成校准偏移，之后一直生效
                applyRotation(previewRotationDeg() + 90);
            }
        });
        sidePanel.addView(bar, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        // 半透明浮层，叠在铺满全屏的预览之上
        sidePanel.setBackgroundColor(Color.parseColor("#88000000"));
        sidePanel.setPadding(6, 6, 6, 6);
        root.addView(sidePanel, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT,
                Gravity.BOTTOM));

        setContentView(root);
        applyImmersive();
        relayoutForOrientation();
        refreshUi();
    }

    /** 沉浸式全屏：隐藏状态栏/导航栏，预览铺满物理屏幕（含刘海区） */
    private void applyImmersive() {
        try {
            if (Build.VERSION.SDK_INT >= 30) {
                getWindow().setDecorFitsSystemWindows(false);
                try {
                    getWindow().getAttributes().layoutInDisplayCutoutMode =
                            WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES;
                } catch (Throwable ignored) { }
                WindowInsetsController ic = getWindow().getInsetsController();
                if (ic != null) {
                    ic.hide(WindowInsets.Type.statusBars() | WindowInsets.Type.navigationBars());
                    ic.setSystemBarsBehavior(
                            WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE);
                }
            } else {
                View decor = getWindow().getDecorView();
                decor.setSystemUiVisibility(
                        View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
                                | View.SYSTEM_UI_FLAG_FULLSCREEN
                                | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                                | View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                                | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                                | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION);
            }
        } catch (Throwable ignored) { }
    }

    @Override
    public void onWindowFocusChanged(boolean hasFocus) {
        super.onWindowFocusChanged(hasFocus);
        if (hasFocus) applyImmersive();
    }

    @Override
    public void onConfigurationChanged(android.content.res.Configuration c) {
        super.onConfigurationChanged(c);
        // 不重建 Activity（configChanges 已声明），只重排版面，避免推流中断
        relayoutForOrientation();
        if (h != null) {
            h.postDelayed(new Runnable() {
                @Override public void run() { updatePreviewTransform(); }
            }, 150);
        }
    }

    private Button addBar(LinearLayout bar, String text, View.OnClickListener l) {
        Button b = new Button(this);
        b.setText(text);
        b.setTextSize(10);
        b.setMinHeight(0);
        b.setMinimumHeight(0);
        b.setPadding(6, 10, 6, 10);
        b.setAllCaps(false);
        b.setOnClickListener(l);
        bar.addView(b, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        return b;
    }

    // ==================== 摄像头列表 ====================

    private static class CamInfo {
        String id;
        String label;
        int facing;
        boolean logical;
        float fov;
    }

    /** 用户是否手动选过摄像头；没选过时程序可以自动纠正默认选项 */
    private boolean userPickedCam = false;
    private int camRetry = 0;

    private final Runnable retryEnum = new Runnable() {
        @Override public void run() {
            if (buildCamList()) rebuildCamera();
            refreshUi();
        }
    };

    /** @return camId 是否发生了变化 */
    private boolean buildCamList() {
        String before = camId;
        camList.clear();
        try {
            for (String id : camMgr.getCameraIdList()) {
                CamInfo ci = describe(id);
                if (ci != null) camList.add(ci);
            }
        } catch (Throwable e) {
            log("enum cameras: " + e);
        }
        // 后置排前面，其次保持设备原始顺序
        final List<CamInfo> src = new ArrayList<CamInfo>(camList);
        camList.clear();
        for (CamInfo c : src) if (c.facing == CameraCharacteristics.LENS_FACING_BACK) camList.add(c);
        for (CamInfo c : src) if (c.facing != CameraCharacteristics.LENS_FACING_BACK) camList.add(c);

        String pLogical = null, pBack = null, pAny = null;
        boolean hasBack = false;
        for (CamInfo c : camList) {
            if (pAny == null) pAny = c.id;
            if (c.facing == CameraCharacteristics.LENS_FACING_BACK) {
                hasBack = true;
                if (pBack == null) pBack = c.id;
                if (c.logical && pLogical == null) pLogical = c.id;
            }
        }
        for (CamInfo c : camList) log("CAM " + c.id + " " + c.label);
        log("CAM list size=" + camList.size() + " hasBack=" + hasBack);

        // 少数 ROM 在相机 HAL 刚起来时会返回不完整的列表（漏掉后置），
        // 这时先别急着锁定，稍后重枚举几次
        if (!hasBack && camRetry < 3) {
            camRetry++;
            if (h != null) h.postDelayed(retryEnum, 1500);
        }

        if (!userPickedCam) {
            // 默认选：后置多摄融合 > 第一个后置 > 第一个
            String want = pLogical != null ? pLogical : (pBack != null ? pBack : pAny);
            if (want != null && !want.equals(camId)) {
                log("CAM auto select " + camId + " -> " + want);
                camId = want;
            }
        } else {
            boolean listed = false;
            for (CamInfo c : camList) if (c.id.equals(camId)) { listed = true; break; }
            if (!listed && pAny != null) camId = pAny;
        }
        return !camId.equals(before);
    }

    private CamInfo describe(String id) {
        try {
            CameraCharacteristics ch = camMgr.getCameraCharacteristics(id);
            Integer facingI = ch.get(CameraCharacteristics.LENS_FACING);
            int facing = facingI == null ? -1 : facingI;
            String face = facing == CameraCharacteristics.LENS_FACING_BACK ? "后置"
                    : facing == CameraCharacteristics.LENS_FACING_FRONT ? "前置" : "外接";

            float[] fl = ch.get(CameraCharacteristics.LENS_INFO_AVAILABLE_FOCAL_LENGTHS);
            String fstr = (fl == null || fl.length == 0) ? "?" :
                    String.format(Locale.US, "%.1fmm", fl[0]);

            float fov = -1f;
            try {
                SizeF phys = ch.get(CameraCharacteristics.SENSOR_INFO_PHYSICAL_SIZE);
                if (phys != null && fl != null && fl.length > 0 && phys.getWidth() > 0f) {
                    fov = (float) (2.0 * Math.atan(phys.getWidth() / (2.0 * fl[0])) * 180.0 / Math.PI);
                }
            } catch (Throwable ignored) { }
            String kind = "";
            if (fov > 0f) kind = fov >= 95f ? "超广角" : fov >= 75f ? "广角" : fov >= 45f ? "标准" : "长焦";

            boolean logical = false;
            try {
                Set<String> physIds = ch.getPhysicalCameraIds();
                logical = physIds != null && !physIds.isEmpty();
            } catch (Throwable ignored) { }

            CamInfo ci = new CamInfo();
            ci.id = id;
            ci.facing = facing;
            ci.logical = logical;
            ci.fov = fov;
            ci.label = "#" + id + " " + face
                    + (kind.length() > 0 ? " " + kind : "")
                    + " " + fstr
                    + (fov > 0f ? String.format(Locale.US, " %.0f°", fov) : "")
                    + (logical ? " 多摄" : "");
            return ci;
        } catch (Throwable e) {
            log("describe " + id + ": " + e);
            return null;
        }
    }

    private void showCamMenu(final View anchor) {
        PopupMenu pm = new PopupMenu(this, anchor);
        for (int i = 0; i < camList.size(); i++) {
            pm.getMenu().add(0, i, i, camList.get(i).label);
        }
        pm.setOnMenuItemClickListener(new PopupMenu.OnMenuItemClickListener() {
            @Override public boolean onMenuItemClick(android.view.MenuItem item) {
                CamInfo c = camList.get(item.getItemId());
                if (c == null || c.id.equals(camId)) return true;
                userPickedCam = true;
                camId = c.id;
                savePrefs();
                rebuildCamera();
                return true;
            }
        });
        pm.show();
    }

    // ==================== 对焦 ====================

    private static final int[] AF_ALL = {
            CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_VIDEO,
            CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_PICTURE,
            CaptureRequest.CONTROL_AF_MODE_AUTO,
            CaptureRequest.CONTROL_AF_MODE_MACRO,
            CaptureRequest.CONTROL_AF_MODE_OFF,
            CaptureRequest.CONTROL_AF_MODE_EDOF,
    };

    private final List<Integer> afModes = new ArrayList<Integer>();

    private void buildAfModes() {
        afModes.clear();
        int[] avail = null;
        try {
            CameraCharacteristics ch = camMgr.getCameraCharacteristics(camId);
            avail = ch.get(CameraCharacteristics.CONTROL_AF_AVAILABLE_MODES);
        } catch (Throwable e) {
            log("af modes: " + e);
        }
        for (int m : AF_ALL) {
            if (avail == null) { afModes.add(Integer.valueOf(m)); continue; }
            for (int a : avail) if (a == m) { afModes.add(Integer.valueOf(m)); break; }
        }
        if (afModes.isEmpty()) afModes.add(Integer.valueOf(CaptureRequest.CONTROL_AF_MODE_AUTO));

        boolean ok = false;
        for (Integer m : afModes) if (m == afMode) { ok = true; break; }
        if (!ok) { userPickedAf = false; }
        if (!userPickedAf) {
            // 没被用户显式指定过：按拍摄视频的习惯挑默认值
            int[] prefer = {
                    CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_VIDEO,
                    CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_PICTURE,
                    CaptureRequest.CONTROL_AF_MODE_AUTO,
                    CaptureRequest.CONTROL_AF_MODE_EDOF,
            };
            int pick = afModes.get(0).intValue();
            outer:
            for (int want : prefer) {
                for (Integer m : afModes) if (m == want) { pick = want; break outer; }
            }
            afMode = pick;
        }
    }

    private String afModeNameList() {
        StringBuilder sb = new StringBuilder("[");
        for (int i = 0; i < afModes.size(); i++) {
            if (i > 0) sb.append(",");
            sb.append(afName(afModes.get(i).intValue()));
        }
        return sb.append("]").toString();
    }

    private String afName(int m) {
        switch (m) {
            case CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_VIDEO: return "连续·视频";
            case CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_PICTURE: return "连续·拍照";
            case CaptureRequest.CONTROL_AF_MODE_AUTO: return "单次自动";
            case CaptureRequest.CONTROL_AF_MODE_MACRO: return "微距";
            case CaptureRequest.CONTROL_AF_MODE_OFF: return "手动对焦";
            case CaptureRequest.CONTROL_AF_MODE_EDOF: return "全景深";
            default: return "模式" + m;
        }
    }

    private String rotName(int r) {
        return (r % 360) + "°";
    }

    private void showFocusMenu(final View anchor) {
        PopupMenu pm = new PopupMenu(this, anchor);
        for (int i = 0; i < afModes.size(); i++) {
            pm.getMenu().add(0, i, i, afName(afModes.get(i).intValue()));
        }
        pm.setOnMenuItemClickListener(new PopupMenu.OnMenuItemClickListener() {
            @Override public boolean onMenuItemClick(android.view.MenuItem item) {
                afMode = afModes.get(item.getItemId()).intValue();
                userPickedAf = true;
                savePrefs();
                restartRepeating();
                refreshUi();
                return true;
            }
        });
        pm.show();
    }

    /** diopter(0=无穷远 .. minFocusDiopter=最近) <-> SeekBar 0..1000 */
    private float progressToDiopter(int prog) {
        if (minFocusDiopter <= 0f) return 0f;
        return minFocusDiopter * prog / 1000f;
    }

    private int diopterToProgress(float d) {
        if (minFocusDiopter <= 0f) return 0;
        int p = Math.round(1000f * d / minFocusDiopter);
        return Math.max(0, Math.min(1000, p));
    }

    /** 点击预览 → 在该点做自动对焦 + 测光 */
    private void tapToFocus(float vx, float vy) {
        if (preview == null || previewSurface == null) return;
        if (afMode == CaptureRequest.CONTROL_AF_MODE_OFF) return;   // 手动模式不响应点击
        if (maxAfRegions <= 0) { restartRepeating(); return; }

        int vw = preview.getWidth(), vh = preview.getHeight();
        if (vw == 0 || vh == 0) return;

        // 1) 视图坐标 -> 预览 buffer 坐标（逆用预览变换矩阵，含旋转/缩放/镜像）
        Matrix inv = new Matrix();
        previewXform.invert(inv);
        float[] pt = new float[]{vx, vy};
        inv.mapPoints(pt);
        float bx = Math.max(0f, Math.min(prevW, pt[0]));
        float by = Math.max(0f, Math.min(prevH, pt[1]));

        // 2) buffer 坐标 -> sensor active array 坐标
        //    HAL 会先把 active array 按输出宽高比居中裁剪，再缩放到输出尺寸
        float aw = activeArray.width(), ah = activeArray.height();
        float ar = prevW / (float) prevH;
        float cw, chh;
        if (aw / ah > ar) { chh = ah; cw = ah * ar; }
        else { cw = aw; chh = aw / ar; }
        float cx = activeArray.left + (aw - cw) / 2f;
        float cy = activeArray.top + (ah - chh) / 2f;
        float sx = cx + bx / prevW * cw;
        float sy = cy + by / prevH * chh;

        int size = (int) (Math.min(cw, chh) * 0.15f);
        size = Math.max(64, size);
        int left = (int) Math.max(activeArray.left, Math.min(activeArray.right - size, sx - size / 2f));
        int top = (int) Math.max(activeArray.top, Math.min(activeArray.bottom - size, sy - size / 2f));

        afRegions = new MeteringRectangle[]{
                new MeteringRectangle(left, top, size, size, MeteringRectangle.METERING_WEIGHT_MAX)
        };
        log("tap focus buf(" + (int) bx + "," + (int) by + ") -> sensor(" + left + "," + top + ")");

        h.post(new Runnable() {
            @Override public void run() {
                restartRepeating();
                try {
                    if (session != null) {
                        CaptureRequest.Builder b =
                                camera.createCaptureRequest(CameraDevice.TEMPLATE_RECORD);
                        b.addTarget(encSurface);
                        if (cfgPreview && previewSurface != null) b.addTarget(previewSurface);
                        applyFps(b, false);
                        applyFocus(b, true);
                        session.capture(b.build(), captureCb, h);
                    }
                } catch (Throwable e) {
                    log("af trigger: " + e);
                }
            }
        });
    }

    // ==================== 预览变换 ====================

    private int dispRotationDeg() {
        try {
            return getWindowManager().getDefaultDisplay().getRotation() * 90;
        } catch (Throwable ignored) { return 0; }
    }

    /** Camera2 标准公式：把 buffer 顺时针转这么多度，能在当前屏幕方向上摆正 */
    private int stdRotationDeg() {
        int degrees = dispRotationDeg();
        if (camFacing == CameraCharacteristics.LENS_FACING_FRONT) {
            return (360 - ((camSensorOrientation + degrees) % 360)) % 360;
        }
        return (camSensorOrientation - degrees + 360) % 360;
    }

    private int dispIndex() {
        int d = dispRotationDeg() / 90;
        return ((d % 4) + 4) % 4;
    }

    /** 实际使用的预览旋转角 */
    private int previewRotationDeg() {
        int d = dispIndex();
        if (rotByDisp[d] >= 0) return rotByDisp[d] % 360;
        // 这个方向还没校准过：从任意校准过的方向按「屏幕转多少、画面就跟着转多少」推算
        for (int j = 0; j < 4; j++) {
            if (rotByDisp[j] >= 0) {
                return ((rotByDisp[j] + (d - j) * 90) % 360 + 360) % 360;
            }
        }
        return DEFAULT_ROT;
    }

    /** 用户选定了某个绝对角度：记到当前屏幕方向的槽位里 */
    private void applyRotation(int absDeg) {
        int want = ((absDeg % 360) + 360) % 360;
        rotByDisp[dispIndex()] = want;
        savePrefs();
        if (preview != null) {
            preview.requestLayout();
            preview.post(new Runnable() {
                @Override public void run() { updatePreviewTransform(); }
            });
        }
        refreshUi();
    }

    /**
     * 预览变换。
     *
     * 关键点：TextureView 默认是把整张纹理「拉伸铺满」视图的，也就是说 setTransform
     * 的矩阵作用在【视图坐标】上，而不是相机 buffer 的像素坐标上。
     * 所以第一步必须先抵消那次拉伸（scale 成 prevW x prevH），后面按「buffer 像素
     * 坐标」推导才算得对 —— 少这一步的话画面会被整体推出可视区，表现就是全黑。
     *
     * 坐标系推导（buf 像素 -> 视图像素）：
     *   1. m.setScale(prevW/vw, prevH/vh)   抵消 TextureView 的拉伸
     *   2. postRotate(rot, c)               绕 buffer 中心旋转到正向
     *   3. postScale(k, k, c)               等比缩放（fit=完整画面 / cover=铺满裁剪）
     *   4. postTranslate(vw/2-cx, vh/2-cy)  把图心移到视图中心
     */
    private void updatePreviewTransform() {
        if (preview == null || previewSurface == null) return;
        final int vw = preview.getWidth(), vh = preview.getHeight();
        if (vw == 0 || vh == 0) return;
        if (cfgNoTransform) { try { preview.setTransform(new Matrix()); } catch (Throwable ignored) { } return; }

        int rot = previewRotationDeg();
        boolean swap = (rot % 180) == 90;
        float bw = swap ? prevH : prevW;    // 旋转后在视图里占的宽
        float bh = swap ? prevW : prevH;
        // 铺满裁剪（centerCrop）：无论画面比例与屏幕比例差多少，都放大到完全盖住视图，
        // 超出部分裁掉。用户要求「预览在任何情况都铺满屏幕」，取景框=整块屏幕。
        float k = Math.max(vw / bw, vh / bh);

        Matrix m = new Matrix();
        m.setScale(prevW / (float) vw, prevH / (float) vh);
        m.postRotate(rot, prevW / 2f, prevH / 2f);
        m.postScale(k, k, prevW / 2f, prevH / 2f);
        m.postTranslate(vw / 2f - prevW / 2f, vh / 2f - prevH / 2f);
        if (camFacing == CameraCharacteristics.LENS_FACING_FRONT) {
            m.postScale(-1f, 1f, vw / 2f, vh / 2f);   // 前置镜像，符合自拍直觉
        }
        synchronized (previewXform) { previewXform.set(m); }
        try { preview.setTransform(m); } catch (Throwable ignored) { }
    }

    private void pickPreviewSize() {
        try {
            CameraCharacteristics ch = camMgr.getCameraCharacteristics(camId);
            StreamConfigurationMap map = ch.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP);
            if (map == null) return;
            Size[] sizes = map.getOutputSizes(SurfaceTexture.class);
            if (sizes == null || sizes.length == 0) return;
            float want = cfgW / (float) cfgH;
            // 预览只是给眼睛看，做小一点：既能降低 ISP 带宽（避免和 1080p60 编码抢资源），
            // 也更省电。优先 ≤1280 宽的 16:9；没有才退到更大的或任意尺寸。
            Size best = null, small = null, anyBest = null;
            for (Size s : sizes) {
                if (s.getWidth() > 1920 || s.getHeight() > 1080) continue;
                boolean ar16x9 = Math.abs(s.getWidth() / (float) s.getHeight() - want) < 0.02f;
                if (ar16x9 && s.getWidth() <= 1280
                        && (best == null || s.getWidth() > best.getWidth())) best = s;
                if (s.getWidth() <= 1280
                        && (small == null || s.getWidth() > small.getWidth())) small = s;
                if (anyBest == null || s.getWidth() > anyBest.getWidth()) anyBest = s;
            }
            Size pick = best != null ? best : (small != null ? small : anyBest);
            if (pick != null) { prevW = pick.getWidth(); prevH = pick.getHeight(); }
        } catch (Throwable e) {
            log("pickPreviewSize: " + e);
        }
        log("preview size " + prevW + "x" + prevH);
    }

    // ==================== 网络 ====================

    private void startNetServer() {
        Thread t = new Thread(new Runnable() {
            @Override public void run() {
                try {
                    serverSocket = new ServerSocket(PORT);
                    log("TCP listening :" + PORT);
                    while (running) {
                        final Socket s = serverSocket.accept();
                        s.setTcpNoDelay(true);
                        s.setSendBufferSize(1 << 20);
                        final OutputStream os = s.getOutputStream();
                        clients.add(os);
                        watchClient(s, os);
                        log("client connected (" + clients.size() + " total)");
                        if (idlePaused) resumeCapture();
                    }
                } catch (Throwable e) {
                    log("netsrv ended: " + e);
                }
            }
        }, "netsrv");
        t.setDaemon(true);
        t.start();
    }

    // ==================== 编码器 ====================

    private void startEncoder() throws Exception {
        MediaFormat fmt = MediaFormat.createVideoFormat("video/avc", cfgW, cfgH);
        fmt.setInteger(MediaFormat.KEY_COLOR_FORMAT,
                MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface);
        fmt.setInteger(MediaFormat.KEY_BIT_RATE, cfgBitrate);
        fmt.setInteger(MediaFormat.KEY_FRAME_RATE, cfgFps);
        fmt.setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 1);
        fmt.setInteger(MediaFormat.KEY_BITRATE_MODE,
                MediaCodecInfo.EncoderCapabilities.BITRATE_MODE_CBR);
        try { fmt.setInteger(MediaFormat.KEY_PREPEND_HEADER_TO_SYNC_FRAMES, 1); } catch (Throwable ignored) {}
        // 高帧率相关：实时优先级 + 提示编码器按目标帧率运行 + 低延迟模式
        try { fmt.setInteger(MediaFormat.KEY_PRIORITY, 0); } catch (Throwable ignored) {}
        try { fmt.setInteger(MediaFormat.KEY_OPERATING_RATE, cfgFps); } catch (Throwable ignored) {}
        try { fmt.setInteger(MediaFormat.KEY_LATENCY, 1); } catch (Throwable ignored) {}

        codec = MediaCodec.createEncoderByType("video/avc");
        codec.configure(fmt, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE);
        encSurface = codec.createInputSurface();
        codec.start();
        muxer = new TsMuxer(cfgFps);
        log("encoder: " + codec.getCodecInfo().getName());

        Thread d = new Thread(new Runnable() {
            @Override public void run() { drainLoop(); }
        }, "drain");
        d.setDaemon(true);
        d.start();
    }

    private void drainLoop() {
        MediaCodec.BufferInfo info = new MediaCodec.BufferInfo();
        while (running) {
            try {
                // 暂停期间拉长等待，少空转
                int idx = codec.dequeueOutputBuffer(info, idlePaused ? 100000 : 10000);
                if (idx == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                    log("outfmt: " + codec.getOutputFormat());
                    idx = -1;   // 不能 continue：秒级统计/UI 刷新必须照跑
                }

                ByteBuffer bb = idx >= 0 ? codec.getOutputBuffer(idx) : null;
                if (bb != null && info.size > 0) {
                    bb.position(info.offset);
                    bb.limit(info.offset + info.size);
                    byte[] data = new byte[info.size];
                    bb.get(data);
                    boolean key = (info.flags & MediaCodec.BUFFER_FLAG_KEY_FRAME) != 0;
                    byte[] ts = muxer.pack(data, key);
                    if (!clients.isEmpty()) {
                        for (OutputStream o : clients) {
                            try {
                                o.write(ts);
                            } catch (Throwable e) {
                                clients.remove(o);
                                log("client dropped (" + clients.size() + " left)");
                            }
                        }
                    }
                    encFrames++;
                    totalFrames++;
                    encBytes += ts.length;
                }
                if (idx >= 0) codec.releaseOutputBuffer(idx, false);

                // 预览链路探针：时间戳前进一帧就记一次
                SurfaceTexture st = previewST;
                if (st != null) {
                    long ts = st.getTimestamp();
                    if (ts != 0 && ts != lastPreviewTs) { lastPreviewTs = ts; previewFrames++; }
                }

                long now = System.currentTimeMillis();
                if (now - statAt >= 1000) {
                    lastFps = (int) (encFrames * 1000 / Math.max(1, now - statAt));
                    capFps = (int) (captureFrames * 1000 / Math.max(1, now - statAt));
                    lastKbps = (int) (encBytes * 8 / Math.max(1, now - statAt));
                    lastPrevFps = (int) ((previewFrames - prevFramesAtStat) * 1000
                            / Math.max(1, now - statAt));
                    prevFramesAtStat = previewFrames;
                    encFrames = 0; encBytes = 0; captureFrames = 0; statAt = now;
                    Log.i(TAG, "STAT enc=" + lastFps + "fps cap=" + capFps + "fps " + lastKbps + "kbps"
                            + " sensorDur=" + sensorFrameNs + " exp=" + exposureNs
                            + " iso=" + iso + " ae=" + aeState + " af=" + afState
                            + " prev=" + lastPrevFps + "fps prevBuf=" + prevW + "x" + prevH
                            + " view=" + viewW + "x" + viewH
                            + " viewPos=" + viewX + "," + viewY
                            + " rot=" + previewRotationDeg()
                            + " slots=" + rotByDisp[0] + "/" + rotByDisp[1] + "/"
                            + rotByDisp[2] + "/" + rotByDisp[3]
                            + " disp=" + dispRotationDeg() + " std=" + stdRotationDeg()
                            + " probe=" + String.format(Locale.US, "%.1f", probeMean)
                            + (cfgNoTransform ? " notrans" : "")
                            + " | " + diag);
                    // UI 刷新降频到 2s，避免高帧率下 TextView 重绘抢资源
                    if (now - uiAt >= 2000) { uiAt = now; refreshUi(); }

                    // 空闲省电：连续 cfgIdle 秒没有客户端就停采集；有客户端立刻恢复
                    if (cfgIdle > 0) {
                        if (clients.isEmpty()) {
                            idleMs += 1000;   // 该分支每秒统计周期执行一次
                            if (idleMs >= cfgIdle * 1000L && !idlePaused) pauseCapture();
                        } else {
                            idleMs = 0;
                            if (idlePaused) resumeCapture();
                        }
                    }
                }
            } catch (Throwable e) {
                Log.e(TAG, "drain", e);
            }
        }
    }

    // ==================== 相机 ====================

    private void maybeOpenCamera() {
        if (!running) return;
        if (encSurface == null) return;                       // 编码器还没好
        if (cfgPreview && previewSurface == null) return;     // 预览还没好
        if (camera != null) return;                           // 已经开着
        openCamera();
    }

    private void openCamera() {
        try {
            if (Build.VERSION.SDK_INT >= 23
                    && checkSelfPermission(Manifest.permission.CAMERA) != 0) {
                log("NO CAMERA PERMISSION");
                lastErr = "没有相机权限";
                return;
            }
            CameraCharacteristics ch = camMgr.getCameraCharacteristics(camId);
            Integer f = ch.get(CameraCharacteristics.LENS_FACING);
            camFacing = f == null ? -1 : f;
            Integer so = ch.get(CameraCharacteristics.SENSOR_ORIENTATION);
            camSensorOrientation = so == null ? 0 : so;
            Rect aa = ch.get(CameraCharacteristics.SENSOR_INFO_ACTIVE_ARRAY_SIZE);
            if (aa != null) activeArray = aa;
            Integer mr = ch.get(CameraCharacteristics.CONTROL_MAX_REGIONS_AF);
            maxAfRegions = mr == null ? 0 : mr;
            Float mfd = ch.get(CameraCharacteristics.LENS_INFO_MINIMUM_FOCUS_DISTANCE);
            minFocusDiopter = mfd == null ? 0f : mfd;
            manualFocusSupported = minFocusDiopter > 0f;
            buildAfModes();
            pickPreviewSize();
            StringBuilder sb = new StringBuilder();
            sb.append("cam=").append(camId).append(" facing=").append(camFacing)
              .append(" sensorOri=").append(camSensorOrientation)
              .append(" active=").append(activeArray.width()).append("x").append(activeArray.height())
              .append(" afAvail=").append(afModeNameList()).append(" afMode=").append(afMode)
              .append(" afRegions=").append(maxAfRegions)
              .append(" minFocus=").append(minFocusDiopter)
              .append(" prevSize=").append(prevW).append("x").append(prevH)
              .append(" cams=");
            for (int i = 0; i < camList.size(); i++) {
                if (i > 0) sb.append(" / ");
                sb.append(camList.get(i).label);
            }
            diag = sb.toString();
            log("opening camera " + camId + " facing=" + camFacing
                    + " sensorOri=" + camSensorOrientation + " active=" + activeArray
                    + " afRegions=" + maxAfRegions + " minFocus=" + minFocusDiopter);

            // 预览 buffer 尺寸可能随摄像头变化
            if (preview != null && preview.getSurfaceTexture() != null) {
                try { preview.getSurfaceTexture().setDefaultBufferSize(prevW, prevH); }
                catch (Throwable ignored) { }
            }
            runOnUiThread(new Runnable() {
                @Override public void run() { updatePreviewTransform(); refreshUi(); }
            });

            camMgr.openCamera(camId, new CameraDevice.StateCallback() {
                @Override public void onOpened(CameraDevice d) {
                    camera = d;
                    log("camera opened " + camId);
                    createSession();
                }
                @Override public void onDisconnected(CameraDevice d) { log("camera disconnected"); }
                @Override public void onError(CameraDevice d, int err) {
                    log("camera ERROR " + err);
                    lastErr = "相机错误 " + err;
                }
            }, h);
        } catch (Throwable e) {
            log("openCamera: " + e);
            lastErr = "openCamera: " + e;
            Log.e(TAG, "openCamera", e);
        }
    }

    private void closeCamera() {
        try { if (session != null) { session.close(); session = null; } } catch (Throwable ignored) {}
        try { if (camera != null) { camera.close(); camera = null; } } catch (Throwable ignored) {}
    }

    /** 切换摄像头 / 开关预览后重建 */
    private void rebuildCamera() {
        h.post(new Runnable() {
            @Override public void run() {
                closeCamera();
                h.postDelayed(new Runnable() {
                    @Override public void run() { maybeOpenCamera(); }
                }, 300);
            }
        });
    }

    private List<Surface> targets(boolean withEncoder) {
        List<Surface> outs = new ArrayList<Surface>();
        if (withEncoder && encSurface != null) outs.add(encSurface);
        if (cfgPreview && previewSurface != null) outs.add(previewSurface);
        return outs;
    }

    private void createSession() {
        if (cfgHs == 1) {
            try {
                log("trying constrained high-speed session");
                camera.createConstrainedHighSpeedCaptureSession(targets(true),
                        new CameraCaptureSession.StateCallback() {
                    @Override public void onConfigured(CameraCaptureSession s) {
                        log("HS session CONFIGURED");
                        session = s;
                        startHighSpeed((CameraConstrainedHighSpeedCaptureSession) s);
                    }
                    @Override public void onConfigureFailed(CameraCaptureSession s) {
                        log("HS session FAILED -> fallback normal");
                        createNormalSession();
                    }
                }, h);
                return;
            } catch (Throwable e) {
                log("HS create threw: " + e);
            }
        }
        createNormalSession();
    }

    private void createNormalSession() {
        try {
            camera.createCaptureSession(targets(true), new CameraCaptureSession.StateCallback() {
                @Override public void onConfigured(CameraCaptureSession s) {
                    session = s;
                    log("normal session configured, targets="
                            + (cfgPreview && previewSurface != null ? "enc+preview" : "enc"));
                    idlePaused = false;
                    idleMs = 0;
                    startRepeating();
                }
                @Override public void onConfigureFailed(CameraCaptureSession s) {
                    log("normal session CONFIGURE FAILED");
                    lastErr = "session 配置失败";
                }
            }, h);
        } catch (Throwable e) {
            log("createNormalSession: " + e);
            lastErr = "createSession: " + e;
        }
    }

    private void startHighSpeed(CameraConstrainedHighSpeedCaptureSession hs) {
        try {
            CaptureRequest.Builder bld = camera.createCaptureRequest(CameraDevice.TEMPLATE_RECORD);
            bld.addTarget(encSurface);
            if (cfgPreview && previewSurface != null) bld.addTarget(previewSurface);
            bld.set(CaptureRequest.CONTROL_AE_TARGET_FPS_RANGE, new Range<Integer>(30, cfgFps));
            List<CaptureRequest> reqs = hs.createHighSpeedRequestList(bld.build());
            hs.setRepeatingBurst(reqs, captureCb, h);
            log("HS repeating burst started, requests=" + reqs.size());
        } catch (Throwable e) {
            log("startHighSpeed: " + e);
        }
    }

    /** 重建并提交 repeating 请求（切换对焦模式、点对焦、拖动手动对焦后调用） */
    private void restartRepeating() {
        h.post(new Runnable() {
            @Override public void run() { startRepeating(); }
        });
    }

    private void startRepeating() {
        try {
            if (session == null || camera == null) return;
            // 空闲暂停：没有客户端，只保留低帧率预览（编码器完全不出帧）
            if (idlePaused && !(cfgPreview && previewSurface != null)) return;
            if (idlePaused && cfgPreview && previewSurface != null) {
                CaptureRequest.Builder b = camera.createCaptureRequest(CameraDevice.TEMPLATE_RECORD);
                b.addTarget(previewSurface);
                b.set(CaptureRequest.CONTROL_AE_TARGET_FPS_RANGE,
                        new Range<Integer>(cfgIdleFps, cfgIdleFps));
                applyFocus(b, false);
                session.setRepeatingRequest(b.build(), captureCb, h);
                return;
            }

            CaptureRequest.Builder bld = camera.createCaptureRequest(CameraDevice.TEMPLATE_RECORD);
            bld.addTarget(encSurface);
            if (cfgPreview && previewSurface != null) bld.addTarget(previewSurface);
            applyFps(bld, true);
            applyFocus(bld, false);
            session.setRepeatingRequest(bld.build(), captureCb, h);
            log("repeating started (target " + cfgFps + "fps, preview="
                    + (cfgPreview && previewSurface != null) + ")");
        } catch (Throwable e) {
            log("startRepeating: " + e);
            lastErr = "startRepeating: " + e;
            Log.e(TAG, "startRepeating", e);
        }
    }

    private void applyFps(CaptureRequest.Builder bld, boolean withVendor) {
        if (cfgSetFpsRange) {
            bld.set(CaptureRequest.CONTROL_AE_TARGET_FPS_RANGE, new Range<Integer>(cfgFps, cfgFps));
        }
        if (cfgExpCap > 0) {
            bld.set(CaptureRequest.SENSOR_EXPOSURE_TIME, cfgExpCap);
        }
        if (withVendor) applyVendorFps(bld);
    }

    private void applyFocus(CaptureRequest.Builder bld, boolean trigger) {
        try {
            bld.set(CaptureRequest.CONTROL_AF_MODE, afMode);
            if (afMode == CaptureRequest.CONTROL_AF_MODE_OFF) {
                if (manualFocusSupported) bld.set(CaptureRequest.LENS_FOCUS_DISTANCE, focusDistance);
            } else {
                if (afRegions != null && maxAfRegions > 0) {
                    bld.set(CaptureRequest.CONTROL_AF_REGIONS, afRegions);
                    bld.set(CaptureRequest.CONTROL_AE_REGIONS, afRegions);
                    bld.set(CaptureRequest.CONTROL_AWB_REGIONS, afRegions);
                }
            }
            bld.set(CaptureRequest.CONTROL_AF_TRIGGER, trigger
                    ? CaptureRequest.CONTROL_AF_TRIGGER_START
                    : CaptureRequest.CONTROL_AF_TRIGGER_IDLE);
        } catch (Throwable e) {
            log("applyFocus: " + e);
        }
    }

    private final CameraCaptureSession.CaptureCallback captureCb =
            new CameraCaptureSession.CaptureCallback() {
        @Override
        public void onCaptureCompleted(CameraCaptureSession s, CaptureRequest req,
                                       TotalCaptureResult res) {
            captureFrames++;
            Long fd = res.get(CaptureResult.SENSOR_FRAME_DURATION);
            Long exp = res.get(CaptureResult.SENSOR_EXPOSURE_TIME);
            Integer isoV = res.get(CaptureResult.SENSOR_SENSITIVITY);
            Integer ae = res.get(CaptureResult.CONTROL_AE_STATE);
            Integer afs = res.get(CaptureResult.CONTROL_AF_STATE);
            Float fdist = res.get(CaptureResult.LENS_FOCUS_DISTANCE);
            if (fd != null) sensorFrameNs = fd;
            if (exp != null) exposureNs = exp;
            if (isoV != null) iso = isoV;
            if (ae != null) aeState = ae;
            if (afs != null) afState = afs;
            if (fdist != null) focusDiopterNow = fdist;
        }
    };

    @SuppressWarnings("unchecked")
    private void applyVendorFps(CaptureRequest.Builder bld) {
        if (cfgVmode == 0) return;
        try {
            CameraCharacteristics ch = camMgr.getCameraCharacteristics(camera.getId());
            long durNs = 1_000_000_000L / cfgFps;
            boolean any = false;
            for (CaptureRequest.Key<?> k : ch.getAvailableCaptureRequestKeys()) {
                String n = k.getName();
                if ((cfgVmode == 1 || cfgVmode == 3) && n.endsWith("manualExposure.disableFPSLimits")) {
                    bld.set((CaptureRequest.Key<Byte>) k, (byte) 1);
                    log("vendor disableFPSLimits=1");
                    any = true;
                } else if ((cfgVmode == 2 || cfgVmode == 3)
                        && n.endsWith("manualExposure.overrideSensorFrameDuration")) {
                    bld.set((CaptureRequest.Key<Long>) k, durNs);
                    log("vendor overrideSensorFrameDuration=" + durNs);
                    any = true;
                }
            }
            if (!any) log("vendor keys NOT found");
        } catch (Throwable e) {
            log("applyVendorFps: " + e);
        }
    }

    /**
     * 每个客户端一个阻塞读线程：对端一关闭就立刻感知并摘除。
     * 只靠"写失败"判断断开会滞后（TCP 半开时可能几十秒都写成功），
     * 会让省电暂停迟迟不生效。
     */
    private void watchClient(final Socket s, final OutputStream os) {
        Thread t = new Thread(new Runnable() {
            @Override public void run() {
                try {
                    InputStream in = s.getInputStream();
                    byte[] buf = new byte[256];
                    while (in.read(buf) >= 0) { /* 客户端不发数据，阻塞等待断开 */ }
                } catch (Throwable ignored) {
                } finally {
                    clients.remove(os);
                    try { s.close(); } catch (Throwable ignored) {}
                    log("client disconnected (" + clients.size() + " left)");
                }
            }
        }, "watch-client");
        t.setDaemon(true);
        t.start();
    }

    // ==================== 省电 ====================

    /**
     * 只把本 Activity 所在窗口压暗，不改系统亮度设定。
     * 好处：退出 app 或崩溃后亮度自动恢复，不会把手机留在"全黑"状态。
     */
    private void applyDim() {
        runOnUiThread(new Runnable() {
            @Override public void run() {
                try {
                    float v;
                    if (!cfgDim) v = WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_NONE;
                    else if (cfgBright >= 2) v = WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_FULL;
                    else if (cfgBright == 1) v = BRIGHT_LOW;
                    else v = BRIGHT_MIN;
                    WindowManager.LayoutParams lp = getWindow().getAttributes();
                    lp.screenBrightness = v;
                    getWindow().setAttributes(lp);
                } catch (Throwable ignored) {}
            }
        });
    }

    private void applyDimOff() {
        runOnUiThread(new Runnable() {
            @Override public void run() {
                try {
                    WindowManager.LayoutParams lp = getWindow().getAttributes();
                    lp.screenBrightness = WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_NONE;
                    getWindow().setAttributes(lp);
                } catch (Throwable ignored) {}
            }
        });
    }

    private void watchBattery() {
        try {
            batteryRx = new BroadcastReceiver() {
                @Override public void onReceive(Context c, Intent i) {
                    int t = i.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, -1);
                    if (t > 0) battTemp = t / 10.0f;
                }
            };
            registerReceiver(batteryRx, new IntentFilter(Intent.ACTION_BATTERY_CHANGED));
        } catch (Throwable ignored) {}
    }

    /** 没有客户端时停掉编码采集；若开了预览，只保留低帧率预览方便继续取景 */
    private void pauseCapture() {
        h.post(new Runnable() {
            @Override public void run() {
                try {
                    if (session != null) {
                        session.stopRepeating();
                        idlePaused = true;
                        idleMs = 0;
                        lastErr = "";
                        Log.w(TAG, "IDLE pause ok");
                        if (cfgPreview && previewSurface != null) startRepeating();
                    } else {
                        lastErr = "pause: session=null";
                    }
                } catch (Throwable e) { lastErr = "pause: " + e; Log.e(TAG, "pauseCapture", e); }
            }
        });
    }

    private void resumeCapture() {
        h.post(new Runnable() {
            @Override public void run() {
                // session 尚未就绪时直接返回：onConfigured 里本来就会 startRepeating
                if (session == null) return;
                try {
                    idlePaused = false;
                    idleMs = 0;
                    startRepeating();
                    Log.w(TAG, "IDLE resume ok");
                } catch (Throwable e) { lastErr = "resume: " + e; Log.e(TAG, "resumeCapture", e); }
            }
        });
    }

    // ==================== 杂项 ====================

    private void log(String m) { Log.i(TAG, m); }

    private static final String[] AE_NAMES =
            {"INACTIVE", "SEARCHING", "CONVERGED", "LOCKED", "FLASH_REQ", "PRECAPTURE"};
    private static final String[] AF_NAMES =
            {"INACTIVE", "PASSIVE_SCAN", "PASSIVE_FOCUSED", "ACTIVE_SCAN",
             "FOCUSED_LOCKED", "NOT_FOCUSED_LOCKED", "PASSIVE_UNFOCUSED"};

    private void refreshUi() {
        final String ae = (aeState >= 0 && aeState < AE_NAMES.length) ? AE_NAMES[aeState] : "?";
        final String af = (afState >= 0 && afState < AF_NAMES.length) ? AF_NAMES[afState] : "?";
        final long elapsed = Math.max(1, System.currentTimeMillis() - startedAt);
        final int avgFps = (int) (totalFrames * 1000 / elapsed);
        final String camLabel = camLabel();
        final String focusTxt = afMode == CaptureRequest.CONTROL_AF_MODE_OFF
                ? (manualFocusSupported
                    ? String.format(Locale.US, "手动 %.2fD(实际 %s)",
                        focusDistance,
                        focusDiopterNow >= 0 ? String.format(Locale.US, "%.2f", focusDiopterNow) : "?")
                    : "手动（此摄像头不支持）")
                : afName(afMode) + " " + af;
        final String text;
        try {
        text = String.format(Locale.US,
                        "PhoneCam  %dx%d  目标 %d fps   编码 %d fps   "
                                + "平均 %d fps   %d kbps\n"
                                + "sensor 输出 %d fps   帧时长 %s   曝光 %s   ISO %d   AE %s\n"
                                + "摄像头 %s\n"
                                + "对焦 %s   客户端 %s\n"
                                + "预览 %s  收帧 %d (%.0f fps)\n"
                                + "电池 %.1f°C   %s   预览开关 %s   亮度 %s   空闲 %d/%ds %s\n"
                                + "vmode=%d  fpsRange=%s  tcp://127.0.0.1:%d",
                cfgW, cfgH, cfgFps, lastFps,
                avgFps, lastKbps,
                capFps,
                sensorFrameNs > 0 ? (sensorFrameNs / 1000) + "us("
                        + (1000000000L / sensorFrameNs) + "fps)" : "?",
                exposureNs > 0 ? (exposureNs / 1000) + "us" : "?",
                iso, ae,
                camLabel,
                focusTxt, clients.size() > 0 ? ("已连接 " + clients.size()) : "等待",
                cfgPreview ? (prevW + "x" + prevH) : "关", previewFrames,
                previewFpsAvg(),
                battTemp, idlePaused ? "省电暂停中" : "工作中",
                cfgPreview ? "开" : "关",
                cfgBright >= 2 ? "正常" : (cfgBright == 1 ? "暗" : "最暗"),
                idleMs / 1000, cfgIdle, lastErr,
                cfgVmode, cfgSetFpsRange ? "on" : "off", PORT);
        } catch (Throwable fe) {
            Log.e(TAG, "ui format", fe);
            return;
        }

        final boolean showSeek = cfgPreview && afMode == CaptureRequest.CONTROL_AF_MODE_OFF
                && manualFocusSupported;
        final int progress = diopterToProgress(focusDistance);
        runOnUiThread(new Runnable() {
            @Override public void run() {
                if (preview != null) {
                    viewW = preview.getWidth();
                    viewH = preview.getHeight();
                    int[] loc = new int[2];
                    preview.getLocationOnScreen(loc);
                    viewX = loc[0];
                    viewY = loc[1];
                }
                if (cfgProbe && preview != null && preview.isAvailable()) {
                    try {
                        android.graphics.Bitmap bm = preview.getBitmap(32, 18);
                        long sum = 0;
                        for (int yy = 0; yy < bm.getHeight(); yy++) {
                            for (int xx = 0; xx < bm.getWidth(); xx++) {
                                int p = bm.getPixel(xx, yy);
                                sum += ((p >> 16) & 0xff) + ((p >> 8) & 0xff) + (p & 0xff);
                            }
                        }
                        probeMean = sum / (double) (bm.getWidth() * bm.getHeight() * 3);
                    } catch (Throwable t) { probeMean = -2; }
                }
                if (status != null) status.setText(text);
                if (btnCam != null) btnCam.setText("摄像头 " + camLabel);
                if (btnFocus != null) btnFocus.setText("对焦 " + afName(afMode));
                if (btnBright != null) btnBright.setText("亮度 "
                        + (cfgBright >= 2 ? "正常" : (cfgBright == 1 ? "暗" : "最暗")));
                if (btnPreview != null) btnPreview.setText("预览 " + (cfgPreview ? "开" : "关"));
                if (btnRot != null) btnRot.setText("旋转 " + rotName(previewRotationDeg()));
                if (seekRow != null) seekRow.setVisibility(showSeek ? View.VISIBLE : View.GONE);
                if (seekFocus != null && showSeek) seekFocus.setProgress(progress);
            }
        });
    }

    /** 预览 surface 自启动以来的平均收帧率（诊断预览链路是否真的在出帧） */
    private double previewFpsAvg() {
        long el = Math.max(1, System.currentTimeMillis() - startedAt);
        return previewFrames * 1000.0 / el;
    }

    private String camLabel() {
        for (CamInfo c : camList) if (c.id.equals(camId)) return c.label;
        return "#" + camId;
    }
}
