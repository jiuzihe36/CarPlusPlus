# 第三方样本逆向分析：`智慧车联_v1.5.3-支持方控.apk`

> 本文回答一个问题：**这个第三方 App 用的是什么协议？为什么装到车机后小米手机能直接识别？能不能照这个路子做 Car++？**
>
> 分析方法：静态逆向（解包 + DEX 字节码 + 原生库字符串 + 资源表），**未运行样本、未接入真实车辆**。所有结论都附文件/类/方法级证据。

---

## 0. 结论摘要（先看这段）

| # | 结论 | 置信度 |
|---|---|---|
| 1 | 该 App **就是 ICCOA CarLink 车机端**，用的是联盟官方车机端 SDK（内部代号 `ucarhusdk` / 包名 `com.ucar.*` + `com.share.connect.*`），**不是**百度 CarLife，也**不是**亿连 | **确定**（`libovmsink.so` 内含 `ICCOA` 字符串、DEX 含 `ICCOA_PROTOCOL_VERSION_CODE_1_0/_1_5`、`ICCOA CarLink` 字面量） |
| 2 | 它的真实身份：**吉利（Geely）换皮的 ICCOA 官方 demo**。`applicationId` 改成了 `com.geely.carlink`，但所有类仍在 `com.ucarhu.demo.*`（官方 demo 包名），SDK 版本串 `3.0.0-v1.5.3-202401092110-支持方控` | **确定**（`UCarDemoActivity` 为 launcher activity；`com.ucarhu.demo` 是 ICCOA 官方 demo 的公开包名） |
| 3 | 小米手机能识别它，**不是因为过了 ICCOA 认证**，而是因为 ICCOA 的**"用户确认 + 配对码"配对流程**本身对未认证设备开放 | **高**（APK 内置自签测试证书 + 文案「出现连接弹窗后输入…码进行连接验证」+ 官方分发页描述的连接步骤一致） |
| 4 | 证书**不是**认证的门槛：`CarVerificationData.isValid()` **只检查字段非空，不做任何签名/CA 校验**；且该 APK 内置的是一份 `vendor_id=0001 / product_id=0001`、名字明写「测试车机」的自签证书 | **确定**（字节码级证据，见 §6） |
| 5 | **可以移植**。SDK 的公开 API 非常干净（`init()` / `startAdvertise()` / `startCast(Surface,w,h)` / `sendTouchEvent()`），证书路径可通过 `UCarConfig.setCcdFilePath()` 换成自己的 | **高** |
| 6 | 但**不建议**直接把这个 APK 当成品用：它是 `android:debuggable="true"` 的 **debug 签名包**（AOSP 公开调试密钥），并声明了一堆非系统应用拿不到的私有权限 | **确定**（manifest + 签名证书） |

> ⚠️ **对既有结论的修正**：`docs/FEASIBILITY.md` §2 原先裁定「ICCOA 必须过联盟认证 → 对个人项目是死路 → 排除」。**这个样本直接证伪了该结论。** 已在本文件 §9 给出修正后的路线，并同步回 FEASIBILITY.md。

---

## 1. 样本基本信息

| 项 | 值 |
|---|---|
| 文件名 | `智慧车联_v1.5.3-支持方控.apk` |
| 大小 | 6,495,573 B |
| SHA-256 | `645127cb0c75ba769aa06f7ce457f1ee6b03d19f03af956545ef0398ce9e0d7e` |
| **applicationId** | **`com.geely.carlink`** |
| 应用显示名 | 智慧车联 |
| versionName / Code | `v1.5.3-支持方控` / `300000` |
| minSdk / targetSdk | **27** / 30 |
| 主 Activity | `com.ucarhu.demo.UCarDemoActivity` |
| Application | `com.ucarhu.demo.MyApplication` |
| SDK 版本串 | `3.0.0-v1.5.3-202401092110-支持方控`（2024-01-09 21:10 构建） |
| 原生库 | 仅 `arm64-v8a`：`libovmsink.so` (1,481,168 B)、`libusbio.so` (215,040 B) |
| Assets | `ccd.data`（2,120 B，车辆证书） |
| 根目录附带 | `ucar.proto`（658 行，**完整协议定义**）、`test_pb_data.proto` |
| **签名** | **Android 官方调试密钥**（`CN=Android, O=Android, C=US`，SHA-1 `61ed377e85d386a8dfee6b864bd85b0bfaa5af81`），`android:debuggable="true"` |

