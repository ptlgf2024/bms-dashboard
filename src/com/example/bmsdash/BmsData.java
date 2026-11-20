package com.example.bmsdash;

/** 一帧 BMS 实时数据快照（字段参照嘉佰达软件板通用协议 V4 命令 03/04） */
public class BmsData {
    public long timestamp;      // 采集时间 ms
    public double voltage = 0;  // 总电压 V
    public double current = 0;  // 电流 A（正=充电，负=放电）
    public boolean hasCurrent = false; // 该帧是否携带电流字段（命令03=true，命令04=false）
    public boolean hasRealtime = false; // 是否解析过命令 03 实时帧
    public int soc = -1;        // SOC %（RSOC）
    public int cycles = -1;     // 循环次数
    public boolean balanceOn = false;   // 任意串处于均衡
    public boolean chargeMos = false;   // 充电 MOS 开
    public boolean dischargeMos = false;// 放电 MOS 开
    public int protectStatus = 0;       // 保护状态位（协议注 1）
    public double tempMos = 0, temp1 = 0, temp2 = 0; // 三路温度 ℃
    public double tempMin = 0, tempMax = 0;          // 温度范围 ℃
    public int[] cells = new int[24];        // 单体电压 mV
    public int cellCount = 0;                // 实际串数
    public int cellMin = 0, cellMax = 0, diff = 0; // 最低/最高单体、压差 mV

    public double power() { return voltage * current; } // 功率 W（正充电/负放电）

    public void computeCells() {
        int min = Integer.MAX_VALUE, max = 0, count = 0;
        for (int v : cells) {
            if (v <= 0) continue;
            count++;
            if (v < min) min = v;
            if (v > max) max = v;
        }
        cellCount = count;
        if (min == Integer.MAX_VALUE) min = 0;
        cellMin = min; cellMax = max; diff = max - min;
    }

    /** 合并另一帧的数据（03 实时帧 / 04 单体帧分开发，需合并） */
    public void merge(BmsData other) {
        if (other.voltage != 0) this.voltage = other.voltage;
        if (other.hasCurrent) { this.current = other.current; this.hasCurrent = true; }
        if (other.soc >= 0) this.soc = other.soc;
        if (other.hasRealtime) {
            this.hasRealtime = true;
            this.cycles = other.cycles;
            this.balanceOn = other.balanceOn;
            this.chargeMos = other.chargeMos;
            this.dischargeMos = other.dischargeMos;
            this.protectStatus = other.protectStatus;
            this.tempMos = other.tempMos;
            this.temp1 = other.temp1;
            this.temp2 = other.temp2;
        }
        if (other.tempMax != 0) { this.tempMin = other.tempMin; this.tempMax = other.tempMax; }
        boolean hasCells = false;
        for (int v : other.cells) if (v > 0) { hasCells = true; break; }
        if (hasCells) { this.cells = other.cells.clone(); computeCells(); }
        this.timestamp = System.currentTimeMillis();
    }
}
