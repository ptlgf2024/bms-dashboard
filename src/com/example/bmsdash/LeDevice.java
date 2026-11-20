package com.example.bmsdash;

import android.bluetooth.BluetoothDevice;

public class LeDevice {
    public final BluetoothDevice device;
    public final String name;
    public final String address;
    public int rssi;

    public LeDevice(BluetoothDevice d, String name, int rssi) {
        this.device = d;
        this.address = d.getAddress();
        String n = name;
        if (n == null || n.trim().isEmpty()) {
            try { n = d.getName(); } catch (Exception e) { n = null; }
        }
        this.name = (n == null || n.trim().isEmpty()) ? "(未命名)" : n;
        this.rssi = rssi;
    }
}