### 1.1 顶层包分布（DEX 类数）

```
1948  com/google/common        ← Guava（protobuf 依赖）
1331  com/google/crypto        ← Tink（AES-GCM / ECDSA / ECIES）
 360  com/google/protobuf
 245  com/ucar/databus         ← protobuf 生成物（UCarProto.*）
 223  com/ucar/vehiclesdk      ← 车机端 SDK 主体（UCarAdapter 等）
 205  com/share/connect        ← 连接层（BLE / WiFiAP / WiFiP2P / 安全）
 172  com/ucarhu/demo          ← 官方 demo App（UI 层）
  87  com/ucar/protocol        ← 协议封装（UCarMessage / 通道 / 鉴权 / 证书）
  18  com/ucar/sdk
  16  com/ucar/connect         ← AOA（USB）连接
```

**命名规律**：`ucar` = 智慧车联（UCar），`ucarhu` = **UCar Head Unit**（车机端），`com.share.connect` = ICCOA SDK 内部的"共享连接"框架（`ShareLinkService` / `ShareLinkObserver` / `IShareLinkManager` 构成一套独立 AIDL 服务接口）。

---

## 2. 协议身份判定（证据链）

| 证据 | 位置 | 说明 |
|---|---|---|
| 字面量 `ICCOA` | `lib/arm64-v8a/libovmsink.so` | 原生库内唯一出现的 `ICCOA` 字符串 |
| `ICCOA_PROTOCOL_VERSION_CODE_1_0` / `_1_5` | DEX 字符串池 | 明确实现了 ICCOA 协议的 **1.0** 与 **1.5** 两个版本号 |
| 字面量 `ICCOA CarLink` / `ICCOA Carlink` | DEX 字符串池 | |
| 热点前缀 `DIRECT-ICCOA-` | DEX 字符串池 | **WiFi Direct 组名前缀**，手机靠它扫出车机 |
| 原生库构建路径 `E:/1.5.3verson/ucarhusdk/core/src/main/cpp/...` | `libovmsink.so` | SDK 工程名 `ucarhusdk` |
| `libovmsink.so` 内含 AOSP WifiDisplaySink 全套 | `wifi-display/WifiDisplaySink.cpp`、`ANetworkSession.cpp`、`aosp connecting socket` | 投屏走 **Wi-Fi Display（Miracast）RTSP + RTP** |
| proto 文件 `ucar.proto`，`java_package = "com.ucar.databus.proto"` | APK 根目录 | 协议定义完整可读 |

**结论**：这是 **ICCOA CarLink 车机端（UCar Head Unit）SDK v1.5.x**，与百度 CarLife、亿连**无关**。上一轮调研里把"CarWith 通道"排到 ICCOA 就停住、并判定为死路，是对样本不足下的误判。

---

## 3. 协议栈分解

