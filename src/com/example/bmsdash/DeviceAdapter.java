package com.example.bmsdash;

import android.content.Context;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.BaseAdapter;
import android.widget.TextView;

import java.util.List;

public class DeviceAdapter extends BaseAdapter {
    private final Context ctx;
    private final List<LeDevice> data;
    public DeviceAdapter(Context c, List<LeDevice> d) { ctx = c; data = d; }

    @Override public int getCount() { return data.size(); }
    @Override public Object getItem(int i) { return data.get(i); }
    @Override public long getItemId(int i) { return i; }

    @Override public View getView(int i, View v, ViewGroup parent) {
        ViewHolder h;
        if (v == null) {
            v = LayoutInflater.from(ctx).inflate(android.R.layout.simple_list_item_2, parent, false);
            h = new ViewHolder();
            h.n = (TextView) v.findViewById(android.R.id.text1);
            h.s = (TextView) v.findViewById(android.R.id.text2);
            v.setTag(h);
        } else h = (ViewHolder) v.getTag();
        LeDevice d = data.get(i);
        h.n.setTextSize(15);
        h.n.setTextColor(ctx.getResources().getColor(R.color.text_primary));
        h.s.setTextSize(11);
        h.s.setTextColor(ctx.getResources().getColor(R.color.text_secondary));
        h.n.setText(d.name);
        h.s.setText(d.address + "  " + d.rssi + " dBm");
        return v;
    }
    static class ViewHolder { TextView n, s; }
}
