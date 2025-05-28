package com.example.walkietalkie.intercom;

import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.hardware.usb.UsbConstants;
import android.hardware.usb.UsbDevice;
import android.hardware.usb.UsbDeviceConnection;
import android.hardware.usb.UsbEndpoint;
import android.hardware.usb.UsbInterface;
import android.hardware.usb.UsbManager;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import com.hoho.android.usbserial.driver.UsbSerialDriver;
import com.hoho.android.usbserial.driver.UsbSerialPort;
import com.hoho.android.usbserial.driver.UsbSerialProber;

import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * USB对讲机管理器
 * 负责USB设备的连接、通信和控制
 */
public class UsbIntercomManager implements BaseIntercomManager {
    private static final String TAG = "UsbIntercomManager";
    private static final String ACTION_USB_PERMISSION = "com.example.walkietalkie.USB_PERMISSION";
    
    private final Context context;
    private final UsbManager usbManager;
    private UsbDevice currentDevice;
    private UsbSerialPort usbSerialPort;
    private boolean connected = false;
    private final ExecutorService executor = Executors.newSingleThreadExecutor();
    private final ExecutorService readExecutor = Executors.newSingleThreadExecutor();
    
    // 回调监听器
    private final List<ConnectionListener> connectionListeners = new ArrayList<>();
    private final List<IntercomResponseListener> responseListeners = new ArrayList<>();
    private final List<DeviceSelectionListener> deviceSelectionListeners = new ArrayList<>();
    private final List<AudioDataListener> audioDataListeners = new ArrayList<>();
    
    // 读取线程控制
    private boolean isReading = false;
    
    /**
     * 构造函数
     * @param context 上下文
     */
    public UsbIntercomManager(Context context) {
        this.context = context;
        this.usbManager = (UsbManager) context.getSystemService(Context.USB_SERVICE);
    }
    
    /**
     * 扫描并连接USB设备
     */
    public void scanAndConnect() {
        if (usbManager == null) {
            notifyError("USB服务不可用");
            return;
        }
        
        // 获取所有USB设备
        List<UsbDeviceInfo> deviceList = new ArrayList<>();
        for (UsbDevice device : usbManager.getDeviceList().values()) {
            UsbDeviceInfo info = new UsbDeviceInfo(device);
            deviceList.add(info);
        }
        
        // 通知找到设备
        if (!deviceList.isEmpty()) {
            for (DeviceSelectionListener listener : deviceSelectionListeners) {
                listener.onDevicesFound(deviceList);
            }
        } else {
            notifyError("未找到USB设备");
        }
    }
    
    /**
     * 连接选中的设备
     * @param device USB设备
     */
    public void connectSelectedDevice(UsbDevice device) {
        if (device == null) {
            notifyError("设备为空");
            return;
        }
        
        // 如果已经连接了设备，先断开
        if (connected) {
            disconnect();
        }
        
        // 请求USB权限
        if (!usbManager.hasPermission(device)) {
            PendingIntent permissionIntent = PendingIntent.getBroadcast(
                    context, 0, new Intent(ACTION_USB_PERMISSION), PendingIntent.FLAG_IMMUTABLE);
            usbManager.requestPermission(device, permissionIntent);
            return;
        }
        
        // 建立连接
        executor.execute(() -> {
            try {
                // 查找USB驱动
                List<UsbSerialDriver> availableDrivers = UsbSerialProber.getDefaultProber().findAllDrivers(usbManager);
                if (availableDrivers.isEmpty()) {
                    notifyError("找不到USB驱动");
                    return;
                }
                
                // 查找匹配的驱动
                UsbSerialDriver driver = null;
                for (UsbSerialDriver availableDriver : availableDrivers) {
                    if (availableDriver.getDevice().equals(device)) {
                        driver = availableDriver;
                        break;
                    }
                }
                
                if (driver == null) {
                    notifyError("设备不支持串行通信");
                    return;
                }
                
                // 打开连接
                UsbDeviceConnection connection = usbManager.openDevice(device);
                if (connection == null) {
                    notifyError("无法打开设备连接");
                    return;
                }
                
                // 获取串行端口
                usbSerialPort = driver.getPorts().get(0);
                usbSerialPort.open(connection);
                usbSerialPort.setParameters(9600, 8, UsbSerialPort.STOPBITS_1, UsbSerialPort.PARITY_NONE);
                
                // 连接成功
                currentDevice = device;
                connected = true;
                
                // 开始读取数据
                startReading();
                
                // 通知连接成功
                for (ConnectionListener listener : connectionListeners) {
                    listener.onUsbConnected(device);
                }
                
            } catch (IOException e) {
                notifyError("连接设备失败: " + e.getMessage());
                disconnect();
            }
        });
    }
    
