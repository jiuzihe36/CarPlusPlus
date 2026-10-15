# 从闭源 SDK 生成签名存根

`:sdk-iccoa` 的源码要引用 `com.ucar.vehiclesdk.*`，而闭源 SDK 的真 jar 不进仓库
（`app/libs/icar-sdk.jar` 在 `.gitignore` 里，由使用者自己放）。中间用一层**签名存根**过渡：

| 阶段 | 用什么 | 说明 |
|---|---|---|
| 编译期 | `sdk-iccoa/stubs/icar-sdk-stubs.jar` | 只提供签名，`compileOnly`，不进产物 |
| 运行期 | `app/libs/icar-sdk.jar` | 真 jar，`implementation`，由 AGP 正常 d8 进 APK |

没有真 jar 时工程照样能编译、能装、能开界面，只是连不上手机 —— 这是刻意的。

## 什么时候要重跑

**换 SDK 版本时必须重跑**。存根是按真 jar 生成的，新版本新增的类 / 方法（例如 2.0.24 才有的
`UCarNotification`、`UCarAdapter.removeNotification`、`IPhoneDataListener.onNotificationAdded`）
在旧存根里不存在，会直接编译失败。

## 怎么跑

```bash
tools/sdk-stubs/make-stubs.sh app/libs/icar-sdk.jar
# 或指定输出：tools/sdk-stubs/make-stubs.sh <真 jar> <输出 jar>
```

脚本会自己下 ASM（`~/项目/资料/toolchain/asm/`），需要 JDK 17。

## 它做了什么

`Stubber.java` 用 ASM 遍历真 jar 里 `com/ucar/` `com/share/` `com/ucarsink/` 三个包的每个类：

- **保留**：类名、访问标志、继承 / 实现关系、字段（含 `static final` 常量 —— 宿主代码会引用
  `UCarConnectState.ERROR_*` 这类常量）、方法签名与异常表；
- **抽空**：方法体换成最小合法实现（`void` → `return`，数值 → `0`，对象 → `null`），
  同时丢掉注解与调试信息；
- **跳过**：非 SDK 包（`androidx/` `com/google/` `kotlin/` …）—— 那些由宿主用 Gradle 提供。

所以存根**能编译但不能运行**，这是设计意图：仓库里不分发闭源字节码。
