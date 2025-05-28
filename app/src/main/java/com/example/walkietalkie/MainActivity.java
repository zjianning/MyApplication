package com.example.walkietalkie;

import android.Manifest;
import android.bluetooth.BluetoothDevice;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.hardware.usb.UsbDevice;
import android.os.Build;
import android.os.Bundle;
import android.util.Log;
import android.view.Menu;
import android.view.MenuItem;
import android.view.View;
import android.widget.Button;
import android.widget.ProgressBar;
import android.widget.RadioButton;
import android.widget.RadioGroup;
import android.widget.TextView;
import android.widget.Toast;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.content.ContextCompat;

import com.example.walkietalkie.audio.AudioManager;
import com.example.walkietalkie.databinding.ActivityMainBinding;
import com.example.walkietalkie.intercom.BaseIntercomManager;
import com.example.walkietalkie.intercom.BleIntercomManager;
import com.example.walkietalkie.intercom.IntercomResponse;
import com.example.walkietalkie.intercom.UsbIntercomManager;
import com.google.android.material.slider.Slider;
import com.google.android.material.snackbar.Snackbar;

import java.util.ArrayList;
import java.util.List;

/**
 * 主活动类，管理对讲机应用的用户界面和核心功能
 * 实现了多个监听器接口，用于处理USB连接、对讲机响应、设备选择和音频处理
 */