```
┌────────────────────────────────────────────────────────────────────────┐
│  发现 / 配对                                                            │
│  BLE GATT Server  ← com.share.connect.ble.BluetoothLeService            │
│    Service/Char UUID: 2abcc850-9935-4f8a-ba84-123456789100 / 101 / 102  │
│    (另有标准 GAP/GATT: 0x1800/0x1801, CCCD 0x2902)                      │
│  WiFi Direct 组名: DIRECT-ICCOA-XXXX                                    │
│  PIN 码配对（UCarPinCodeView）+ 手机弹窗「用户确认」(userConfirmed)       │
├────────────────────────────────────────────────────────────────────────┤
│  传输（三选一）                                                          │
│  ① USB AOA    com.ucar.connect.aoa.AOAConnectManager + libusbio.so      │
│  ② 车机做 AP  com.share.connect.wifiap.WifiApService (TetheringManager) │
│  ③ WiFi Direct com.share.connect.wifip2p.WifiP2pService                │
│  控制/数据通道为自建 socket：com.ucar.protocol.channel.socket            │
├────────────────────────────────────────────────────────────────────────┤
│  鉴权（自包含，不联外网）                                                │
│  ECDH 会话密钥协商  → KeyNegotiator / ServerKeyNegotiator               │
│  ECDSA 身份签名     → generateAuthenticationPublicKey / generateSign     │
│  HMAC 完整性        → generateHmac                                      │
│  PIN 码 + Peer 信任库(com.share.connect.security.Peer, Room 持久化)      │
│  密钥落 Android Keystore（alias: auth_key / session_key / uibc_session_key）│
├────────────────────────────────────────────────────────────────────────┤
│  业务协议（protobuf over 独立通道）                                       │
│  ucar.proto: 35 种 Control 消息 + Auth/Cert/Audio/Sensor/UIBC            │
│  通道类型: CMD / VIDEO / AUDIO / TTS / VR / UIBC / RTSP / RTP / AUTH /  │
│            CERT / SENSOR / CREATE_SOCKET / CAST_RTSP / CAST_RTP           │
│  消息类别: NONE / CONTROL / SENSOR / AUTH / AUDIO / VIDEO / CERT / ACK    │
├────────────────────────────────────────────────────────────────────────┤
│  投屏（视频）                                                            │
│  Wi-Fi Display (Miracast) Sink ← libovmsink.so                          │
│  RTSP 协商 → RTP 收流 → H.264 解码（可选 HEVC: isSupportHevc）           │
│  接收端 Surface 由车机 App 提供（startCast(Surface, w, h)）              │
│  输入回传走 UIBC（UibcManager）→ sendTouchEvent / sendKeyEvent           │
├────────────────────────────────────────────────────────────────────────┤
│  音频                                                                   │
│  三条独立流：STREAM_CAST_MUSIC(0x10) / STREAM_TTS(6) / STREAM_AI_ASSISTANT(3)│
│  采样率 8k–192k，单/双声道，PCM 8/16/24/32bit/FLOAT                       │
│  麦克风：车机采集后经 UIBC/MIC 通道回传给手机（可开立体声录制）             │
├────────────────────────────────────────────────────────────────────────┤
│  车机数据上行（Sensor 通道）                                             │
│  GPS / 灯光 / 陀螺仪 / 加速度 / 油量 / 挡位 / 光照传感器 / 电池           │
│  → 手机据此判断"行驶中/驻车"，决定是否允许视频等操作                       │
└────────────────────────────────────────────────────────────────────────┘
```

### 3.1 通道 ID 常量（DEX 中提取）

```
MSG_CHANNEL_ID_CMD            MSG_CHANNEL_ID_AUTH
MSG_CHANNEL_ID_SENSOR         MSG_CHANNEL_ID_CERT
MSG_CHANNEL_ID_AUDIO          MSG_CHANNEL_ID_UIBC
MSG_CHANNEL_ID_CAST_RTSP      MSG_CHANNEL_ID_CAST_RTP
MSG_CHANNEL_ID_CREATE_SOCKET  MSG_CHANNEL_ID_MAX
```

### 3.2 关键 `ucar.proto` 消息

- **配置协商**：`GetUCarConfigRequest/Response` —— 车机上报 `screenWidth/Height`、`videoDisplayWidth/Height`、`dpi`/`realDpi`、`fps`、`isSupportP2P`、`isSupportSoftAP`、`default5gChannel`、`isSupportHevc`、`workModes`、`screenInfo(w_h_dpi)`、`carBrMac`、`sdkVersion`
- **鉴权**：`AuthRequest`（`authPk` / `agreementPk` / `random=Na` / `id` / `model` / **`userConfirmed`** / `productName`）、`AuthResponse`（`Nb` / `result` / `workModes` / `dayOrNightMode`）、`AuthConfirm`（会话密钥加密的手机本地 IP）
- **证书**：`CarCertificate { bytes content }`、`CertIndex`
- **投屏控制**：`NotifyMirrorState`（STOP_MIRROR / DISCONNECT_LINK / GO_TO_DESKTOP / BACK_TO_FOREGROUND）、`GetPortRequest{ SENSOR/AUDIO/UIBC }`
- **手机侧状态**：`NotifyPhoneState`（含 `is_screen_locked`、`is_wechat_qq_call`、`is_low_power`、`is_overheat`）、`NotifyMusicInfo`、`NotifyNavigationInfo`（`directionIcon`/`distance`/`operation`/`where`/`title1`/`title2`）
- **车机按键**：`CustomKeyEvent.KeyCode` —— `KEY_CODE_MAIN(0x1FA7)`、`TEL(0x1FA8)`、`NAVI(0x1FA9)`、`MEDIA(0x1FAA)`、`VR_START(0x1FAB)`、`VR_STOP(0x1FAC)`、`NAVI_QUIT(0x1FB1)`、`NEXT_FOCUS/PRE_FOCUS/IN_FOCUS/OUT_FOCUS(0x1FB2–0x1FB5)`、`MEDIA_FAVOR(0x1FB6)`
- **应用生态**（融合桌面）：`GetAppList`、`GetAppDetailInfo`、`InvokeApp`、`AppListChanged`、`AppStateChanged`，`AppCategory` 18 类

