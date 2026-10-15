# app/libs — 不进仓库的运行时依赖

## `icar-sdk.jar` — ICCOA CarLink 协议 SDK

ICCOA CarLink 是小米 CarWith、OPPO Car+ 车联、vivo Jovi InCar 共用的手机侧协议。
**这个文件刻意不进仓库**：它是第三方的闭源预编译字节码，既不随本仓库分发，
也不在本项目的 AGPL-3.0 许可范围内。`.gitignore` 用 `libs/*.jar` 把它挡在外面。

### 怎么接进去的

Gradle/AGP **不认 `.dex` 作为依赖**（`implementation` / `runtimeOnly` 都不会把 dex 打进 APK），
所以 SDK 分成两半：

| 半边 | 文件 | 用法 |
| --- | --- | --- |
| 编译期 | [`sdk-iccoa/stubs/icar-sdk-stubs.jar`](../../sdk-iccoa/stubs) | **已提交**。纯签名存根，`compileOnly`，永不进 APK。 |
| 运行期 | `app/libs/icar-sdk.jar` | 标准 `implementation(files("libs/icar-sdk.jar"))`，由 AGP 正常 d8 编译、multidex 打包、对齐、签名。 |

> 早期版本走的是「把 dex 追加成 `classesN.dex` + zipalign + apksigner 重签」的注入路线。
> 那条路已经删掉：手工往 APK 里塞 dex 很容易漏写 ZIP 条目的 size/CRC，ART 校验时会报
> `Bad file size` → SDK 类加载失败 → 界面退化成「投屏组件未就绪」，且栈帧/映射/增量编译全都不正常。
> 现在 SDK 就是一个普通 jar 依赖，出问题按普通 Java 依赖排查即可。

### 怎么生成这个 jar

SDK 的原始形态是头机里的 `icar-sdk.dex`（dex 版本 035）。用 dex2jar 转成标准字节码 jar：

```bash
d2j-dex2jar.sh -o app/libs/icar-sdk.jar app/libs/icar-sdk.dex
```

**转换前先验 dex 是否完好**（这一步不能省）：

```python
import struct, zlib, hashlib
d = open("app/libs/icar-sdk.dex", "rb").read()
assert struct.unpack("<I", d[40:44])[0] == 0x12345678      # endian_tag
assert struct.unpack("<I", d[32:36])[0] == len(d)          # file_size
assert zlib.adler32(d[12:]) & 0xffffffff == struct.unpack("<I", d[8:12])[0]
assert hashlib.sha1(d[32:]).digest() == d[12:32]
```

> 被手工改过的 dex（例如直接替换字符串常量却没重算头部）会让 dex2jar 报
> `Endian_tag unsupported` —— 那是**输入文件坏了**，不是工具不支持该版本。头部四个字段
> （file_size / header_size / endian_tag / checksum+signature）任一错位都会这样。

### 车机侧显示名改成 Car++

手机端 CarWith 弹窗里的车名来自 SDK 内部常量 `com/share/connect/Device` 的
`UNKNOWN_CAR`（原始值是 `MiAutoCar`）。`UCarConfig` **没有**设置车名的 API，只能在 jar 里改：
改 `.class` 常量池的 UTF8 条目 `01 00 09 "MiAutoCar"` → `01 00 05 "Car++"` 即可
（class 文件全是指针索引，改长度安全；改完用 `javap -p -c` 验一遍）。

### 必须一起搬的三样东西

只把 jar 放进来**不等于** SDK 可用 —— 真机上「手机完全扫不到车机 / 界面无配对码」的经典症状
就是只搬了字节码。另外三份必须一起在：

| 组成 | 漏掉的症状 | 位置 |
| --- | --- | --- |
| `<service>` 组件 | 系统起不了 SDK 的 Service → **静默失效，零报错** | `app/src/main/AndroidManifest.xml`：`com.share.connect.ShareLinkService` / `.ble.BluetoothLeService` / `.wifiap.WifiApService` / `.wifip2p.WifiP2pService` + `androidx.room.MultiInstanceInvalidationService` |
| `<uses-permission>` | 权限不足 → API 静默失败 | 同上：`TETHER_PRIVILEGED` / `OVERRIDE_WIFI_CONFIG` / `NETWORK_SETTINGS` / `NETWORK_STACK` / `LOCAL_MAC_ADDRESS` / `MANAGE_USB` 等 |
| Gradle 传递依赖 | `NoClassDefFoundError` | `androidx.room:room-runtime:2.4.3`（SDK 用 Room 存信任设备表，而它自身 `androidx/room/*` 定义数为 0，只有引用） |

### 打包时要剥掉的第三方类

这个 jar 里**自带**了一份 protobuf（javalite）、error_prone_annotations 和 guava 的
`ListenableFuture`。而 `:carpp-sdk` 自己依赖 **protobuf-java**（Android Auto 协议的生成代码
需要完整版 runtime）。两者同占 `com.google.protobuf` 包 → 直接 `Duplicate class` 构建失败。

因此放进 `app/libs/` 的 jar 必须先把这三组剥掉，交给 Maven 提供：

```
com/google/protobuf/**                ← 355 个类
com/google/errorprone/**              ← 22 个类
com/google/common/util/concurrent/ListenableFuture.class
```

剥完后 jar 里剩约 4357 个类（SDK 自身 `com/ucar` + `com/share` 约 800 个，其余是它内联的
Guava / Tink / gson）。老版本 lite 生成代码跑在新版 protobuf-java runtime 上属于官方支持的
向后兼容方向（反之不行）。

### 不要改 SDK 的包名

SDK 的跨进程组件名和自身字节码里都烧死了包名。动 `com.share.connect`、`com.easy`、
`com.ucar`、`com.ucarsink` 任何一个根，都会**静默**断掉会话且没有任何报错。
