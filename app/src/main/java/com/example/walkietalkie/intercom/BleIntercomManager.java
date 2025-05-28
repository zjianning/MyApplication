package com.example.walkietalkie.intercom;

import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothGatt;
import android.bluetooth.BluetoothGattCallback;
import android.bluetooth.BluetoothGattCharacteristic;
import android.bluetooth.BluetoothGattService;
import android.bluetooth.BluetoothManager;
import android.bluetooth.BluetoothProfile;
import android.bluetooth.le.BluetoothLeScanner;
import android.bluetooth.le.ScanCallback;
import android.bluetooth.le.ScanFilter;
import android.bluetooth.le.ScanResult;
import android.bluetooth.le.ScanSettings;
import android.content.Context;
import android.content.pm.PackageManager;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import androidx.core.app.ActivityCompat;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * 低功耗蓝牙对讲机管理器
 * 负责BLE设备的连接、通信和控制
 * 实现与MCU的串口透传通信
 */
public class BleIntercomManager implements BaseIntercomManager {
    private static final String TAG = "BleIntercomManager";
    
    // BLE服务和特征的UUID（需要根据实际设备调整）
    private static final UUID SERVICE_UUID = UUID.fromString("0000ffe0-0000-1000-8000-00805f9b34fb");
    private static final UUID TX_CHAR_UUID = UUID.fromString("0000ffe1-0000-1000-8000-00805f9b34fb");
    private static final UUID RX_CHAR_UUID = UUID.fromString("0000ffe1-0000-1000-8000-00805f9b34fb");
    
    private final Context context;
    private final BluetoothManager bluetoothManager;
    private final BluetoothAdapter bluetoothAdapter;
    private BluetoothLeScanner bluetoothLeScanner;
    private BluetoothGatt bluetoothGatt;
    private BluetoothGattCharacteristic txCharacteristic;
    private BluetoothGattCharacteristic rxCharacteristic;
    
    private boolean isConnected = false;
    private boolean isScanning = false;
    
    // 数据处理线程
    private final ExecutorService executor = Executors.newSingleThreadExecutor();
    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    
    // 回调监听器
    private final List<ConnectionListener> connectionListeners = new ArrayList<>();
    private final List<IntercomResponseListener> responseListeners = new ArrayList<>();
    private final List<DeviceSelectionListener> deviceSelectionListeners = new ArrayList<>();
    private final List<AudioDataListener> audioDataListeners = new ArrayList<>();

    /**
     * 构造函数
     * @param context 上下文
     */
    public BleIntercomManager(Context context) {
        this.context = context;
        this.bluetoothManager = (BluetoothManager) context.getSystemService(Context.BLUETOOTH_SERVICE);
        this.bluetoothAdapter = bluetoothManager.getAdapter();
    }
    
    /**
     * 检查设备是否支持BLE
     */
    public boolean isBleSupported() {
        return bluetoothAdapter != null && context.getPackageManager().hasSystemFeature(PackageManager.FEATURE_BLUETOOTH_LE);
    }
    
    /**
     * 检查蓝牙是否已启用
     */
    public boolean isBluetoothEnabled() {
        return bluetoothAdapter != null && bluetoothAdapter.isEnabled();
    }
    
    /**
     * 检查蓝牙权限并执行操作
     * @param operation 需要执行的蓝牙操作
     * @param errorMessage 权限错误时的消息
     * @return 是否有权限执行操作
     */
    private boolean checkBluetoothPermissionAndRun(Runnable operation, String errorMessage) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            if (ActivityCompat.checkSelfPermission(context, android.Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED ||
                ActivityCompat.checkSelfPermission(context, android.Manifest.permission.BLUETOOTH_SCAN) != PackageManager.PERMISSION_GRANTED) {
                notifyError(errorMessage);
                return false;
            }
        }
        