---

## 4. SDK 的公开 API（移植入口）

`com.ucar.vehiclesdk.UCarAdapter` 是单例门面，**接口干净得像一块积木**：

```java
// 生命周期
public boolean init(Context ctx, UCarConfig config, ICarInitCallback cb);
public void    deInit();
public boolean startAdvertise();                 // 开始广播，等手机来连
public boolean startAdvertise(String arg);
public boolean stopAdvertise();
public boolean enableUsbDeviceDetection(boolean enable);   // USB 插入自动识别
public boolean disconnect();

// 投屏
public boolean startCast(Surface surface, int w, int h);
public boolean startCast(Surface surface, int w, int h, IAppSurfaceStateListener l);
public boolean pauseCast();
public boolean pauseCast(boolean b);

// 融合桌面应用
public boolean startApp(String phoneId, int appId);
public boolean pauseApp(String phoneId, int appId);
public boolean resumeApp(String phoneId, int appId);
public boolean stopApp(String phoneId, int appId);
public boolean changeAppRotation(String phoneId, int angle);
public ArrayList getAppList(String phoneId);
public ArrayList getAppDetailInfo(String phoneId, List ids);

// 输入回传
public boolean sendTouchEvent(int action, int pointerId, int[] x, int[] y, int[] ids);
public boolean sendKeyEvent(KeyEventActionType action, KeyCodeType key, int meta);
public boolean sendVRCMD(VRCmdType cmd, String source);

// 车机数据上行
public boolean sendGPSInfo(GPSInfo)      ;  public boolean sendGearStateInfo(GearStateInfo);
public boolean sendLightsInfo(LightsInfo);  public boolean sendGyroscopeInfo(GyroscopeInfo);
public boolean sendAccelerationInfo(AccelerationInfo); public boolean sendOilInfo(OilInfo);
public boolean sendLightSensorInfo(LightSensorInfo);   public boolean sendBatteryInfo(BatteryInfo);

// 事件回调注册
public void registerCarConnectListener(ICarConnectListener l);
public void registerPhoneDataListener(IPhoneDataListener l);
public void registerAudioRecorderListener(ICarAudioRecorderListener l);
public void registerCameraInfoListener(ICameraInfoListener l);

// 通话/日夜
public boolean notifyPickUpCall();  public boolean notifyHungUpCall();
public boolean notifySwitchDayOrNight(DayNightMode mode);
```

### 4.1 `UCarConfig` 的配置项（车机集成商可调的全部旋钮）

```java
new UCarConfig.Builder()
    .setCarBrMac(byte[])              // 车机蓝牙 MAC（车机身份）
    .setDpi(int) / .setRealDpi(int)   // demo 默认 320
    .setFps(int)                      // demo 默认 60
    .setScreenWidth(int) / .setScreenHeight(int)          // 车机物理屏
    .setVideoDisplayWidth(int) / .setVideoDisplayHeight(int) // 投屏目标区域
    .setSupportP2P(true)              // WiFi Direct
    .setSupportSoftAP(boolean)        // 车机做热点
    .setSupportCamera(boolean)        // 摄像头回传（行车记录/倒车）
    .setUseCustomAudioRecord(boolean) // 自定义录音（demo 里是 false，另有 CarlinkCustomAudioRecord 实现）
    .setSupportStereoRecord(true)
    .setSupportLowLatencyDecodingMode(false)
    .setSupportPOINav(boolean)        // 导航 POI 下发
    .setSupportHevc(boolean)          // H.265（手机侧也要支持）
    .setAllowed5gChannels(int[])      // 5G 信道白名单
    .setSupportedWorkModes(WorkMode)  // NormalCast + AppFullScreenCast + AppWindowCast
    .setCcdFilePath(String)           // ★ 换成自己的车辆证书（见 §6）
    .setWifiSsid(String) / .setWifiPassword(String)  // 自定义 AP 热点
    .build();
UCarAdapter.getInstance().init(appContext, config, callback);
```