    /**
     * 断开连接
     */
    public void disconnect() {
        executor.execute(() -> {
            isReading = false;
            
            if (usbSerialPort != null) {
                try {
                    usbSerialPort.close();
                } catch (IOException e) {
                    Log.e(TAG, "关闭端口失败", e);
                }
                usbSerialPort = null;
            }
            
            connected = false;
            currentDevice = null;
            
            // 通知断开连接
            for (ConnectionListener listener : connectionListeners) {
                listener.onUsbDisconnected();
            }
        });
    }
    
    /**
     * 开始读取数据线程
     */
    private void startReading() {
        isReading = true;
        readExecutor.execute(() -> {
            byte[] buffer = new byte[1024];
            while (isReading && connected && usbSerialPort != null) {
                try {
                    int len = usbSerialPort.read(buffer, 1000);
                    if (len > 0) {
                        // 复制接收到的数据
                        byte[] received = new byte[len];
                        System.arraycopy(buffer, 0, received, 0, len);
                        
                        // 解析响应
                        processResponse(received);
                    }
                } catch (IOException e) {
                    if (isReading) {
                        Log.e(TAG, "读取数据失败", e);
                        notifyError("读取数据失败: " + e.getMessage());
                        disconnect();
                        break;
                    }
                }
            }
        });
    }
    
    /**
     * 处理接收到的响应
     * @param data 接收到的数据
     */
    private void processResponse(byte[] data) {
        // 判断响应类型
        if (data.length == 0) {
            return;
        }
        
        // 检查是否是音频数据
        if (data[0] == IntercomCommand.CMD_RECEIVE_AUDIO_DATA) {
            // 如果是音频数据，解析并通知监听器
            byte[] audioData = IntercomCommand.parseReceivedAudioData(data);
            notifyAudioDataReceived(audioData);
        } else {
            // 其他类型的响应
            IntercomResponse response = IntercomResponse.fromRawData(data);
            notifyResponseReceived(response);
        }
    }
    
    /**
     * 发送命令
     * @param command 命令数据
     */
    private void sendCommand(byte[] command) {
        if (!connected || usbSerialPort == null) {
            notifyError("USB未连接，无法发送命令");
            return;
        }
        
        executor.execute(() -> {
            try {
                usbSerialPort.write(command, 1000);
            } catch (IOException e) {
                Log.e(TAG, "发送命令失败", e);
                notifyError("发送命令失败: " + e.getMessage());
            }
        });
    }

    /**
     * 发送音频数据到对讲机
     * @param audioData 音频数据
     */
    public void sendAudioData(byte[] audioData) {
        if (!connected || usbSerialPort == null) {
            notifyError("USB未连接，无法发送音频数据");
            return;
        }
        
        // 使用专用命令类型包装音频数据
        byte[] command = IntercomCommand.sendAudioData(audioData);
        
        // 使用现有的发送命令方法
        sendCommand(command);
    }
    
    /**
     * 设置音量
     * @param volume 音量值(0-10)
     */
    public void setVolume(int volume) {
        // 实现设置音量的命令
        byte[] command = new byte[] { 0x20, (byte)volume };
        sendCommand(command);
    }
    
