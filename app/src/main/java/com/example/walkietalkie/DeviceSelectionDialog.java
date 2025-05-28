package com.example.walkietalkie;

import android.app.AlertDialog;
import android.content.Context;
import android.hardware.usb.UsbDevice;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ArrayAdapter;
import android.widget.ListView;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.example.walkietalkie.intercom.UsbIntercomManager;

import java.util.List;

/**
 * USB设备选择对话框
 * 显示可用的USB设备列表，让用户选择要连接的设备
 */
public class DeviceSelectionDialog {
    
    public interface DeviceSelectionListener {
        void onDeviceSelected(UsbDevice device);
        void onDialogCancelled();
    }
    
    private final Context context;
    private AlertDialog dialog;
    private DeviceSelectionListener listener;
    
    public DeviceSelectionDialog(Context context) {
        this.context = context;
    }
    
    public void setDeviceSelectionListener(DeviceSelectionListener listener) {
        this.listener = listener;
    }
    
    /**
     * 显示设备选择对话框
     */
    public void show(List<UsbIntercomManager.UsbDeviceInfo> devices) {
        if (devices == null || devices.isEmpty()) {
            showNoDevicesFoundDialog();
            return;
        }
        
        // 创建ListView和适配器
        ListView listView = new ListView(context);
        DeviceAdapter adapter = new DeviceAdapter(context, devices);
        listView.setAdapter(adapter);
        
        // 构建对话框
        AlertDialog.Builder builder = new AlertDialog.Builder(context)
                .setTitle(R.string.dialog_title_select_device)
                .setView(listView)
                .setCancelable(true)
                .setNegativeButton(R.string.btn_cancel, (dialog, which) -> {
                    if (listener != null) {
                        listener.onDialogCancelled();
                    }
                });
        
        // 设置设备点击事件
        listView.setOnItemClickListener((parent, view, position, id) -> {
            UsbIntercomManager.UsbDeviceInfo deviceInfo = devices.get(position);
            
            // 检查设备是否支持串行通信
            if (!deviceInfo.isSupported()) {
                Toast.makeText(context, R.string.dialog_message_unsupported_device, Toast.LENGTH_LONG).show();
                
                // 询问用户是否仍要尝试连接不支持的设备
                new AlertDialog.Builder(context)
                        .setTitle(R.string.dialog_title_unsupported_device)
                        .setMessage(R.string.dialog_message_unsupported_device)
                        .setPositiveButton(R.string.btn_connect, (dlg, which) -> {
                            if (listener != null) {
                                listener.onDeviceSelected(deviceInfo.getDevice());
                            }
                            dialog.dismiss();
                        })
                        .setNegativeButton(R.string.btn_cancel, null)
                        .show();
            } else {
                if (listener != null) {
                    listener.onDeviceSelected(deviceInfo.getDevice());
                }
                dialog.dismiss();
            }
        });
        
        dialog = builder.create();
        dialog.show();
    }
    
    /**
     * 显示未找到设备的对话框
     */
    private void showNoDevicesFoundDialog() {
        new AlertDialog.Builder(context)
                .setTitle(R.string.dialog_title_no_devices)
                .setMessage(R.string.dialog_message_no_devices)
                .setPositiveButton(R.string.btn_ok, (dialog, which) -> {
                    if (listener != null) {
                        listener.onDialogCancelled();
                    }
                })
                .setCancelable(false)
                .show();
    }
    
    /**
     * 关闭对话框
     */
    public void dismiss() {
        if (dialog != null && dialog.isShowing()) {
            dialog.dismiss();
        }
    }
    
    /**
     * 设备列表适配器
     */
    private static class DeviceAdapter extends ArrayAdapter<UsbIntercomManager.UsbDeviceInfo> {
        
        public DeviceAdapter(Context context, List<UsbIntercomManager.UsbDeviceInfo> devices) {
            super(context, android.R.layout.simple_list_item_2, android.R.id.text1, devices);
        }
        
        @NonNull
        @Override
        public View getView(int position, @Nullable View convertView, @NonNull ViewGroup parent) {
            View view = super.getView(position, convertView, parent);
            
            UsbIntercomManager.UsbDeviceInfo deviceInfo = getItem(position);
            
            TextView text1 = view.findViewById(android.R.id.text1);
            TextView text2 = view.findViewById(android.R.id.text2);
            
            if (deviceInfo != null) {
                text1.setText(deviceInfo.getDescription());
                
                StringBuilder statusText = new StringBuilder();
                
                // 如果可能是对讲机，添加提示
                if (deviceInfo.isProbablyIntercom()) {
                    statusText.append(getContext().getString(R.string.device_possible_intercom));
                } else {
                    statusText.append(getContext().getString(R.string.device_generic_usb));
                }
                
                // 添加设备支持状态
                if (deviceInfo.isSupported()) {
                    statusText.append(" ").append(getContext().getString(R.string.device_supported));
                    text2.setTextColor(0xFF006400); // 深绿色
                } else {
                    statusText.append(" ").append(getContext().getString(R.string.device_not_supported));
                    text2.setTextColor(0xFFB22222); // 深红色
                }
                
                text2.setText(statusText.toString());
            }
            
            return view;
        }
    }
} 