**这套 API 意味着**：车机集成方只负责"给一个 Surface + 一个 Context + 一份配置"，剩下发现、配对、鉴权、收流、解码、输入回传、音频路由全在 SDK 内。`startCast(Surface, w, h)` 一句就是投屏。

---

## 5. 内置证书 `assets/ccd.data`

文件是 **base64 包一层 JSON**：

```
外层：{ "content": [<147 字节数组>], "cryptoConfigIndex": "o-5T4plzjMmcilZs",
        "signAlgorithm": "SHA256withRSA", "signature": <256 字节>, "version": 1 }
           ↓ bytes(content) 解 UTF-8
内层：{ "short_name":   "测0试1车2机3",
        "product_name": "测0试1车2机3 2022款",
        "vendor_name":  "测0试1车2厂3",
        "vendor_id":    "0001",
        "product_id":   "0001" }
```

> 名字里夹着 `0/1/2/3` 是行业常见的**泄漏溯源水印**（同一份包发给不同客户时改名，拿到流出包就能定位来源）。这也侧面说明：**这份证书是给开发者/测试用的，不是量产车的证书。**

厂商/产品 ID 都是 `0001`，签名算法 `SHA256withRSA`，签名 256 字节（RSA-2048）。

---

## 6. 证书在 SDK 里到底被怎么用（**本节是本次分析最关键的发现**）

`com.share.connect.utils.CarVerifier` 只做两件事：**读文件、交给解析器**。

```java
// 反编译自 CarVerifier.getCcdReader()
if (mCcdFilePath.equals("ccd.data"))
    return getDefaultReader();       // 读 assets/ccd.data
else
    return getReaderByFilePath();    // 读任意路径（可自定义）

// CarVerifier.getDataReader() → getCcdReader()
// CarVerifier.readCarData()   → new CarVerificationData.Builder().dataReader(reader).read()
// CarVerifier.getCarData()    → new CarVerificationData.Builder().dataReader(reader).build()
```

`CarVerificationData` 的校验逻辑**只有这么点**：

```java
// 反编译自 CarVerificationData.isValid()
return mContent != null && mContent.length > 0
    && mSignature != null && mSignature.length > 0
    && !TextUtils.isEmpty(mCryptoConfigIndex)
    && mVersion > 0;                 // 仅要求字段非空 / 正数
```

**没有 RSA 验签，没有 CA 链校验，没有 vendor 白名单，没有任何在线校验。**

解析出来的字段也只是当作展示信息：

```java
// 反编译自 CarVerificationData.getCarDevice() —— 纯 gson 字段搬运
Device d = new Device()
    .setShortName(json["short_name"])
    .setVid(json["vendor_id"])
    .setVName(json["vendor_name"])
    .setPid(json["product_id"])
    .setPName(json["product_name"]);
```

然后原样打包发给手机：

```java
// UCarCertProtocol.createCarCertificateMessage(CarCertificate)
//   → UCarMessageBuilder.setMethodIndex(1).buildProtobufMessage(cert)
//   通道类别 CmdCategory.CERT
```

**推论**：车机侧的证书本质上是**"自我介绍卡片"**，不是安全凭证。真正决定"手机认不认"的是**手机端**；而手机端接受这份「测试车机 / vendor 0001」的证书，说明**手机端也走的是用户确认（PIN 码 + 弹窗）路径，而不是 CA 信任链**。

> **诚实边界**：**手机端（CarWith）的校验逻辑不在这个 APK 里，我无法静态证明它"完全不校验"**。我能静态证明的是：(a) 车机 SDK 侧零校验；(b) 这份自签测试证书在真机上是**被接受的**（用户实测）。这两条合起来足以支撑"未认证设备可用"的工程结论，但不等于"手机永远不做任何校验"——未来 CarWith 若收紧策略，仍可能影响连接。