    /**
     * 设置静噪
     * @param squelch 静噪值(0-10)
     */
    public void setSquelch(int squelch) {
        // 实现设置静噪的命令
        byte[] command = new byte[] { 0x30, (byte)squelch };
        sendCommand(command);
    }
    
    /**
     * 按下PTT
     */
    public void pttPress() {
        // 实现按下PTT的命令
        byte[] command = new byte[] { (byte)0x80, (byte)0x01 };
        sendCommand(command);
    }
    
    /**
     * 释放PTT
     */
    public void pttRelease() {
        // 实现释放PTT的命令
        byte[] command = new byte[] { (byte)0x80, (byte)0x00 };
        sendCommand(command);
    }
    
    /**
     * 设置扫描模式
     * @param mode 模式(0-停止，1-开始)
     */
    public void setScanMode(int mode) {
        // 实现设置扫描模式的命令
        byte[] command = new byte[] { 0x70, (byte)mode };
        sendCommand(command);
    }
    
    /**
     * 获取频率
     */
    public void getFrequency() {
        // 实现获取频率的命令
        byte[] command = new byte[] { 0x10 };
        sendCommand(command);
    }
    
    /**
     * 获取音量
     */
    public void getVolume() {
        // 实现获取音量的命令
        byte[] command = new byte[] { 0x20 };
        sendCommand(command);
    }
    
    /**
     * 获取静噪
     */
    public void getSquelch() {
        // 实现获取静噪的命令
        byte[] command = new byte[] { 0x30 };
        sendCommand(command);
    }
    
    /**
     * 获取信道
     */
    public void getChannel() {
        // 实现获取信道的命令
        byte[] command = new byte[] { 0x40 };
        sendCommand(command);
    }
    
    /**
     * 获取信号强度
     */
    public void getRssi() {
        // 实现获取信号强度的命令
        byte[] command = new byte[] { 0x50 };
        sendCommand(command);
    }
    
    /**
     * 获取电池电量
     */
    public void getBattery() {
        // 实现获取电池电量的命令
        byte[] command = new byte[] { 0x60 };
        sendCommand(command);
    }
    
    /**
     * 获取当前连接状态
     * @return 是否已连接
     */
    public boolean isConnected() {
        return connected;
    }
    
    /**
     * 获取当前连接的USB设备
     * @return USB设备，如未连接则返回null
     */
    public UsbDevice getConnectedDevice() {
        return currentDevice;
    }
    
    /**
     * 添加连接状态监听器
     */
    public void addConnectionListener(ConnectionListener listener) {
        if (listener != null && !connectionListeners.contains(listener)) {
            connectionListeners.add(listener);
        }
    }
    
    /**
     * 移除连接状态监听器
     */
    public void removeConnectionListener(ConnectionListener listener) {
        connectionListeners.remove(listener);
    }
    
    /**
     * 添加响应监听器
     */
    public void addResponseListener(IntercomResponseListener listener) {
        if (listener != null && !responseListeners.contains(listener)) {
            responseListeners.add(listener);
        }
    }
    
    /**
     * 移除响应监听器
     */
    public void removeResponseListener(IntercomResponseListener listener) {
        responseListeners.remove(listener);
    }
    
    /**
     * 添加设备选择监听器
     */
    public void addDeviceSelectionListener(DeviceSelectionListener listener) {
        if (listener != null && !deviceSelectionListeners.contains(listener)) {
            deviceSelectionListeners.add(listener);
        }
    }
    
    /**
     * 移除设备选择监听器
     */
    public void removeDeviceSelectionListener(DeviceSelectionListener listener) {
        deviceSelectionListeners.remove(listener);
    }
    
    /**
     * 添加音频数据监听器
     */
    @Override
    public void addAudioDataListener(AudioDataListener listener) {
        if (listener != null && !audioDataListeners.contains(listener)) {
            audioDataListeners.add(listener);
        }
    }
    
