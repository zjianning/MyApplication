package com.example.walkietalkie.intercom;

/**
 * 对讲机指令类
 * 用于生成发送给对讲机的各种命令数据包
 */
public class IntercomCommand {
    // 命令类型
    public static final byte CMD_GET_FREQUENCY = (byte) 0x01;  // 获取频率
    public static final byte CMD_SET_FREQUENCY = (byte) 0x02;  // 设置频率
    public static final byte CMD_GET_VOLUME = (byte) 0x03;     // 获取音量
    public static final byte CMD_SET_VOLUME = (byte) 0x04;     // 设置音量
    public static final byte CMD_GET_CHANNEL = (byte) 0x05;    // 获取信道
    public static final byte CMD_SET_CHANNEL = (byte) 0x06;    // 设置信道
    public static final byte CMD_GET_RSSI = (byte) 0x07;       // 获取信号强度
    public static final byte CMD_GET_BATTERY = (byte) 0x08;    // 获取电池电量
    public static final byte CMD_PTT_PRESS = (byte) 0x09;      // 按下PTT
    public static final byte CMD_PTT_RELEASE = (byte) 0x0A;    // 释放PTT
    public static final byte CMD_SET_SCAN_MODE = (byte) 0x0B;  // 设置扫描模式
    public static final byte CMD_GET_SQUELCH = (byte) 0x0C;    // 获取静噪值
    public static final byte CMD_SET_SQUELCH = (byte) 0x0D;    // 设置静噪值
    public static final byte CMD_SEND_AUDIO_DATA = (byte) 0xA0; // 发送音频数据
    public static final byte CMD_RECEIVE_AUDIO_DATA = (byte) 0xA1; // 接收音频数据
    
    // 命令包分隔符
    private static final byte START_BYTE = (byte) 0xAA;
    private static final byte END_BYTE = (byte) 0x55;
    
    /**
     * 创建命令数据包
     * @param command 命令类型
     * @param data 命令参数数据
     * @return 完整的命令数据包
     */
    public static byte[] createCommandPacket(byte command, byte[] data) {
        // 组装命令包：起始字节 + 命令类型 + 数据长度 + 数据 + 校验和 + 结束字节
        int dataLength = (data != null) ? data.length : 0;
        byte[] packet = new byte[4 + dataLength + 1]; // 起始字节(1) + 命令(1) + 长度(1) + 数据(n) + 校验(1) + 结束字节(1)
        
        packet[0] = START_BYTE;        // 起始字节
        packet[1] = command;           // 命令类型
        packet[2] = (byte) dataLength; // 数据长度
        
        // 复制数据
        if (data != null && dataLength > 0) {
            System.arraycopy(data, 0, packet, 3, dataLength);
        }
        
        // 计算校验和 (简单的异或校验)
        byte checksum = 0;
        for (int i = 1; i < packet.length - 2; i++) {
            checksum ^= packet[i];
        }
        packet[packet.length - 2] = checksum;
        packet[packet.length - 1] = END_BYTE;
        
        return packet;
    }
    
    /**
     * 获取频率命令
     */
    public static byte[] getFrequency() {
        return createCommandPacket(CMD_GET_FREQUENCY, null);
    }
    
    /**
     * 设置频率命令
     * @param frequency 频率值 (MHz), 如 450.0625
     */
    public static byte[] setFrequency(float frequency) {
        // 将浮点数转换为字节数组 (4字节)
        byte[] data = new byte[4];
        int intBits = Float.floatToIntBits(frequency);
        data[0] = (byte) (intBits & 0xFF);
        data[1] = (byte) ((intBits >> 8) & 0xFF);
        data[2] = (byte) ((intBits >> 16) & 0xFF);
        data[3] = (byte) ((intBits >> 24) & 0xFF);
        return createCommandPacket(CMD_SET_FREQUENCY, data);
    }
    
    /**
     * 获取音量命令
     */
    public static byte[] getVolume() {
        return createCommandPacket(CMD_GET_VOLUME, null);
    }
    
    /**
     * 设置音量命令
     * @param volume 音量值 (0-10)
     */
    public static byte[] setVolume(int volume) {
        byte[] data = {(byte) volume};
        return createCommandPacket(CMD_SET_VOLUME, data);
    }
    
    /**
     * 获取信道命令
     */
    public static byte[] getChannel() {
        return createCommandPacket(CMD_GET_CHANNEL, null);
    }
    
    /**
     * 设置信道命令
     * @param channel 信道 (1-16)
     */
    public static byte[] setChannel(int channel) {
        byte[] data = {(byte) channel};
        return createCommandPacket(CMD_SET_CHANNEL, data);
    }
    
    /**
     * 获取信号强度命令
     */
    public static byte[] getRssi() {
        return createCommandPacket(CMD_GET_RSSI, null);
    }
    
    /**
     * 获取电池电量命令
     */
    public static byte[] getBattery() {
        return createCommandPacket(CMD_GET_BATTERY, null);
    }
    
    /**
     * 按下PTT命令
     */
    public static byte[] pttPress() {
        return createCommandPacket(CMD_PTT_PRESS, null);
    }
    
    /**
     * 释放PTT命令
     */
    public static byte[] pttRelease() {
        return createCommandPacket(CMD_PTT_RELEASE, null);
    }
    
    /**
     * 设置扫描模式命令
     * @param mode 扫描模式 (0=关闭, 1=开启)
     */
    public static byte[] setScanMode(int mode) {
        byte[] data = {(byte) mode};
        return createCommandPacket(CMD_SET_SCAN_MODE, data);
    }
    
    /**
     * 获取静噪值命令
     */
    public static byte[] getSquelch() {
        return createCommandPacket(CMD_GET_SQUELCH, null);
    }
    
    /**
     * 设置静噪值命令
     * @param squelch 静噪值 (0-9)
     */
    public static byte[] setSquelch(int squelch) {
        byte[] data = {(byte) squelch};
        return createCommandPacket(CMD_SET_SQUELCH, data);
    }
    
    /**
     * 发送音频数据
     * @param audioData 音频数据字节数组
     */
    public static byte[] sendAudioData(byte[] audioData) {
        // 限制单次发送的最大数据量，防止缓冲区溢出
        int maxDataLength = 256; // 最大数据长度
        
        if (audioData.length > maxDataLength) {
            // 如果数据超过最大长度，只发送前maxDataLength个字节
            byte[] truncatedData = new byte[maxDataLength];
            System.arraycopy(audioData, 0, truncatedData, 0, maxDataLength);
            return createCommandPacket(CMD_SEND_AUDIO_DATA, truncatedData);
        } else {
            return createCommandPacket(CMD_SEND_AUDIO_DATA, audioData);
        }
    }
    
    /**
     * 解析接收到的音频数据
     * @param commandData 命令数据
     * @return 音频数据字节数组
     */
    public static byte[] parseReceivedAudioData(byte[] commandData) {
        // 命令数据部分，假设命令格式为：
        // [命令类型(1字节)][数据长度(1字节)][音频数据(n字节)]
        if (commandData != null && commandData.length > 2) {
            int dataLength = commandData[1] & 0xFF;
            if (dataLength > 0 && commandData.length >= dataLength + 2) {
                byte[] audioData = new byte[dataLength];
                System.arraycopy(commandData, 2, audioData, 0, dataLength);
                return audioData;
            }
        }
        return new byte[0];
    }
} 