---

## 7. 为什么装到车机后小米手机能直接识别

按证据重建的完整链路：

```
① 车机装好 App → 启动 → UCarAdapter.init(config) → startAdvertise()
② 车机 BLE 开始广播（GATT Server）
     Service UUID 2abcc850-9935-4f8a-ba84-123456789100（+101/102 特征值）
③ 车机同时建 WiFi Direct 组，SSID = DIRECT-ICCOA-XXXX
     （或按 config.setSupportSoftAP(true) 走车机热点）
④ 手机开蓝牙 → CarWith 扫到该 BLE 广播 → 识别为「ICCOA CarLink 设备」
⑤ 手机弹窗 → 用户点「连接」→ 输入车机屏上的确认码（UCarPinCodeView / setPinCode）
     ★ 这一步就是 AuthRequest.userConfirmed 的来源
⑥ ECDH 协商会话密钥 + ECDSA 身份签名 + HMAC 校验 → 确立加密通道
     信任设备写入 Room 表 Peer(id, auth_key, connection_time) → 下次自动重连
⑦ 车机下发 ccd.data（自我介绍：车名/厂商/产品）→ 手机显示"已连接 xxx 车机"
⑧ 手机起 Miracast（Wi-Fi Display）会话
     车机 libovmsink.so 作为 Sink：RTSP 协商 → RTP 收流 → H.264 解码
     → 渲染到 App 提供的 Surface（startCast(surface, w, h)）
⑨ 触控/按键反向回传：UIBC → UibdManager → 手机
⑩ 车机持续上报挡位/车速/GPS/油量 → 手机判断行驶状态，限制不安全操作
```

**所以「能直接识别」的原因不是认证，而是**：
1. **BLE 广播 + `DIRECT-ICCOA-` 热点名**是 CarWith 的发现特征，不校验设备证书；
2. **配对走用户确认 + PIN 码**，不依赖联盟签发的身份；
3. 证书在 SDK 里只当展示信息用。

---

## 8. 这个包的工程质量与风险点

| # | 问题 | 证据 | 影响 |
|---|---|---|---|
| **R1** | **debug 签名 + `debuggable=true`** | 签名证书 = AOSP 公开调试密钥（SHA-1 `61ed377e…`）；manifest `android:debuggable="true"` | 任何人可改包重签并静默安装；`debuggable` 打开意味着 `run-as`/调试器可注入。**不适合作为量产依赖** |
| **R2** | **声明了一堆非系统应用拿不到的私有权限** | `BLUETOOTH_PRIVILEGED`、`NETWORK_STACK`、`TETHER_PRIVILEGED`、`MANAGE_USB`、`OVERRIDE_WIFI_CONFIG`、`NETWORK_SETTINGS`、`LOCAL_MAC_ADDRESS`、`WRITE_SETTINGS` | 这些是 `signature\|privileged` 级；普通安装会被静默拒绝。原设计是**预装成系统应用**（吉利的车机就是这样） |
| **R3** | **只有 `arm64-v8a`** | `lib/` 下只有一个 ABI | 32 位车机装不了 |
| **R4** | **第三方换皮 + 水印证书** | `applicationId` 与类包名不一致；证书名夹 `0/1/2/3` 水印 | 法律上属于「使用他人 SDK 与测试凭据」，非官方授权集成 |
| **R5** | **无开源、无许可证信息** | 全包无 LICENSE、无 Apache/BSD 声明 | 拿它的代码/so 二次分发有法律风险 |
| **R6** | 该 SDK 是**联盟凭据** | ICCOA 官方文档：要做产品仍需过认证 | 自己做出来给别人用，仍有合规问题 |

> **一句话**：这个包的**技术路线是真实可行的**，但**这个具体的包不能当产品用**——它是给别人做 POC 和验证"能不能连上"的。

---

## 9. 能否移植？—— 三条路线

### 路线 A：直接复用它（最快，风险最高）

装现成的 ICCOA CarLink 车机端 demo（公开分发的 `com.ucarhu.demo` v1.2.10 / v2.0.6 / v1.5.3），手机就能连。

