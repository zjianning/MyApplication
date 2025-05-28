package com.example.walkietalkie.intercom;

/**
 * 基础对讲机管理器接口
 * 用于统一USB和BLE管理器的接口，简化代码结构
 */
public interface BaseIntercomManager {
    boolean isConnected();
    void getFrequency();
    void getVolume();
    void getChannel();
    void getRssi();
    void getBattery();
    void getSquelch();
    void setVolume(int volume);
    void setSquelch(int squelch);
    void pttPress();
    void pttRelease();
    void setScanMode(int mode);
    void sendAudioData(byte[] audioData);
    void release();
    
    /**
     * 音频数据监听器接口
     */
    interface AudioDataListener {
        void onAudioDataReceived(byte[] audioData);
    }
    
    /**
     * 添加音频数据监听器
     */
    void addAudioDataListener(AudioDataListener listener);
    
    /**
     * 移除音频数据监听器
     */
    void removeAudioDataListener(AudioDataListener listener);
} 