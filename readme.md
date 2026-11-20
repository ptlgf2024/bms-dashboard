# 嘉佰达 BMS 助手

[![Platform](https://img.shields.io/badge/platform-Android%206%2B-green.svg?style=flat-square)](https://www.android.com/)
[![Min SDK](https://img.shields.io/badge/minSDK-24-orange.svg?style=flat-square)](https://developer.android.com/)
[![Build](https://img.shields.io/badge/build-manual%20toolchain-blue.svg?style=flat-square)](#-构建)
[![License: MIT](https://img.shields.io/badge/license-MIT-blue.svg?style=flat-square)](LICENSE)

一个通过蓝牙 BLE **只读**读取嘉佰达（Jiabaida / JBD）软件板保护板数据的 Android 开源工具。提供图形化仪表盘、完整的参数与保护信息、本地历史记录、保护记录和桌面小部件。

> **免责声明**：本项目为个人学习与实用工具，与嘉佰达科技及任何电池品牌官方无关。仅实现公开协议中的只读命令，不会修改保护板的任何参数。

---

## 目录

- [功能特性](#-功能特性)
- [支持的设备](#-支持的设备)
- [快速开始](#-快速开始)
- [界面](#-界面)
- [数据说明](#-数据说明)
- [项目结构](#-项目结构)
- [构建](#-构建)
- [常见问题](#-常见问题)
- [许可证](#-许可证)

---

##  功能特性

### 实时仪表盘
- 半圆电压表盘与 SOC 环形电量环，嘉佰达蓝色主题
- 电压、电流、功率、SOC、容量、循环次数、压差、温度等数据卡
- **24 串单体电压电池柱**：按电压比例填充，标注最高（▲）与最低（▼）串
- V12 扩展字段：剩余/满充容量、湿度、报警状态、均衡电流、限流/加热/MOS 状态
- 三路 NTC 温度与当前保护状态

### 参数与信息
- 按嘉佰达官方命名规则解析设备型号（系列 / 串数 / 电芯类型 / 电流 / 功能码）
- 厂商、BMS 型号、硬件版本、BMS 编码、主控芯片等身份信息
- 保护参数与额定值按 8 类分组展示：容量信息、电压保护、电流保护、温度保护、均衡与硬件、保护延时、SOC 电压点、额定参数
- 单体内阻（0xF6），按实际串数三列展示
- 保护记录：设备历史累计保护次数（0xAA）+ 本机运行时监测事件

### 其他
- 本地历史记录：CSV 存储（最多 2000 条）与 SOC/电压趋势图
- 报警阈值：压差、温度、单体电压、SOC，超阈值高亮与振动
- 自动重连、60 秒轮询、冷启动从缓存恢复读数
- 4×2 桌面小部件，JobScheduler 后台刷新

---

##  支持的设备

| 项目 | 说明 |
|---|---|
| 保护板 | 嘉佰达（JBD）软件板，兼容通用协议 V4 / V12 的只读命令 |
| 实测电池 | 24 串磷酸铁锂，标称 50Ah |
| 厂商标识 | `DGJBD`，BMS 型号 `DB24SF01 V1.0` |
| 设备名 | 以 `DB` 开头，例如 `DB24SA03L24S60ABU` |
| BLE 服务 | `FF00`，通知特征 `FF01`，写入特征 `FF02` |

**已实现的只读命令：**

| 命令 | 内容 |
|---|---|
| `0x03` | 实时数据（电压、电流、SOC、容量、温度、保护状态等） |
| `0x04` | 24 串单体电压 |
| `0x05` | 硬件版本 / 设备名 |
| `0x00` | 主控芯片类型 |
| `0xAA` | 12 类保护累计次数 |
| `0xF6` | 单体内阻 |
| `0xFA` | 参数寄存器 0~183 |

> 不实现任何写操作：蓝牙密码、设备改名、MOS 控制、参数修改等均未实现。

---

##  快速开始

### 安装

从 [Releases](../../releases) 下载 APK 直接安装（Android 6.0 / API 24 及以上），需要蓝牙与定位权限。

### 使用

1. 首次打开会弹出扫描框，选择 `DB` 开头的设备
2. 连接后自动读取实时数据与全部只读参数（约 15 秒）
3. 之后每 60 秒轮询一次；断开后界面保留最后一次读数
4. 桌面长按空白处 → 小部件 → 搜索「BMS 助手」添加 4×2 卡片

---

##  界面

应用包含三个页面与一个桌面小部件：

| 页面 | 内容 |
|---|---|
| 实时 | 电压表盘、电量环、数据卡、24 串电池柱、保护状态与 V12 扩展字段 |
| 参数 | 报警阈值配置、蓝牙过滤开关、清除已记住设备 |
| 信息 | 设备身份、型号解析、分组参数、保护记录、内阻、本地历史 |

---

##  数据说明

- **数据来源**：全部通过 BLE 只读命令获取，缓存于本机 `SharedPreferences`（`widget_cache`），冷启动可恢复。
- **历史记录**：每次读数写入本机 `bms_history.csv`，最多 2000 条。
- **保护记录**：设备侧仅提供累计次数（0xAA）；带时间戳/串号/电压的事件明细，公开协议不提供读取命令，因此应用只记录自身运行时监测到的状态变化。
- **兼容性**：不同保护板固件对 V12 参数的支持程度不同，部分寄存器可能返回 0 或空值。

---

##  项目结构

```
bms-dashboard/
├── AndroidManifest.xml
├── build.ps1                      # 手动工具链构建脚本
├── readme.md
├── LICENSE
├── release.keystore               # 签名密钥（本地保留，不提交到仓库）
├── res/
│   ├── layout/                    # 主界面、扫描对话框、小部件布局
│   ├── drawable/                  # 卡片、图标、小部件图形
│   ├── values/                    # 颜色、样式
│   └── xml/                       # 桌面小部件配置
├── src/com/example/bmsdash/
│   ├── MainActivity.java          # UI 与交互
│   ├── BleManager.java            # 扫描/连接/命令发送/帧重组
│   ├── BmsParser.java             # 0x03/0x04 帧解析
│   ├── BmsData.java               # 数据快照与帧合并
│   ├── CellBatteryView.java       # 单串电池柱图标
│   ├── HistoryStore.java          # 历史 CSV
│   ├── ProtectionStore.java       # 保护事件 CSV
│   ├── ChartView.java             # 趋势图
│   ├── GaugeView.java             # 电压表盘
│   ├── BatteryArcView.java        # 电量环
│   ├── DeviceAdapter.java         # 设备列表
│   ├── LeDevice.java              # 设备模型
│   ├── WidgetProvider.java        # 桌面小部件
│   ├── WidgetDrawables.java       # 小部件图形
│   └── WidgetRefreshJobService.java # 小部件后台刷新
└── tools/                         # 图标生成等辅助脚本
```

---

##  构建

本项目**不使用 Gradle**，采用手动工具链（`build.ps1`）：

```
aapt2 compile → aapt2 link → javac -source 8 -target 8
→ jar → d8 (build-tools 36) → zipalign → apksigner
```

**准备：**
- Android SDK：build-tools `34.0.0` 与 `36.0.0`、platform `android-34`
- JDK（脚本在 JDK 25 下验证通过）
- 签名密钥 `release.keystore`（覆盖升级必须使用同一把）

**构建：**

```powershell
$env:BMS_KS_PASS = '你的签名密码'
.\build.ps1
```

产物：`build/apk/bms-dashboard-1.0.apk`

> 说明：
> - 签名密码从环境变量 `BMS_KS_PASS` 读取，不写入脚本；别名可用 `BMS_KS_ALIAS` 覆盖（默认 `silentmonitor`）。
> - aapt2 不接受中文路径，脚本会复制到纯 ASCII 目录（如 `C:\bmsdash_build`）构建后拷回。
> - JDK 25 下 d8 必须使用 build-tools 36.0.0（34.0.0 的 d8 处理匿名内部类会崩溃）。

---

##  常见问题

**Q: 扫描不到设备？**
确认保护板已通电、手机蓝牙与定位权限已开启。默认只显示名称以 `DB` 开头的设备。

**Q: 冷启动为什么能看到上次数据？**
App 与桌面小部件共享 `widget_cache` 缓存，冷启动会恢复最后一次读数，避免短暂显示 `--`。

**Q: 能读到官方 App 里的保护历史明细吗？**
不能。公开协议只有累计次数（0xAA），没有带时间戳/串号/电压的事件明细命令。

**Q: 数据多久更新一次？**
连接后即时读取，之后每 60 秒轮询。桌面小部件通过 JobScheduler 后台刷新。

---

##  许可证

[MIT License](LICENSE) — 本项目仅供学习与研究使用，使用本项目带来的任何风险由使用者自行承担。