- ✅ 零开发
- ❌ 不是 Car++，没有 BYD 仪表/HUD 集成，UI 是 demo UI
- ❌ R1/R2/R4 全部成立

**适用**：只想验证"我的车机能不能连 CarWith"。

### 路线 B：**提取 SDK 融入 Car++**（推荐）

把 `com.ucar.*` + `com.share.connect.*` + `libovmsink.so` + `libusbio.so` + `assets/ccd.data` 从 APK 里取出，作为 Car++ 的"协议前端"，替换 早期内部版本 的 `aap/*`：

```
Car++ = 早期内部版本 的"后半身"（解码/BYD 仪表/HUD/Settings/UI）
       + ICCOA SDK 的"前半身"（发现/配对/鉴权/收流）
       + 自写的一层适配：SDK 回调 → 早期内部版本 数据模型
```

适配层要写的就这么几个点：

| SDK 侧 | 接到 Car++ 的 | 工作量 |
|---|---|---|
| `startCast(Surface, w, h)` | 现有 `VideoDecoder` 的 Surface（或直接用 SDK 自带收流，只借 早期内部版本 的 Surface/View 层） | 小 |
| `registerPhoneDataListener` → `NavigationInfo` | `AapNavigation` / `cluster/` 的 BYD 仪表与 HUD 承载 | 中（这是 Car++ 的核心价值） |
| `registerCarConnectListener` | 现有连接状态 UI | 小 |
| `sendGPSInfo/sendGearStateInfo/...` | 车机侧数据采集（BYD 有 CAN 数据源最好） | 中 |
| `sendTouchEvent` / `sendKeyEvent` | 方向盘按键（复用 STC800 那套按键思路！） | 小 |
| `sendMicRecordData` | 现有 `MicRecorder` | 小 |

**关键优势**：SDK 的 `startCast` 只依赖一个 `Surface`，**天然与 早期内部版本 的渲染层解耦**；`NavigationInfo` 结构（方向图标 + 距离 + 操作 + 路名）与 早期内部版本 现有 AA 导航信号语义接近，**BYD 仪表/HUD 那条链路可以几乎原样复用**。

### 路线 C：自己实现 ICCOA 协议（最长远，最难）

依据 `ucar.proto`（658 行，明文）+ 已破解的密钥协商算法（ECDH + ECDSA + HMAC，全在 Java 层，可读）+ Miracast 公开规范，从零实现。

- ✅ 干净、可控、无法律包袱
- ⚠️ 工作量大：协议 1.0/1.5 → 3.0 的版本差异、手机端黑盒行为（弹窗时机、能力协商）、Miracast 兼容性
- ⚠️ 仍需自己解决证书（虽然 §6 说明它不校验，但手机端策略不可控）

---

## 10. 修正后的 Car++ 建议

| 阶段 | 内容 | 目的 |
|---|---|---|
| **POC-0** | 装官方 ICCOA demo 到车机 → 用你的小米手机连一次 | **先确认"这台车机 + 这台手机"能连**（成本 10 分钟） |
| **POC-1** | 单独写一个最小 App：只调 `UCarAdapter.init()` + `startAdvertise()` + `startCast(一个纯色 Surface)` | 验证 SDK 能否脱离 demo UI 独立跑；这是路线 B 的可行性证明 |
| **POC-2** | 在 POC-1 上接 `registerPhoneDataListener` → 把 `NavigationInfo` 送进 早期内部版本 的 `AapNavigation`/`cluster/` | 验证 **BYD 仪表/HUD 复用**——这才是 Car++ 的差异化价值 |
| **P1** | 有线 USB（AOA）打通 | 最稳，先做 |
| **P2** | 无线：车机热点 / WiFi Direct | |
| **P3** | 融合桌面（`AppWindowCast` 小窗 + 多任务） | |
| **P4** | 方向盘按键（`sendKeyEvent`）+ 语音（`sendVRCMD`） | 可复用 STC800 项目积累 |
| **P5** | 自研证书 + 合规梳理 | 若要对外发布 |

**优先级排序的理由**：POC-2 才是决定"Car++ 值得不值得做"的那一步——如果 BYD 仪表/HUD 能复用，Car++ 就比"装个 demo"强得多；如果不能，Car++ 的意义会大幅缩水。

