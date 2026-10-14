# 声明

## 一、项目性质

Car++ 是**独立的社区项目**，不是任何手机厂商或车机厂商的官方软件。

本项目与小米、OPPO、vivo、ICCOA、Google、比亚迪等主体**均无隶属、授权、背书或合作关系**；
文中提及的产品名、公司名与商标归各自所有者所有，仅用于说明兼容性。

## 二、许可与版权

本仓库以 **AGPL-3.0** 许可发布（见 [`LICENSE`](LICENSE)）。

其中**包含继承而来的 GPLv3 代码**，其原作者的版权声明与许可条款见
[`COPYRIGHT_MICHAEL_REID_GPLv3AFFERO.txt`](COPYRIGHT_MICHAEL_REID_GPLv3AFFERO.txt)，
该文件是本项目不可分割的一部分，**不得删除**。

据此，任何二次分发或以本 SDK（`:carpp-sdk` / `:sdk-api` / `:sdk-iccoa`）构建衍生作品的行为，
包括以网络服务形式提供，均须：

1. 完整保留上述许可与版权声明；
2. 向接收者提供相应的完整源码（AGPL-3.0 第 13 条）；
3. 对修改之处作出显著标注。

## 三、第三方组件

- **ICCOA CarLink 车机端协议实现**：第三方闭源字节码（`icar-sdk.dex`，约 7 MB），
  **不在本仓库分发**，也不在本项目许可范围内。运行期需要自行获取并注入，
  方法见 [`sdk-iccoa/README.md`](sdk-iccoa/README.md)。
  缺少该组件时，本软件可以编译、安装并运行界面，但无法与手机建立连接。
- 其余开源依赖及其许可见 [`docs/THIRD_PARTY.md`](docs/THIRD_PARTY.md)。

## 四、免责

本软件按「现状」提供，不含任何明示或默示的担保。
与行车相关的功能（投屏、仪表显示、HUD 输出）**请在停车状态下配置与测试**，
因使用本软件造成的任何损失，开发者不承担责任。

---

# Notice

## 1. Nature of the project

Car++ is an **independent community project**. It is not official software of any phone or
head-unit manufacturer, and is **not affiliated with, authorised by or endorsed by** Xiaomi,
OPPO, vivo, ICCOA, Google or BYD. Product and company names are trademarks of their respective
owners and are used only to describe compatibility.

## 2. Licence and copyright

This repository is released under **AGPL-3.0** ([`LICENSE`](LICENSE)).

It **incorporates inherited GPLv3 code**; the original author's copyright notice and licence
terms are kept in [`COPYRIGHT_MICHAEL_REID_GPLv3AFFERO.txt`](COPYRIGHT_MICHAEL_REID_GPLv3AFFERO.txt),
which is an integral part of this project and **must not be removed**.

Consequently, any redistribution, or any derivative work built on this SDK
(`:carpp-sdk` / `:sdk-api` / `:sdk-iccoa`) — including providing it as a network service — must:

1. retain the above licence and copyright notices in full;
2. offer the corresponding complete source to recipients (AGPL-3.0 §13);
3. state prominently that the work has been modified.

## 3. Third-party components

- **ICCOA CarLink car-side protocol implementation**: third-party proprietary bytecode
  (`icar-sdk.dex`, ~7 MB). **Not distributed here** and not covered by this project's licence.
  You must obtain and inject it yourself; see [`sdk-iccoa/README.md`](sdk-iccoa/README.md).
  Without it the software still compiles, installs and runs its UI, but cannot connect to a phone.
- Other open-source dependencies and their licences: [`docs/THIRD_PARTY.md`](docs/THIRD_PARTY.md).

## 4. Disclaimer

Provided "as is", without warranty of any kind. Configure and test driving-related features
(projection, cluster display, HUD output) **while parked**. The developers accept no liability
for any loss arising from use of this software.