    /**
     * 移除音频数据监听器
     */
    @Override
    public void removeAudioDataListener(AudioDataListener listener) {
        audioDataListeners.remove(listener);
    }
    
    /**
     * 通知错误
     */
    private void notifyError(String error) {
        Log.e(TAG, error);
        for (ConnectionListener listener : connectionListeners) {
            listener.onUsbError(error);
        }
    }
    
    /**
     * 通知接收到响应
     */
    private void notifyResponseReceived(IntercomResponse response) {
        for (IntercomResponseListener listener : responseListeners) {
            listener.onResponseReceived(response);
        }
    }
    
    /**
     * 通知接收到音频数据
     */
    private void notifyAudioDataReceived(byte[] audioData) {
        for (AudioDataListener listener : audioDataListeners) {
            listener.onAudioDataReceived(audioData);
        }
    }
    
    /**
     * 释放资源
     */
    @Override
    public void release() {
        disconnect();
        executor.shutdown();
        readExecutor.shutdown();
    }
    
    /**
     * 连接状态监听器接口
     */
    public interface ConnectionListener {
        void onUsbConnected(UsbDevice device);
        void onUsbDisconnected();
        void onUsbPermissionDenied();
        void onUsbError(String error);
    }
    
    /**
     * 响应监听器接口
     */
    public interface IntercomResponseListener {
        void onResponseReceived(IntercomResponse response);
    }
    
    /**
     * 设备选择监听器接口
     */
    public interface DeviceSelectionListener {
        void onDevicesFound(List<UsbDeviceInfo> devices);
    }
    
    /**
     * USB设备信息类
     */
    public static class UsbDeviceInfo {
        private final UsbDevice device;
        private final String name;
        private final String description;
        private final boolean supported;
        
        public UsbDeviceInfo(UsbDevice device) {
            this.device = device;
            this.name = device.getDeviceName();
            this.description = device.getProductName() != null ? 
                    device.getProductName() : "未知设备";
            
            // 检查是否为支持的设备（简化判断，实际应该根据VID/PID判断）
            List<UsbSerialDriver> drivers = UsbSerialProber.getDefaultProber().findAllDrivers(
                    (UsbManager) null);
            boolean found = false;
            for (UsbSerialDriver driver : drivers) {
                if (driver.getDevice().equals(device)) {
                    found = true;
                    break;
                }
            }
            this.supported = found;
        }
        
        public UsbDevice getDevice() {
            return device;
        }
        
        public String getName() {
            return name;
        }
        
        public String getDescription() {
            return description;
        }
        
        public boolean isSupported() {
            return supported;
        }
        
        /**
         * 判断设备是否可能是对讲机
         * 根据设备的VendorID和ProductID简单判断
         * 实际项目中应该维护一个已知对讲机设备的列表
         */
        public boolean isProbablyIntercom() {
            // 这里简单地基于设备描述判断，实际应用中应该基于VID/PID判断
            // 常见的对讲机USB芯片厂商包括: Prolific, FTDI, Silicon Labs 等
            int vendorId = device.getVendorId();
            int productId = device.getProductId();
            
            // 常见的USB串口芯片VID
            boolean isCommonUsbSerialChip = (
                vendorId == 0x067B || // Prolific
                vendorId == 0x0403 || // FTDI
                vendorId == 0x10C4 || // Silicon Labs
                vendorId == 0x0483    // STMicroelectronics
            );
            
            // 简单判断是否为通信类设备
            boolean isCommDevice = device.getDeviceClass() == 2; // USB通信设备类
            
            // 如果描述中包含特定关键词，可能是对讲机
            String desc = description.toLowerCase();
            boolean hasKeyword = (
                desc.contains("radio") || 
                desc.contains("intercom") || 
                desc.contains("transceiver") ||
                desc.contains("walkie") || 
                desc.contains("talkie") ||
                desc.contains("usb") && desc.contains("serial")
            );
            
            return isCommonUsbSerialChip || isCommDevice || hasKeyword;
        }
    }
} 