        if (operation != null) {
            operation.run();
        }
        return true;
    }
    
    /**
     * 开始扫描BLE设备
     */
    public void scanDevices() {
        if (!isBleSupported() || !isBluetoothEnabled()) {
            notifyError("蓝牙不可用或未启用");
            return;
        }
        
        if (isScanning) {
            return;
        }
        
        // 检查权限并执行扫描
        checkBluetoothPermissionAndRun(() -> {
            // 设置扫描过滤器和设置
            List<ScanFilter> filters = new ArrayList<>();
            ScanSettings settings = new ScanSettings.Builder()
                    .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
                    .build();
            
            bluetoothLeScanner = bluetoothAdapter.getBluetoothLeScanner();
            if (bluetoothLeScanner == null) {
                notifyError("蓝牙扫描器不可用");
                return;
            }
            
            // 开始扫描
            isScanning = true;
            executor.execute(() -> {
                bluetoothLeScanner.startScan(filters, settings, scanCallback);
                // 10秒后自动停止扫描
                mainHandler.postDelayed(this::stopScan, 10000);
            });
        }, "缺少蓝牙扫描权限");
    }
    
    /**
     * 停止扫描BLE设备
     */
    public void stopScan() {
        if (!isScanning || bluetoothLeScanner == null) {
            return;
        }
        
        isScanning = false;
        
        checkBluetoothPermissionAndRun(() -> {
            executor.execute(() -> {
                bluetoothLeScanner.stopScan(scanCallback);
            });
        }, "缺少蓝牙扫描权限");
    }
    
    /**
     * 扫描回调
     */
    private final ScanCallback scanCallback = new ScanCallback() {
        private final List<BleDeviceInfo> deviceList = new ArrayList<>();
        
        @Override
        public void onScanResult(int callbackType, ScanResult result) {
            BluetoothDevice device = result.getDevice();
            if (device != null && device.getName() != null) {
                // 过滤并添加设备
                BleDeviceInfo deviceInfo = new BleDeviceInfo(context, device);
                if (!deviceList.contains(deviceInfo)) {
                    deviceList.add(deviceInfo);
                    // 实时通知发现新设备
                    notifyDevicesFound(new ArrayList<>(deviceList));
                }
            }
        }
        
        @Override
        public void onBatchScanResults(List<ScanResult> results) {
            for (ScanResult result : results) {
                onScanResult(0, result);
            }
        }
        
        @Override
        public void onScanFailed(int errorCode) {
            isScanning = false;
            notifyError("蓝牙扫描失败: " + errorCode);
        }
    };
    
    /**
     * 连接到BLE设备
     */
    public void connectDevice(BluetoothDevice device) {
        if (device == null) {
            notifyError("设备为空");
            return;
        }
        
        // 断开现有连接
        disconnect();
        
        // 检查权限并连接新设备
        checkBluetoothPermissionAndRun(() -> {
            executor.execute(() -> {
                bluetoothGatt = device.connectGatt(context, false, gattCallback);
            });
        }, "缺少蓝牙连接权限");
    }
    
    /**
     * 断开BLE连接
     */
    public void disconnect() {
        if (bluetoothGatt != null) {
            checkBluetoothPermissionAndRun(() -> {
                executor.execute(() -> {
                    bluetoothGatt.disconnect();
                    bluetoothGatt.close();
                    bluetoothGatt = null;
                });
                
                isConnected = false;
                notifyDisconnected();
            }, "缺少蓝牙连接权限");
        }
    }
    
    /**
     * GATT回调
     */
    private final BluetoothGattCallback gattCallback = new BluetoothGattCallback() {
        @Override
        public void onConnectionStateChange(BluetoothGatt gatt, int status, int newState) {
            if (status == BluetoothGatt.GATT_SUCCESS) {
                if (newState == BluetoothProfile.STATE_CONNECTED) {
                    // 连接成功，开始服务发现
                    Log.d(TAG, "蓝牙设备已连接，开始发现服务");
                    
                    checkBluetoothPermissionAndRun(() -> {
                        bluetoothGatt.discoverServices();
                    }, "缺少蓝牙连接权限");
                } else if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                    // 断开连接
                    Log.d(TAG, "蓝牙设备已断开连接");
                    isConnected = false;
                    notifyDisconnected();
                }
            } else {
                // 连接错误
                Log.e(TAG, "连接状态错误: " + status);
                notifyError("连接错误: " + status);
                
                // 清理连接
                if (bluetoothGatt != null) {
                    bluetoothGatt.close();
                    bluetoothGatt = null;
                }
                isConnected = false;
                notifyDisconnected();
            }
        }

        @Override
        public void onServicesDiscovered(BluetoothGatt gatt, int status) {
            if (status == BluetoothGatt.GATT_SUCCESS) {
                Log.d(TAG, "GATT服务发现成功");
                
                // 查找目标服务和特征
                BluetoothGattService targetService = gatt.getService(SERVICE_UUID);
                if (targetService == null) {
                    notifyError("未找到目标服务");
                    return;
                }
                
                // 获取发送特征
                txCharacteristic = targetService.getCharacteristic(TX_CHAR_UUID);
                if (txCharacteristic == null) {
                    notifyError("未找到发送特征");
                    return;
                }
                
                // 获取接收特征
                rxCharacteristic = targetService.getCharacteristic(RX_CHAR_UUID);
                if (rxCharacteristic == null) {
                    notifyError("未找到接收特征");
                    return;
                }
                
                // 启用通知
                boolean hasPermission = checkBluetoothPermissionAndRun(null, "缺少蓝牙连接权限");
                if (!hasPermission) {
                    return;
                }
                
                boolean notificationEnabled = gatt.setCharacteristicNotification(rxCharacteristic, true);
                if (!notificationEnabled) {
                    notifyError("无法启用特征通知");
                    return;
                }
                
                // 连接成功
                isConnected = true;
                final BluetoothDevice device = gatt.getDevice();
                mainHandler.post(() -> {
                    // 通知UI线程连接已建立
                    checkBluetoothPermissionAndRun(() -> {
                        notifyConnected(device);
                    }, null);
                });
            } else {
                notifyError("GATT服务发现失败: " + status);
            }
        }

        @Override
        public void onCharacteristicRead(BluetoothGatt gatt, BluetoothGattCharacteristic characteristic, int status) {
            if (status == BluetoothGatt.GATT_SUCCESS) {
                byte[] data = characteristic.getValue();
                if (data != null && data.length > 0) {
                    processReceivedData(data);
                }
            } else {
                Log.e(TAG, "特征读取失败: " + status);
            }
        }

        @Override
        public void onCharacteristicChanged(BluetoothGatt gatt, BluetoothGattCharacteristic characteristic) {
            byte[] data = characteristic.getValue();
            if (data != null && data.length > 0) {
                processReceivedData(data);
            }
        }
    };
    
    /**
     * 处理接收的数据
     */
    private void processReceivedData(byte[] data) {
        // 解析响应数据
        IntercomResponse response = IntercomResponse.fromRawData(data);
        if (response != null) {
            // 如果是音频数据
            if (response.getResponseType() == IntercomResponse.RESP_RECEIVE_AUDIO_DATA) {
                byte[] audioData = response.parseAudioData();
                if (audioData != null) {
                    notifyAudioDataReceived(audioData);
                }
            } else {
                // 其他响应类型
                notifyResponseReceived(response);
            }
        }
    }
    
    /**
     * 发送命令到BLE设备
     */
    public void sendCommand(byte[] command) {
        if (!isConnected || txCharacteristic == null || bluetoothGatt == null) {
            notifyError("BLE未连接或特征不可用");
            return;
        }
        
        executor.execute(() -> {
            try {
                boolean hasPermission = checkBluetoothPermissionAndRun(null, "缺少蓝牙连接权限");
                if (!hasPermission) {
                    return;
                }
                
                // 大数据需要分片发送
                if (command.length > 20) {
                    // BLE数据包最大20字节，分片发送
                    sendLargeData(command);
                } else {
                    // 标准发送
                    txCharacteristic.setValue(command);
                    boolean success = bluetoothGatt.writeCharacteristic(txCharacteristic);
                    if (!success) {
                        mainHandler.post(() -> notifyError("发送命令失败"));
                    }
                }
            } catch (Exception e) {
                Log.e(TAG, "发送命令异常", e);
                mainHandler.post(() -> notifyError("发送命令异常: " + e.getMessage()));
            }
        });
    }
    
    /**
     * 分片发送大数据
     */
    private void sendLargeData(byte[] data) {
        // BLE数据包最大20字节，分片发送
        int offset = 0;
        final int MAX_PACKET_SIZE = 20;
        
        try {
            while (offset < data.length) {
                // 计算当前分片大小
                int chunkSize = Math.min(MAX_PACKET_SIZE, data.length - offset);
                byte[] chunk = new byte[chunkSize];
                System.arraycopy(data, offset, chunk, 0, chunkSize);
                
                // 发送分片
                txCharacteristic.setValue(chunk);
                boolean success = bluetoothGatt.writeCharacteristic(txCharacteristic);
                
                if (!success) {
                    mainHandler.post(() -> notifyError("发送数据分片失败"));
                    return;
                }
                
                // 移动偏移量
                offset += chunkSize;
                
                // 给BLE堆栈处理时间
                Thread.sleep(10);
            }
        } catch (Exception e) {
            Log.e(TAG, "分片发送数据异常", e);
            mainHandler.post(() -> notifyError("分片发送异常: " + e.getMessage()));
        }
    }
    
    /**
     * 以下为与UsbIntercomManager相同的接口方法，用于保持API一致性
     */
    
    /**
     * 检查是否已连接
     */
    public boolean isConnected() {
        return isConnected;
    }
    
    /**
     * 设置PTT按下
     */
    public void pttPress() {
        // 构造PTT按下命令并发送
        byte[] command = IntercomCommand.pttPress();
        sendCommand(command);
    }
    
    /**
     * 设置PTT释放
     */
    public void pttRelease() {
        // 构造PTT释放命令并发送
        byte[] command = IntercomCommand.pttRelease();
        sendCommand(command);
    }
    
    /**
     * 获取频率
     */
    public void getFrequency() {
        byte[] command = IntercomCommand.getFrequency();
        sendCommand(command);
    }
    
    /**
     * 设置频率
     */
    public void setFrequency(float frequency) {
        byte[] command = IntercomCommand.setFrequency(frequency);
        sendCommand(command);
    }
    
    /**
     * 获取音量
     */
    public void getVolume() {
        byte[] command = IntercomCommand.getVolume();
        sendCommand(command);
    }
    
    /**
     * 设置音量
     */
    public void setVolume(int volume) {
        byte[] command = IntercomCommand.setVolume(volume);
        sendCommand(command);
    }
    
    /**
     * 获取通道
     */
    public void getChannel() {
        byte[] command = IntercomCommand.getChannel();
        sendCommand(command);
    }
    
    /**
     * 设置通道
     */
    public void setChannel(int channel) {
        byte[] command = IntercomCommand.setChannel(channel);
        sendCommand(command);
    }
    
    /**
     * 获取信号强度
     */
    public void getRssi() {
        byte[] command = IntercomCommand.getRssi();
        sendCommand(command);
    }
    
    /**
     * 获取电池电量
     */
    public void getBattery() {
        byte[] command = IntercomCommand.getBattery();
        sendCommand(command);
    }
    
    /**
     * 获取静噪等级
     */
    public void getSquelch() {
        byte[] command = IntercomCommand.getSquelch();
        sendCommand(command);
    }
    
    /**
     * 设置静噪等级
     */
    public void setSquelch(int squelch) {
        byte[] command = IntercomCommand.setSquelch(squelch);
        sendCommand(command);
    }
    
    /**
     * 设置扫描模式
     */
    public void setScanMode(int mode) {
        byte[] command = IntercomCommand.setScanMode(mode);
        sendCommand(command);
    }
    
    /**
     * 发送音频数据
     */
    public void sendAudioData(byte[] audioData) {
        byte[] command = IntercomCommand.sendAudioData(audioData);
        sendCommand(command);
    }
    
    /**
     * 添加连接监听器
     */
    public void addConnectionListener(ConnectionListener listener) {
        if (!connectionListeners.contains(listener)) {
            connectionListeners.add(listener);
        }
    }
    
    /**
     * 移除连接监听器
     */
    public void removeConnectionListener(ConnectionListener listener) {
        connectionListeners.remove(listener);
    }
    
    /**
     * 添加响应监听器
     */
    public void addResponseListener(IntercomResponseListener listener) {
        if (!responseListeners.contains(listener)) {
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
        if (!deviceSelectionListeners.contains(listener)) {
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
        if (!audioDataListeners.contains(listener)) {
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
     * 通知连接成功
     */
    private void notifyConnected(BluetoothDevice device) {
        for (ConnectionListener listener : connectionListeners) {
            listener.onBleConnected(device);
        }
    }
    
    /**
     * 通知断开连接
     */
    private void notifyDisconnected() {
        for (ConnectionListener listener : connectionListeners) {
            listener.onBleDisconnected();
        }
    }
    
    /**
     * 通知错误
     */
    private void notifyError(String error) {
        Log.e(TAG, error);
        for (ConnectionListener listener : connectionListeners) {
            listener.onBleError(error);
        }
    }
    
    /**
     * 通知找到设备
     */
    private void notifyDevicesFound(List<BleDeviceInfo> devices) {
        mainHandler.post(() -> {
            for (DeviceSelectionListener listener : deviceSelectionListeners) {
                listener.onBleDevicesFound(devices);
            }
        });
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
        stopScan();
        disconnect();
        executor.shutdown();
    }
    
    /**
     * 连接状态监听器接口
     */
    public interface ConnectionListener {
        // BLE专用回调
        void onBleConnected(BluetoothDevice device);
        void onBleDisconnected();
        void onBleError(String error);
        
        // 以下保持与UsbIntercomManager一致的接口
        void onUsbConnected(android.hardware.usb.UsbDevice device);
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
        // BLE专用回调
        void onBleDevicesFound(List<BleDeviceInfo> devices);
        
        // 保持与UsbIntercomManager一致的接口
        void onDevicesFound(List<UsbIntercomManager.UsbDeviceInfo> devices);
    }
    
    /**
     * BLE设备信息类
     */
    public static class BleDeviceInfo {
        private final BluetoothDevice device;
        private final String name;
        private final String address;
        private final boolean isProbablyIntercom;
        
        public BleDeviceInfo(Context context, BluetoothDevice device) {
            this.device = device;
            
            // 获取设备名称，如果为空则使用地址
            String deviceName = null;
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                if (ActivityCompat.checkSelfPermission(context, android.Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED) {
                    deviceName = device.getName();
                }
            } else {
                deviceName = device.getName();
            }
            
            this.name = deviceName != null ? deviceName : "未命名设备";
            this.address = device.getAddress();
            
            // 检查设备名称是否包含对讲机相关关键字
            this.isProbablyIntercom = isProbablyIntercomDevice(deviceName);
        }
        
        public BluetoothDevice getDevice() {
            return device;
        }
        
        public String getName() {
            return name;
        }
        
        public String getAddress() {
            return address;
        }
        
        public boolean isProbablyIntercom() {
            return isProbablyIntercom;
        }
        
        @Override
        public boolean equals(Object obj) {
            if (this == obj) return true;
            if (obj == null || getClass() != obj.getClass()) return false;
            BleDeviceInfo that = (BleDeviceInfo) obj;
            return address.equals(that.address);
        }
        
        @Override
        public int hashCode() {
            return address.hashCode();
        }
    }
    
    /**
     * 判断设备是否可能是对讲机设备
     */
    private static boolean isProbablyIntercomDevice(String deviceName) {
        if (deviceName == null) {
            return false;
        }
        
        String lowerName = deviceName.toLowerCase();
        return lowerName.contains("intercom") || 
               lowerName.contains("walkie") || 
               lowerName.contains("talkie") || 
               lowerName.contains("radio") ||
               lowerName.contains("对讲机") ||
               lowerName.contains("通信");
    }
} 