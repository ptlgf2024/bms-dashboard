#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
超威芯界 BMS 蓝牙连接与数据读取示例（Python）

依赖：bleak（跨平台 BLE 库，支持 Windows / Linux / macOS）
    pip install bleak

用法：
    python ble_read_example.py                    # 扫描 DB 开头的设备并连接
    python ble_read_example.py XX:XX:XX:XX:XX:XX  # 直接连接指定 MAC（替换为你的设备地址）

功能：扫描过滤 -> 连接 -> 订阅 FF01 通知 -> 发送 0x03/0x04 命令
      -> 分帧重组 -> 解析电压/电流/SOC/温度/24串单体电压
"""

import asyncio
import struct
import sys
from datetime import datetime

try:
    from bleak import BleakClient, BleakScanner
    from bleak.backends.characteristic import BleakGATTCharacteristic
except ImportError:
    print("缺少依赖，请先运行: pip install bleak")
    sys.exit(1)

# ---------- UUID ----------
SERVICE_UUID = "0000ff00-0000-1000-8000-00805f9b34fb"
NOTIFY_UUID  = "0000ff01-0000-1000-8000-00805f9b34fb"   # BMS -> 手机
WRITE_UUID   = "0000ff02-0000-1000-8000-00805f9b34fb"   # 手机 -> BMS

POLL_INTERVAL = 60  # 秒

CMD_REALTIME = 0x03
CMD_CELLS    = 0x04
CMD_NAME     = 0x05


def log(msg: str):
    print(f"[{datetime.now().strftime('%H:%M:%S')}] {msg}", flush=True)


def build_command(cmd: int) -> bytes:
    """构造命令帧 DD A5 CMD 00 CHK_H CHK_L 77，CHK = 0x10000 - CMD"""
    chk = 0x10000 - cmd
    return bytes([0xDD, 0xA5, cmd, 0x00, (chk >> 8) & 0xFF, chk & 0xFF, 0x77])


class FrameAssembler:
    """通知分帧重组器：按 DD 帧头 + 长度字段重组，处理噪声与粘包"""

    def __init__(self, on_frame):
        self.buf = bytearray()
        self.on_frame = on_frame

    def feed(self, chunk: bytes):
        for b in chunk:
            if not self.buf and b != 0xDD:      # 丢弃非帧头噪声
                continue
            self.buf.append(b)
            if len(self.buf) >= 4:
                data_len = (self.buf[2] << 8) | self.buf[3]
                need = 4 + data_len + 3        # 帧头4 + 数据 + 校验2 + 帧尾1
                if need <= 1024 and len(self.buf) >= need:
                    frame = bytes(self.buf[:need])
                    self.on_frame(frame)
                    del self.buf[:need]        # 剩余字节继续处理（粘包）
            if len(self.buf) > 1024:           # 防溢出
                self.buf.clear()


def parse_realtime(d: bytes):
    """解析命令 03 实时数据帧"""
    if len(d) < 29:
        return
    voltage = struct.unpack_from(">H", d, 0)[0] / 100.0
    current = struct.unpack_from(">h", d, 2)[0] / 100.0   # 有符号，正充电/负放电
    soc = d[19]
    temps = [struct.unpack_from(">H", d, off)[0] / 10.0 - 273.15
             for off in (23, 25, 27)]
    state = "充电" if current > 0.005 else ("放电" if current < -0.005 else "静置")
    log(f"实时数据: 电压 {voltage:.2f} V, 电流 {current:+.2f} A ({state}), "
        f"SOC {soc}%, 温度 {min(temps):.1f}~{max(temps):.1f} ℃")


def parse_cells(d: bytes):
    """解析命令 04 的 24 串单体电压"""
    if len(d) < 48:
        return
    cells = [struct.unpack_from(">H", d, i * 2)[0] for i in range(24)]
    log(f"单体电压: {min(cells)}~{max(cells)} mV, 压差 {max(cells) - min(cells)} mV, "
        f"总压 {sum(cells) / 1000.0:.2f} V")
    log(f"  24串明细: {cells}")


def on_frame(frame: bytes):
    """完整帧回调"""
    if len(frame) < 7:
        return
    cmd = frame[1]
    data_len = (frame[2] << 8) | frame[3]
    d = frame[4:4 + min(data_len, len(frame) - 7)]
    log(f"收到帧 CMD={cmd:02X} 数据长度={len(d)}")
    if cmd == CMD_REALTIME:
        parse_realtime(d)
    elif cmd == CMD_CELLS:
        parse_cells(d)
    elif cmd == CMD_NAME:
        log(f"设备名: {d.decode('ascii', errors='ignore')}")


async def main():
    assembler = FrameAssembler(on_frame)

    # ---------- 1. 扫描或指定设备 ----------
    target = sys.argv[1] if len(sys.argv) > 1 else None
    if target:
        log(f"连接指定设备 {target}")
        device = await BleakScanner.find_device_by_address(target, timeout=10.0)
    else:
        log("扫描名称以 DB 开头的设备…")
        devices = await BleakScanner.discover(timeout=10.0)
        matches = [d for d in devices
                   if d.name and d.name.upper().startswith("DB")]
        for d in matches:
            log(f"发现设备: {d.name}  {d.address}")
        device = matches[0] if matches else None

    if device is None:
        log("未找到设备")
        return

    # ---------- 2. 连接并订阅 ----------
    async with BleakClient(device) as client:
        log(f"已连接 {device.address}")
        svc = client.services.get_service(SERVICE_UUID)
        if svc is None:
            log("未发现 FF00 服务，非目标设备")
            return
        log("设备指纹验证通过（FF00 服务）")

        def notify_handler(_ch: BleakGATTCharacteristic, data: bytearray):
            assembler.feed(bytes(data))

        # bleak 的 start_notify 会在各平台后端自动向 CCCD(0x2902) 写入 0x0001
        # 启用 Notify，等价于 Android 端手动 writeDescriptor，无需显式操作
        await client.start_notify(NOTIFY_UUID, notify_handler)
        log("已订阅 FF01 通知")

        # ---------- 3. 发送命令并轮询 ----------
        async def poll_once():
            await client.write_gatt_char(WRITE_UUID, build_command(CMD_REALTIME),
                                         response=False)
            log("发送命令 03")
            await asyncio.sleep(0.3)
            await client.write_gatt_char(WRITE_UUID, build_command(CMD_CELLS),
                                         response=False)
            log("发送命令 04")

        await asyncio.sleep(0.5)   # 等订阅生效
        while True:
            await poll_once()
            await asyncio.sleep(POLL_INTERVAL)


if __name__ == "__main__":
    try:
        asyncio.run(main())
    except KeyboardInterrupt:
        log("已退出")
