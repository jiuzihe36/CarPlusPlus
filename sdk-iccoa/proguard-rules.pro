# ICCOA CarLink 协议插件的裁剪规则（由 consumerProguardFiles 带给宿主 App）。
#
# com.share.connect.* / com.ucar.* / com.easy.* / com.ucarsink.* 都在运行时注入的
# icar-sdk.dex 里：R8 看不到它们的定义，任何裁剪或改名都会让链路在运行时断掉且无报错。
# 这些包名根也绝对不能改（SDK 自己的字节码里写死了包名与跨进程组件名）。
-keep class com.share.connect.** { *; }
-keep class com.ucar.** { *; }
-keep class com.easy.** { *; }
-keep class com.ucarsink.** { *; }

# Car++ 自己的门面与桥接：门面由 dex 里的 SDK 通过反射/接口回调驱动。
-keep class com.carpp.app.** { *; }
-keep class com.carpp.sdk.connection.IcocaBridge { *; }
-keep class com.carpp.sdk.icoca.IcocaProjectionActivity { *; }

# 编译期只有存根，R8 在宿主 App 上跑时会遇到这些缺定义的引用。
-dontwarn com.share.connect.**
-dontwarn com.ucar.**
-dontwarn com.easy.**
-dontwarn com.ucarsink.**
