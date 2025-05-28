package com.example.walkietalkie;

import android.app.AlertDialog;
import android.bluetooth.BluetoothDevice;
import android.content.Context;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ArrayAdapter;
import android.widget.ListView;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.example.walkietalkie.intercom.BleIntercomManager;

import java.util.List;

/**
 * BLE设备选择对话框
 * 显示可用的蓝牙设备列表，让用户选择要连接的设备
 */
public class BleDeviceSelectionDialog {
    
    public interface DeviceSelectionListener {
        void onDeviceSelected(BluetoothDevice device);
        void onDialogCancelled();
    }
    
    private final Context context;
    private AlertDialog dialog;
    private DeviceSelectionListener listener;
    
    public BleDeviceSelectionDialog(Context context) {
        this.context = context;
    }
    
    public void setDeviceSelectionListener(DeviceSelectionListener listener) {
        this.listener = listener;
    }
    
    /**
     * 显示设备选择对话框
     */
    public void show(List<BleIntercomManager.BleDeviceInfo> devices) {
        // 先关闭现有对话框
        dismiss();
        
        if (devices == null || devices.isEmpty()) {
            new AlertDialog.Builder(context)
                    .setTitle(R.string.dialog_title_device_selection)
                    .setMessage(R.string.dialog_message_no_devices_found)
                    .setPositiveButton(R.string.btn_ok, null)
                    .show();
            return;
        }
        
        // 使用builder模式创建对话框，更清晰并减少嵌套
        AlertDialog.Builder builder = new AlertDialog.Builder(context);
        View dialogView = LayoutInflater.from(context).inflate(R.layout.dialog_device_selection, null);
        ListView deviceListView = dialogView.findViewById(R.id.device_list);
        
        // 设置列表适配器
        DeviceAdapter adapter = new DeviceAdapter(context, devices);
        deviceListView.setAdapter(adapter);
        
        // 设置点击事件
        deviceListView.setOnItemClickListener((parent, view, position, id) -> {
            if (position >= 0 && position < devices.size()) {
                dismiss();
                
                if (listener != null) {
                    BleIntercomManager.BleDeviceInfo deviceInfo = devices.get(position);
                    listener.onDeviceSelected(deviceInfo.getDevice());
                }
            }
        });
        
        // 创建对话框
        builder.setTitle(R.string.dialog_title_ble_device_selection)
               .setView(dialogView)
               .setNegativeButton(R.string.btn_cancel, (dialog, which) -> {
                   if (listener != null) {
                       listener.onDialogCancelled();
                   }
               })
               .setCancelable(false);
        
        dialog = builder.create();
        dialog.show();
    }
    
    /**
     * 关闭对话框
     */
    public void dismiss() {
        if (dialog != null) {
            dialog.dismiss();
            dialog = null;
        }
    }
    
    /**
     * 设备列表适配器
     */
    private static class DeviceAdapter extends ArrayAdapter<BleIntercomManager.BleDeviceInfo> {
        
        public DeviceAdapter(Context context, List<BleIntercomManager.BleDeviceInfo> devices) {
            super(context, 0, devices);
        }
        
        @NonNull
        @Override
        public View getView(int position, @Nullable View convertView, @NonNull ViewGroup parent) {
            if (convertView == null) {
                convertView = LayoutInflater.from(getContext()).inflate(R.layout.item_device, parent, false);
            }
            
            BleIntercomManager.BleDeviceInfo deviceInfo = getItem(position);
            if (deviceInfo != null) {
                TextView nameTextView = convertView.findViewById(R.id.device_name);
                TextView addressTextView = convertView.findViewById(R.id.device_address);
                
                // 设置设备名称和地址
                nameTextView.setText(deviceInfo.getName());
                addressTextView.setText(deviceInfo.getAddress());
                
                // 如果可能是对讲机，高亮显示
                if (deviceInfo.isProbablyIntercom()) {
                    nameTextView.setTextColor(getContext().getResources().getColor(android.R.color.holo_blue_dark));
                    convertView.setBackgroundColor(getContext().getResources().getColor(android.R.color.holo_blue_light, null));
                } else {
                    nameTextView.setTextColor(getContext().getResources().getColor(android.R.color.black));
                    convertView.setBackgroundColor(getContext().getResources().getColor(android.R.color.white, null));
                }
            }
            
            return convertView;
        }
    }
} 