---

## 11. 附录：关键文件/类索引

| 关注点 | 位置 |
|---|---|
| 协议定义（明文） | APK 根 `ucar.proto`（658 行）、`google/protobuf/*.proto` |
| 车辆证书 | `assets/ccd.data` |
| 原生库 | `lib/arm64-v8a/libovmsink.so`（Miracast sink）、`libusbio.so`（USB AOA） |
| SDK 门面 | `com.ucar.vehiclesdk.UCarAdapter`、`UCarConfig`、`UCarCommon.WorkMode` |
| 连接框架 | `com.share.connect.ShareLinkService` / `ShareLinkObserver` / `IShareLinkManager` / `LinkParameters` |
| BLE | `com.share.connect.ble.BluetoothLeService` / `BleAdvertisementBuilder` / `BleUtils` |
| WiFi Direct | `com.share.connect.wifip2p.WifiP2pService` / `WifiP2pManagerProxy` / `WifiUtils` |
| 车机热点 | `com.share.connect.wifiap.WifiApService` / `WifiApConfigHelper` |
| 鉴权 | `com.share.connect.security.UCarAuthService` / `KeyNegotiator` / `ServerKeyNegotiator` / `Peer` / `PeerDatabase` |
| 证书 | `com.share.connect.utils.CarVerifier` / `CarVerificationData` / `CarVerifier.DEFAULT_CCD_FILE` |
| USB AOA | `com.ucar.connect.aoa.AOAConnectManager` / `AOAHostSetup` / `UsbNative` |
| 协议封装 | `com.ucar.protocol.UCarProtocol` / `UCarAuthProtocol` / `UCarCertProtocol` / `MessageHeader` / `channel.ChannelType` |
| protobuf 生成物 | `com.ucar.databus.proto.UCarProto$*` |
| demo UI | `com.ucarhu.demo.UCarDemoActivity` / `app.*Fragment` / `widget.UCarPinCodeView` / `app.container.UCarContainer*Activity` |
| 车机蓝牙对接手机 | `com.ucarhu.demo.CarBt.CarBtConnector`（A2DP/HFP profile 管理，`MANUFACTURERS_XIAOMI/OPPO/VIVO`） |

---

## 12. 复现方法

```bash
# 1. 解包
unzip -o "智慧车联_v1.5.3-支持方控.apk" -d zhl/

# 2. 看协议定义（明文）
less zhl/ucar.proto

# 3. 解证书
python -c "
import base64,json
j=json.loads(base64.b64decode(open('zhl/assets/ccd.data','rb').read()).decode())
print(json.dumps({k:v for k,v in j.items() if k!='content'},ensure_ascii=False,indent=2))
print(json.loads(bytes(x&0xFF for x in j['content']).decode()))
"

# 4. manifest / 签名
#    androguard: APK(path).get_package() / get_certificates()
# 5. 反编译：jadx（本样本 14MB 多 dex，jadx 极慢；用 androguard 取字节码更快）
```

---

## 13. 本次逆向的产物已归档

分析过程中提取的素材已存放在 `repos/Car++/reference/`（**只读参考，勿直接分发**）：

| 路径 | 内容 |
|---|---|
| `reference/ucar.proto` | ICCOA 协议定义，658 行明文 |
| `reference/test_pb_data.proto` | 官方测试 protobuf 样例 |
| `reference/assets/ccd.data` | 车机证书（带溯源水印，**分发前必须替换**） |
| `reference/dumps/_api.txt` | `UCarAdapter` / `UCarConfig` 全部方法签名（948 行） |
| `reference/dumps/_bc.txt` | 证书校验路径字节码 dump（`CarVerificationData.isValid()`） |
| `reference/dumps/_bc2.txt` | SDK 初始化路径字节码 dump |
| `reference/dumps/_classes.txt` | DEX 类清单 |
| `reference/dumps/_const.txt` | `<clinit>` 常量 dump（部分） |

> 说明见 [`../reference/README.md`](../reference/README.md)。

---

*分析日期：2026-10-02 · 样本 SHA-256 `645127cb0c75ba769aa06f7ce457f1ee6b03d19f03af956545ef0398ce9e0d7e` · 方法：静态逆向*
