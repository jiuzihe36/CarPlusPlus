# `:sdk-iccoa` — ICCOA CarLink 协议插件

让车机接住 **小米 CarWith / OPPO Car+ 车联 / vivo Jovi InCar**（它们都走 ICCOA CarLink）。

它只是 `:sdk-api` 里 `PhoneLink` 的一种实现；`:carpp-sdk` 完全不认识它，宿主 App 在
`Application.onCreate` 里 `PhoneLinkRegistry.install(IccoaProvider())` 一下就接上了。
想换协议，把这个模块从依赖里去掉、换成自己的实现即可。

## 目录

| 文件 | 作用 |
|---|---|
| `src/main/java/com/carpp/sdk/connection/IcocaBridge.kt` | 桥接层：把上游 SDK 的 worker 线程回调折叠成 `StateFlow<LinkState>`，并把 `UCarCommon.NavigationInfo` 映射成协议无关的 `NavInfo` |
| `src/main/java/com/carpp/app/IccoaManager.java` | 对上游 SDK 的门面（init / 广播 / 断开 / 刷新配对码 / 投屏 Surface） |
| `src/main/java/com/carpp/app/IccoaProvider.kt` | `PhoneLinkProvider` 实现，注册入口 |
| `src/main/java/com/carpp/sdk/icoca/IcocaProjectionActivity.kt` | 投影界面，持有 SDK 投屏用的 Surface |
| `stubs/icar-sdk-stubs.jar` | 纯签名存根，**compileOnly**，不进产物。由 `tools/sdk-stubs/make-stubs.sh` 从真 jar 生成 —— **换 SDK 版本时必须重跑** |

## 协议本体：闭源 jar，不在仓库里

`icar-sdk.jar` 是第三方预编译字节码，既不在仓库里分发，也不在本项目 AGPL-3.0 许可范围内
（`.gitignore` 挡掉 `app/libs/*.jar`）。所以它分两半引入：

| 半 | 文件 | 怎么用 |
|---|---|---|
| 编译期 | `stubs/icar-sdk-stubs.jar` | 只提供签名，让本模块能编译；`tools/sdk-stubs/make-stubs.sh` 从真 jar 生成 |
| 运行期 | `app/libs/icar-sdk.jar` | `:app` 里 `implementation(files("libs/icar-sdk.jar"))`，由 AGP 正常 d8 / multidex / 对齐 / 签名 |

**没有这个 jar 时工程照样能编译、能装、能开界面，只是连不上手机** —— 这是刻意设计的降级形态。

拿到 jar 后放进 `app/libs/icar-sdk.jar`，正常构建即可。

### 换 SDK 版本的两步

1. `tools/sdk-stubs/make-stubs.sh app/libs/icar-sdk.jar` —— 重生成签名存根
   （新版本新增的类 / 方法不在旧存根里，不重跑会直接编译失败）；
2. 原生库跟着换：`libovmsink.so` / `libusbio.so` 由 SDK 内部 `SinkNative` / `UsbNative` 各自
   `System.loadLibrary` 加载，**jar 与 so 必须同批**，否则 JNI 注册的类名对不上。

## 移植时必须一起搬的三样

只搬 dex **不等于** SDK 可用。真机「手机完全扫不到车机、界面无配对码、点任何按钮都没反应」的根因
就是只搬了 dex：

| 组成 | 漏掉时的症状 | 本模块的对应位置 |
|---|---|---|
| `<service>` 组件 | 系统起不了 SDK 的 Service → **静默失效，零报错** | `src/main/AndroidManifest.xml` 里的 `com.share.connect.ShareLinkService` / `.ble.BluetoothLeService` / `.wifiap.WifiApService` / `.wifip2p.WifiP2pService` + `androidx.room.MultiInstanceInvalidationService` |
| `<uses-permission>` | 权限不足 → API 静默失败 | 同 manifest：`BLUETOOTH_ADVERTISE` / `LOCAL_MAC_ADDRESS` / `NETWORK_SETTINGS` / `NETWORK_STACK` / `TETHER_PRIVILEGED` / `OVERRIDE_WIFI_CONFIG` / `QUERY_ALL_PACKAGES` / `MANAGE_USB` |
| Gradle 传递依赖 | `NoClassDefFoundError` | `androidx.room:room-runtime:2.4.3`（SDK 用 Room 存信任设备表，dex 里 room **只有引用没有定义**） |

还有两条：

- **启动必须等运行时权限**：API 31+ 没拿到 `BLUETOOTH_ADVERTISE` / `BLUETOOTH_CONNECT` 时
  `startAdvertise()` 会静默失败。
- **包根不能改**：`com.share.connect` / `com.easy` / `com.ucar` / `com.ucarsink` 写死在 SDK 字节码
  和跨进程组件名里，改名或搬位置会静默断链。`proguard-rules.pro` 已 keep 住，R8 下也别动。

## 配对码为什么必须「停-启重播」

手机关掉配对弹窗后不会再自己弹：车机「一直在广播」不等于「手机能重新发现」，广播帧内容不变时
手机端不重新弹窗。所以刷新配对码是 `stopAdvertise()` → `startAdvertise()`，而不是重复 `startAdvertise()`
（后者会被 SDK 直接忽略）。30 秒看门狗与首页刷新按钮走同一条路径，会话进行中或画面已投出时跳过。