public class MainActivity extends AppCompatActivity implements 
        UsbIntercomManager.ConnectionListener,
        UsbIntercomManager.IntercomResponseListener,
        UsbIntercomManager.DeviceSelectionListener,
        DeviceSelectionDialog.DeviceSelectionListener,
        BleIntercomManager.ConnectionListener,
        BleIntercomManager.IntercomResponseListener,
        BleIntercomManager.DeviceSelectionListener,
        BleDeviceSelectionDialog.DeviceSelectionListener,
        AudioManager.AudioProcessListener {

    private static final String TAG = "MainActivity";
    private ActivityMainBinding binding;
    
    // 对讲机管理器 - 控制USB对讲机的连接和通信
    private UsbIntercomManager usbIntercomManager;
    
    // 蓝牙对讲机管理器
    private BleIntercomManager bleIntercomManager;
    
    // 当前活跃的通信管理器
    private BaseIntercomManager activeIntercomManager;
    
    // 音频管理器 - 处理音频的录制和播放
    private AudioManager audioManager;
    
    // 设备选择对话框 - 用于用户选择USB设备
    private DeviceSelectionDialog usbDeviceSelectionDialog;
    
    // 蓝牙设备选择对话框
    private BleDeviceSelectionDialog bleDeviceSelectionDialog;
    
    // UI组件 - 用于显示和控制对讲机状态
    private TextView statusTextView;          // 连接状态显示
    private RadioGroup connectionTypeGroup;   // 连接类型选择
    private RadioButton radioUsb;             // USB连接选项
    private RadioButton radioBle;             // 蓝牙连接选项
    private Button scanUsbButton;             // USB扫描按钮
    private Button scanBleButton;             // 蓝牙扫描按钮
    private TextView frequencyTextView;       // 频率显示
    private TextView volumeTextView;          // 音量显示
    private TextView batteryTextView;         // 电池电量显示
    private TextView channelTextView;         // 通道显示
    private TextView rssiTextView;            // 信号强度显示
    private Slider volumeSlider;              // 音量滑块控制
    private Slider squelchSlider;             // 静噪滑块控制
    private Button pttButton;                 // 按键通话按钮
    private Button scanButton;                // 扫描按钮
    private ProgressBar audioLevelBar;        // 音频电平指示条
    
    // 连接方式
    private static final int CONNECTION_TYPE_USB = 0;
    private static final int CONNECTION_TYPE_BLE = 1;
    private int currentConnectionType = CONNECTION_TYPE_USB;
    
    // 所需权限 - 应用运行需要的Android权限
    private static final String[] REQUIRED_PERMISSIONS = {
            Manifest.permission.RECORD_AUDIO, // 录音权限，用于音频通话功能
            Manifest.permission.ACCESS_FINE_LOCATION // 位置权限，Android 11以下BLE扫描需要
    };
    
    // 权限请求启动器 - 处理Android权限请求的结果
    private final ActivityResultLauncher<String[]> requestPermissionLauncher =
            registerForActivityResult(new ActivityResultContracts.RequestMultiplePermissions(), result -> {
                boolean allGranted = true;
                for (Boolean granted : result.values()) {
                    allGranted = allGranted && granted;
                }
                
                if (allGranted) {
                    // 所有权限已授予，初始化音频
                    initAudioManager();
                    
                    // 检查蓝牙权限是否已授予
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                        if (ContextCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_SCAN) == 
                                PackageManager.PERMISSION_GRANTED && 
                            ContextCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_CONNECT) == 
                                PackageManager.PERMISSION_GRANTED) {
                            // 蓝牙权限已授予，初始化蓝牙
                            initBleIntercomManager();
                        }
                    }
                } else {
                    // 部分权限被拒绝
                    Toast.makeText(this, R.string.error_recording_permission_required, Toast.LENGTH_LONG).show();
                }
            });
    
    // 蓝牙权限请求启动器
    private final ActivityResultLauncher<String[]> requestBlePermissionLauncher =
            registerForActivityResult(new ActivityResultContracts.RequestMultiplePermissions(), result -> {
                boolean allGranted = true;
                for (Boolean granted : result.values()) {
                    allGranted = allGranted && granted;
                }
                
                if (allGranted) {
                    // 蓝牙权限已授予，扫描蓝牙设备
                    scanBleDevices();
                } else {
                    // 权限被拒绝
                    Toast.makeText(this, R.string.error_bluetooth_permission, Toast.LENGTH_LONG).show();
                }
            });

    /**
     * 活动创建时调用，初始化UI和管理器
     */
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        binding = ActivityMainBinding.inflate(getLayoutInflater());
        setContentView(binding.getRoot());

        // 初始化对讲机管理器
        usbIntercomManager = new UsbIntercomManager(this);
        usbIntercomManager.addConnectionListener(this);
        usbIntercomManager.addResponseListener(this);
        usbIntercomManager.addDeviceSelectionListener(this);
        
        // 初始化设备选择对话框
        usbDeviceSelectionDialog = new DeviceSelectionDialog(this);
        usbDeviceSelectionDialog.setDeviceSelectionListener(this);
        
        // 初始化蓝牙设备选择对话框
        bleDeviceSelectionDialog = new BleDeviceSelectionDialog(this);
        bleDeviceSelectionDialog.setDeviceSelectionListener(this);
        
        // 设置初始活跃管理器
        activeIntercomManager = usbIntercomManager;
        
        // 使用数据绑定初始化UI组件
        statusTextView = binding.contentMain.statusText;
        connectionTypeGroup = binding.contentMain.connectionTypeGroup;
        radioUsb = binding.contentMain.radioUsb;
        radioBle = binding.contentMain.radioBle;
        scanUsbButton = binding.contentMain.scanUsbButton;
        scanBleButton = binding.contentMain.scanBleButton;
        frequencyTextView = binding.contentMain.frequencyText;
        volumeTextView = binding.contentMain.volumeText;
        batteryTextView = binding.contentMain.batteryText;
        channelTextView = binding.contentMain.channelText;
        rssiTextView = binding.contentMain.rssiText;
        volumeSlider = binding.contentMain.volumeSlider;
        squelchSlider = binding.contentMain.squelchSlider;
        pttButton = binding.contentMain.pttButton;
        scanButton = binding.contentMain.scanButton;
        audioLevelBar = binding.contentMain.audioLevelBar;
        
        // 设置连接类型切换监听器
        connectionTypeGroup.setOnCheckedChangeListener((group, checkedId) -> {
            if (checkedId == R.id.radio_usb) {
                currentConnectionType = CONNECTION_TYPE_USB;
                activeIntercomManager = usbIntercomManager;
                scanUsbButton.setEnabled(true);
                scanBleButton.setEnabled(false);
                updateUiConnectionState(usbIntercomManager.isConnected());
            } else if (checkedId == R.id.radio_ble) {
                currentConnectionType = CONNECTION_TYPE_BLE;
                
                // 初始化蓝牙管理器
                if (bleIntercomManager == null) {
                    initBleIntercomManager();
                }
                
                // 切换活跃管理器
                if (bleIntercomManager != null) {
                    activeIntercomManager = bleIntercomManager;
                }
                
                scanUsbButton.setEnabled(false);
                scanBleButton.setEnabled(bleIntercomManager != null);
                
                // 更新UI状态
                if (bleIntercomManager != null) {
                    updateUiConnectionState(bleIntercomManager.isConnected());
                } else {
                    updateUiConnectionState(false);
                }
            }
        });
        
        // 设置USB扫描按钮点击监听器
        scanUsbButton.setOnClickListener(v -> {
            scanUsbDevices();
        });
        
        // 设置蓝牙扫描按钮点击监听器
        scanBleButton.setOnClickListener(v -> {
            // 检查蓝牙权限
            if (bleIntercomManager != null) {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                    if (ContextCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_SCAN) != 
                            PackageManager.PERMISSION_GRANTED ||
                        ContextCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_CONNECT) != 
                            PackageManager.PERMISSION_GRANTED) {
                        
                        // 请求蓝牙权限
                        requestBlePermissionLauncher.launch(new String[]{
                                Manifest.permission.BLUETOOTH_SCAN,
                                Manifest.permission.BLUETOOTH_CONNECT
                        });
                        return;
                    }
                } else if (ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) != 
                        PackageManager.PERMISSION_GRANTED) {
                    // Android 11以下需要位置权限
                    requestPermissionLauncher.launch(new String[]{
                            Manifest.permission.ACCESS_FINE_LOCATION
                    });
                    return;
                }
                
                // 扫描蓝牙设备
                scanBleDevices();
            } else {
                Toast.makeText(this, R.string.error_bluetooth_not_supported, Toast.LENGTH_SHORT).show();
            }
        });
        
        // 设置音量滑块变化监听器
        volumeSlider.addOnChangeListener((slider, value, fromUser) -> {
            if (fromUser && activeIntercomManager != null) {
                int volume = (int) value;
                activeIntercomManager.setVolume(volume);  // 发送音量设置命令到对讲机
                volumeTextView.setText(getString(R.string.device_volume, volume));
            }
        });
        
        // 设置静噪滑块变化监听器
        squelchSlider.addOnChangeListener((slider, value, fromUser) -> {
            if (fromUser && activeIntercomManager != null) {
                int squelch = (int) value;
                activeIntercomManager.setSquelch(squelch);  // 发送静噪设置命令到对讲机
            }
        });
        
        // 设置PTT按钮事件 - 按下开始发送，松开停止发送
        pttButton.setOnTouchListener((v, event) -> {
            switch (event.getAction()) {
                case android.view.MotionEvent.ACTION_DOWN:
                    if (activeIntercomManager != null && activeIntercomManager.isConnected()) {
                        activeIntercomManager.pttPress();  // 通知对讲机PTT按下
                        pttButton.setText(R.string.btn_ptt_active);
                        
                        // 开始录音并发送
                        if (hasRequiredPermissions()) {
                            if (audioManager == null) {
                                initAudioManager();
                            }
                            if (audioManager != null) {
                                audioManager.startRecording();  // 启动音频录制
                            }
                        } else {
                            requestPermissions();  // 请求所需权限
                        }
                    }
                    break;
                case android.view.MotionEvent.ACTION_UP:
                case android.view.MotionEvent.ACTION_CANCEL:
                    if (activeIntercomManager != null) {
                        activeIntercomManager.pttRelease();  // 通知对讲机PTT释放
                        pttButton.setText(R.string.btn_ptt);
                        
                        // 停止录音
                        if (audioManager != null) {
                            audioManager.stopRecording();
                        }
                        
                        // 重置音频电平指示条
                        audioLevelBar.setProgress(0);
                    }
                    break;
            }
            return false;
        });
        
        // 设置扫描按钮事件 - 切换扫描模式
        scanButton.setOnClickListener(v -> {
            if (activeIntercomManager != null) {
                Integer tag = (Integer) scanButton.getTag();
                
                if (tag == null || tag == 0) {
                    activeIntercomManager.setScanMode(1);  // 开始扫描
                    scanButton.setText(R.string.btn_scan_stop);
                    scanButton.setTag(1);
                } else {
                    activeIntercomManager.setScanMode(0);  // 停止扫描
                    scanButton.setText(R.string.btn_scan_start);
                    scanButton.setTag(0);
                }
            }
        });
        
        // 初始化UI状态
        updateUiConnectionState(false);
        
        // 检查并请求权限
        if (!hasRequiredPermissions()) {
            requestPermissions();
        } else {
            initAudioManager();
            
            // 初始化蓝牙管理器
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                if (ContextCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_SCAN) == 
                        PackageManager.PERMISSION_GRANTED && 
                    ContextCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_CONNECT) == 
                        PackageManager.PERMISSION_GRANTED) {
                    initBleIntercomManager();
                }
            } else {
                initBleIntercomManager();
            }
        }
        
        // 应用启动时自动扫描USB设备
        if (usbIntercomManager != null) {
            usbIntercomManager.scanAndConnect();
        }
    }
    
    /**
     * 活动恢复时自动扫描USB设备
     */
    @Override
    protected void onResume() {
        super.onResume();
        // 每次恢复活动时扫描设备
        if (currentConnectionType == CONNECTION_TYPE_USB) {
            if (usbIntercomManager != null && !usbIntercomManager.isConnected()) {
                usbIntercomManager.scanAndConnect();
            }
        } else {
            if (bleIntercomManager != null && !bleIntercomManager.isConnected()) {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                    if (ContextCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_SCAN) == 
                            PackageManager.PERMISSION_GRANTED) {
                        bleIntercomManager.scanDevices();
                    }
                } else if (ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) == 
                        PackageManager.PERMISSION_GRANTED) {
                    bleIntercomManager.scanDevices();
                }
            }
        }
    }
    
    /**
     * 初始化蓝牙对讲机管理器
     */
    private void initBleIntercomManager() {
        if (bleIntercomManager == null) {
            bleIntercomManager = new BleIntercomManager(this);
            
            // 检查设备是否支持BLE
            if (!bleIntercomManager.isBleSupported()) {
                Toast.makeText(this, R.string.error_bluetooth_not_supported, Toast.LENGTH_SHORT).show();
                radioBle.setEnabled(false);
                scanBleButton.setEnabled(false);
                bleIntercomManager = null;
                return;
            }
            
            // 检查蓝牙是否已启用
            if (!bleIntercomManager.isBluetoothEnabled()) {
                Toast.makeText(this, R.string.error_bluetooth_not_enabled, Toast.LENGTH_SHORT).show();
                return;
            }
            
            // 添加监听器
            bleIntercomManager.addConnectionListener(this);
            bleIntercomManager.addResponseListener(this);
            bleIntercomManager.addDeviceSelectionListener(this);
            
            // 启用蓝牙按钮
            radioBle.setEnabled(true);
            scanBleButton.setEnabled(true);
        }
    }
    
    /**
     * 扫描USB设备
     */
    private void scanUsbDevices() {
        statusTextView.setText(R.string.status_scanning);
        if (usbIntercomManager != null) {
            usbIntercomManager.scanAndConnect();
        }
    }
    
    /**
     * 扫描蓝牙设备
     */
    private void scanBleDevices() {
        statusTextView.setText(R.string.status_scanning);
        if (bleIntercomManager != null) {
            bleIntercomManager.scanDevices();
        }
    }
    
    /**
     * 初始化音频管理器
     * 设置录音和播放功能，连接到对讲机管理器
     */
    private void initAudioManager() {
        if (activeIntercomManager != null && hasRequiredPermissions()) {
            if (audioManager != null) {
                audioManager.release();  // 释放之前的实例
            }
            
            audioManager = new AudioManager(activeIntercomManager);
            audioManager.setAudioProcessListener(this);
            boolean initialized = audioManager.initRecorder();
            if (!initialized) {
                Toast.makeText(this, R.string.error_audio_recorder_init, Toast.LENGTH_SHORT).show();
            }
        }
    }
    
    /**
     * 检查是否已授予所需权限
     * @return 如果所有权限已授予返回true，否则返回false
     */
    private boolean hasRequiredPermissions() {
        for (String permission : REQUIRED_PERMISSIONS) {
            if (ContextCompat.checkSelfPermission(this, permission) != PackageManager.PERMISSION_GRANTED) {
                return false;
            }
        }
        return true;
    }
    
    /**
     * 请求所需权限
     * 用于获取应用运行所需的Android权限
     */
    private void requestPermissions() {
        List<String> permissionsToRequest = new ArrayList<>();
        
        for (String permission : REQUIRED_PERMISSIONS) {
            if (ContextCompat.checkSelfPermission(this, permission) != PackageManager.PERMISSION_GRANTED) {
                permissionsToRequest.add(permission);
            }
        }
        
        // Android 12+需要额外的蓝牙权限
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_SCAN) != 
                    PackageManager.PERMISSION_GRANTED) {
                permissionsToRequest.add(Manifest.permission.BLUETOOTH_SCAN);
            }
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_CONNECT) != 
                    PackageManager.PERMISSION_GRANTED) {
                permissionsToRequest.add(Manifest.permission.BLUETOOTH_CONNECT);
            }
        }
        
        if (!permissionsToRequest.isEmpty()) {
            requestPermissionLauncher.launch(permissionsToRequest.toArray(new String[0]));
        }
    }
    
    /**
     * 活动销毁时调用，释放资源
     * 清理音频、对讲机和UI资源
     */
    @Override
    protected void onDestroy() {
        super.onDestroy();
        
        // 优化资源释放，加入空检查以提高代码健壮性
        releaseAudioManager();
        releaseUsbManager();
        releaseBleManager();
        
        if (usbDeviceSelectionDialog != null) {
            usbDeviceSelectionDialog.dismiss();  // 关闭设备选择对话框
            usbDeviceSelectionDialog = null;
        }
        
        if (bleDeviceSelectionDialog != null) {
            bleDeviceSelectionDialog.dismiss();  // 关闭蓝牙设备选择对话框
            bleDeviceSelectionDialog = null;
        }
    }
    
    /**
     * 释放音频管理器资源
     */
    private void releaseAudioManager() {
        if (audioManager != null) {
            audioManager.release();  // 释放音频资源
            audioManager = null;
        }
    }
    
    /**
     * 释放USB管理器资源
     */
    private void releaseUsbManager() {
        if (usbIntercomManager != null) {
            usbIntercomManager.removeConnectionListener(this);
            usbIntercomManager.removeResponseListener(this);
            usbIntercomManager.removeDeviceSelectionListener(this);
            usbIntercomManager.release();  // 释放对讲机资源
            usbIntercomManager = null;
        }
    }
    
    /**
     * 释放BLE管理器资源
     */
    private void releaseBleManager() {
        if (bleIntercomManager != null) {
            bleIntercomManager.removeConnectionListener(this);
            bleIntercomManager.removeResponseListener(this);
            bleIntercomManager.removeDeviceSelectionListener(this);
            bleIntercomManager.release();  // 释放蓝牙资源
            bleIntercomManager = null;
        }
    }
    
    /**
     * 更新UI连接状态
     * @param connected 是否已连接
     */
    private void updateUiConnectionState(boolean connected) {
        if (connected) {
            // 连接状态 - 显示为绿色
            if (currentConnectionType == CONNECTION_TYPE_USB) {
                statusTextView.setText(R.string.status_connected);
            } else {
                statusTextView.setText(R.string.status_ble_connected);
            }
            statusTextView.setTextColor(getResources().getColor(android.R.color.holo_green_dark));
            
            // 更新对讲机状态 - 查询设备信息
            if (activeIntercomManager != null) {
                activeIntercomManager.getFrequency();  // 获取频率
                activeIntercomManager.getVolume();     // 获取音量
                activeIntercomManager.getChannel();    // 获取通道
                activeIntercomManager.getRssi();       // 获取信号强度
                activeIntercomManager.getBattery();    // 获取电池电量
                activeIntercomManager.getSquelch();    // 获取静噪等级
            }
        } else {
            // 断开连接状态 - 显示为红色
            if (currentConnectionType == CONNECTION_TYPE_USB) {
                statusTextView.setText(R.string.status_disconnected);
            } else {
                statusTextView.setText(R.string.status_ble_disconnected);
            }
            statusTextView.setTextColor(getResources().getColor(android.R.color.holo_red_dark));
            
            // 重置显示 - 清空所有设备信息
            frequencyTextView.setText(getString(R.string.device_frequency, "--"));
            volumeTextView.setText(getString(R.string.device_volume, 0));
            batteryTextView.setText(getString(R.string.device_battery, 0));
            channelTextView.setText(getString(R.string.device_channel, 0));
            rssiTextView.setText(getString(R.string.device_signal, 0));
        }
        
        // 启用/禁用控制按钮 - 根据连接状态
        volumeSlider.setEnabled(connected);
        squelchSlider.setEnabled(connected);
        pttButton.setEnabled(connected);
        scanButton.setEnabled(connected);
    }

    /**
     * 创建选项菜单
     */
    @Override
    public boolean onCreateOptionsMenu(Menu menu) {
        getMenuInflater().inflate(R.menu.menu_main, menu);
        return true;
    }

    /**
     * 处理选项菜单项的选择
     */
    @Override
    public boolean onOptionsItemSelected(MenuItem item) {
        int id = item.getItemId();

        if (id == R.id.action_settings) {
            // 设置功能尚未实现
            Toast.makeText(this, getString(R.string.action_settings), Toast.LENGTH_SHORT).show();
            return true;
        } else if (id == R.id.action_privacy_policy) {
            // 打开隐私政策页面
            startActivity(new Intent(this, PrivacyPolicyActivity.class));
            return true;
        }

        return super.onOptionsItemSelected(item);
    }
    
    /**
     * 统一处理连接状态变化
     * @param connected 是否已连接
     * @param connectionType 连接类型（USB/BLE）
     * @param device 连接的设备
     */
    private void handleConnectionStateChange(boolean connected, int connectionType, Object device) {
        // 确保在UI线程上运行
        runOnUiThread(() -> {
            // 更新当前连接类型
            currentConnectionType = connectionType;
            
            // 更新活跃的通信管理器
            if (connectionType == CONNECTION_TYPE_USB) {
                activeIntercomManager = usbIntercomManager;
                radioUsb.setChecked(true);
            } else {
                activeIntercomManager = bleIntercomManager;
                radioBle.setChecked(true);
            }
            
            // 更新UI状态
            updateUiConnectionState(connected);
            
            // 如果已连接，初始化音频管理器
            if (connected && hasRequiredPermissions()) {
                initAudioManager();
                
                // 发送toast提示
                String toastMsg = (connectionType == CONNECTION_TYPE_USB) ? 
                        getString(R.string.status_connected) : 
                        getString(R.string.status_ble_connected);
                Toast.makeText(MainActivity.this, toastMsg, Toast.LENGTH_SHORT).show();
                
                // 记录设备信息
                if (connectionType == CONNECTION_TYPE_USB && device instanceof UsbDevice) {
                    Log.d(TAG, "USB设备已连接: " + ((UsbDevice)device).getDeviceName());
                } else if (connectionType == CONNECTION_TYPE_BLE && device instanceof BluetoothDevice) {
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                        if (ContextCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_CONNECT) == 
                                PackageManager.PERMISSION_GRANTED) {
                            Log.d(TAG, "蓝牙设备已连接: " + ((BluetoothDevice)device).getName());
                        }
                    } else {
                        Log.d(TAG, "蓝牙设备已连接: " + ((BluetoothDevice)device).getName());
                    }
                }
            } else if (!connected) {
                // 发送断开连接的toast提示
                String toastMsg = (connectionType == CONNECTION_TYPE_USB) ? 
                        getString(R.string.status_disconnected) : 
                        getString(R.string.status_ble_disconnected);
                Toast.makeText(MainActivity.this, toastMsg, Toast.LENGTH_SHORT).show();
                Log.d(TAG, connectionType == CONNECTION_TYPE_USB ? 
                        "USB设备已断开连接" : "蓝牙设备已断开连接");
            }
        });
    }

    // USB连接监听器回调 - 处理USB设备连接状态变化
    /**
     * 当USB设备连接时调用
     */
    @Override
    public void onUsbConnected(UsbDevice device) {
        handleConnectionStateChange(true, CONNECTION_TYPE_USB, device);
    }

    /**
     * 当USB设备断开连接时调用
     */
    @Override
    public void onUsbDisconnected() {
        handleConnectionStateChange(false, CONNECTION_TYPE_USB, null);
    }

    /**
     * 当USB权限被拒绝时调用
     */
    @Override
    public void onUsbPermissionDenied() {
        Log.d(TAG, "USB权限被拒绝");
        Toast.makeText(this, R.string.error_permission_denied, Toast.LENGTH_SHORT).show();
    }

    /**
     * 当发生USB错误时调用
     */
    @Override
    public void onUsbError(String error) {
        Log.e(TAG, "USB错误: " + error);
        Toast.makeText(this, getString(R.string.error_usb, error), Toast.LENGTH_SHORT).show();
    }
    
    /**
     * 当蓝牙设备连接时调用
     */
    @Override
    public void onBleConnected(BluetoothDevice device) {
        handleConnectionStateChange(true, CONNECTION_TYPE_BLE, device);
    }
    
    /**
     * 当蓝牙设备断开连接时调用
     */
    @Override
    public void onBleDisconnected() {
        handleConnectionStateChange(false, CONNECTION_TYPE_BLE, null);
    }
    
    /**
     * 当发生蓝牙错误时调用
     */
    @Override
    public void onBleError(String error) {
        Log.e(TAG, "蓝牙错误: " + error);
        Toast.makeText(this, error, Toast.LENGTH_SHORT).show();
    }

    // 设备选择监听器回调
    /**
     * 当发现USB设备时调用
     * 显示设备选择对话框
     */
    @Override
    public void onDevicesFound(List<UsbIntercomManager.UsbDeviceInfo> devices) {
        // 当发现设备时，显示设备选择对话框
        runOnUiThread(() -> {
            if (usbDeviceSelectionDialog != null) {
                usbDeviceSelectionDialog.show(devices);
            }
        });
    }
    
    /**
     * 当发现蓝牙设备时调用
     */
    @Override
    public void onBleDevicesFound(List<BleIntercomManager.BleDeviceInfo> devices) {
        // 当发现设备时，显示设备选择对话框
        runOnUiThread(() -> {
            if (bleDeviceSelectionDialog != null) {
                bleDeviceSelectionDialog.show(devices);
            }
        });
    }
    
    // 设备选择对话框监听器回调
    /**
     * 当用户选择USB设备时调用
     * 连接到选中的设备
     */
    @Override
    public void onDeviceSelected(UsbDevice device) {
        if (usbIntercomManager != null && device != null) {
            statusTextView.setText(R.string.status_connecting);
            usbIntercomManager.connectSelectedDevice(device);
        }
    }
    
    /**
     * 当用户选择蓝牙设备时调用
     */
    @Override
    public void onDeviceSelected(BluetoothDevice device) {
        if (bleIntercomManager != null && device != null) {
            statusTextView.setText(R.string.status_connecting);
            bleIntercomManager.connectDevice(device);
        }
    }

    /**
     * 当用户取消设备选择对话框时调用
     */
    @Override
    public void onDialogCancelled() {
        // 用户取消了设备选择
        Toast.makeText(this, R.string.error_connection_cancelled, Toast.LENGTH_SHORT).show();
        statusTextView.setText(currentConnectionType == CONNECTION_TYPE_USB ? 
                R.string.status_disconnected : R.string.status_ble_disconnected);
    }
    
    // 音频处理监听器回调
    /**
     * 当音频电平变化时调用
     * 更新音频电平指示条
     */
    @Override
    public void onAudioLevelChanged(int level) {
        runOnUiThread(() -> {
            if (audioLevelBar != null) {
                audioLevelBar.setProgress(level);
            }
        });
    }
    
    /**
     * 当音频处理发生错误时调用
     */
    @Override
    public void onError(String errorMessage) {
        runOnUiThread(() -> {
            Toast.makeText(MainActivity.this, errorMessage, Toast.LENGTH_SHORT).show();
        });
    }

    // 对讲机响应监听器回调
    /**
     * 当收到对讲机响应时调用
     * 处理不同类型的响应并更新UI
     */
    @Override
    public void onResponseReceived(IntercomResponse response) {
        if (response == null) {
            return;
        }
        
        switch (response.getResponseType()) {
            case IntercomResponse.RESP_GET_FREQUENCY:
                if (response.isSuccess()) {
                    float freq = response.parseFrequency();  // 解析频率数据
                    runOnUiThread(() -> frequencyTextView.setText(
                            getString(R.string.device_frequency, String.valueOf(freq))));
                }
                break;
                
            case IntercomResponse.RESP_GET_VOLUME:
                if (response.isSuccess()) {
                    int volume = response.parseVolume();  // 解析音量数据
                    runOnUiThread(() -> {
                        volumeTextView.setText(getString(R.string.device_volume, volume));
                        volumeSlider.setValue(volume);
                    });
                }
                break;
                
            case IntercomResponse.RESP_GET_CHANNEL:
                if (response.isSuccess()) {
                    int channel = response.parseChannel();  // 解析通道数据
                    runOnUiThread(() -> channelTextView.setText(
                            getString(R.string.device_channel, channel)));
                }
                break;
                
            case IntercomResponse.RESP_GET_RSSI:
                if (response.isSuccess()) {
                    int rssi = response.parseRssi();  // 解析信号强度数据
                    runOnUiThread(() -> rssiTextView.setText(
                            getString(R.string.device_signal, rssi)));
                }
                break;
                
            case IntercomResponse.RESP_GET_BATTERY:
                if (response.isSuccess()) {
                    int battery = response.parseBattery();  // 解析电池电量数据
                    runOnUiThread(() -> batteryTextView.setText(
                            getString(R.string.device_battery, battery)));
                }
                break;
                
            case IntercomResponse.RESP_GET_SQUELCH:
                if (response.isSuccess()) {
                    int squelch = response.parseSquelch();  // 解析静噪等级数据
                    runOnUiThread(() -> squelchSlider.setValue(squelch));
                }
                break;
                
            case IntercomResponse.RESP_SET_SCAN_MODE:
                // 处理扫描模式变更响应
                if (response.isSuccess()) {
                    runOnUiThread(() -> {
                        Integer tag = (Integer) scanButton.getTag();
                        if (tag != null && tag == 1) {
                            // 如果当前状态是扫描中，切换到停止扫描
                            activeIntercomManager.setScanMode(0);
                            scanButton.setText(R.string.btn_scan_start);
                            scanButton.setTag(0);
                        }
                    });
                }
                break;
            
            case IntercomResponse.RESP_RECEIVE_AUDIO_DATA:
                // 处理接收到的音频数据
                if (response.isSuccess()) {
                    byte[] audioData = response.parseAudioData();  // 解析音频数据
                    // 音频数据将由AudioManager直接处理，因为它实现了AudioDataListener接口
                    // 可以在这里添加额外的处理逻辑，如更新UI或记录音频统计信息
                }
                break;
        }
    }
} 