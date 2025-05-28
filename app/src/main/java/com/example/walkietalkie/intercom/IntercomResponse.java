package com.example.walkietalkie.intercom;

/**
 * 对讲机响应类
 * 处理从对讲机接收的各种响应数据
 */
public class IntercomResponse {
    // 响应类型常量
    public static final int RESP_SUCCESS = 0x00;               // 成功响应
    public static final int RESP_ERROR = 0xFF;                 // 错误响应
    public static final int RESP_GET_FREQUENCY = 0x11;         // 获取频率响应
    public static final int RESP_GET_VOLUME = 0x21;            // 获取音量响应
    public static final int RESP_GET_SQUELCH = 0x31;           // 获取静噪响应
    public static final int RESP_GET_CHANNEL = 0x41;           // 获取信道响应
    public static final int RESP_GET_RSSI = 0x51;              // 获取信号强度响应
    public static final int RESP_GET_BATTERY = 0x61;           // 获取电池电量响应
    public static final int RESP_SET_SCAN_MODE = 0x71;         // 设置扫描模式响应
    public static final int RESP_RECEIVE_AUDIO_DATA = 0xA1;    // 接收音频数据响应
    
    private final int responseType;
    private final byte[] responseData;
    private final boolean success;
    
    /**
     * 构造函数
     * @param responseType 响应类型
     * @param responseData 响应数据
     * @param success 是否成功
     */
    public IntercomResponse(int responseType, byte[] responseData, boolean success) {
        this.responseType = responseType;
        this.responseData = responseData;
        this.success = success;
    }
    
    /**
     * 获取响应类型
     * @return 响应类型
     */
    public int getResponseType() {
        return responseType;
    }
    
    /**
     * 获取响应数据
     * @return 响应数据
     */
    public byte[] getResponseData() {
        return responseData;
    }
    
    /**
     * 响应是否成功
     * @return 是否成功
     */
    public boolean isSuccess() {
        return success;
    }
    
    /**
     * 解析频率
     * @return 频率值(MHz)
     */
    public float parseFrequency() {
        if (responseType == RESP_GET_FREQUENCY && responseData.length >= 4) {
            // 假设频率数据为4字节浮点数
            int value = (responseData[0] & 0xFF) |
                    ((responseData[1] & 0xFF) << 8) |
                    ((responseData[2] & 0xFF) << 16) |
                    ((responseData[3] & 0xFF) << 24);
            return Float.intBitsToFloat(value);
        }
        return 0.0f;
    }
    
    /**
     * 解析音量
     * @return 音量值(0-10)
     */
    public int parseVolume() {
        if (responseType == RESP_GET_VOLUME && responseData.length >= 1) {
            return responseData[0] & 0xFF;
        }
        return 0;
    }
    
    /**
     * 解析静噪
     * @return 静噪值(0-10)
     */
    public int parseSquelch() {
        if (responseType == RESP_GET_SQUELCH && responseData.length >= 1) {
            return responseData[0] & 0xFF;
        }
        return 0;
    }
    
    /**
     * 解析信道
     * @return 信道值
     */
    public int parseChannel() {
        if (responseType == RESP_GET_CHANNEL && responseData.length >= 1) {
            return responseData[0] & 0xFF;
        }
        return 0;
    }
    
    /**
     * 解析信号强度
     * @return 信号强度值(0-5)
     */
    public int parseRssi() {
        if (responseType == RESP_GET_RSSI && responseData.length >= 1) {
            return responseData[0] & 0xFF;
        }
        return 0;
    }
    
    /**
     * 解析电池电量
     * @return 电池电量百分比(0-100)
     */
    public int parseBattery() {
        if (responseType == RESP_GET_BATTERY && responseData.length >= 1) {
            return responseData[0] & 0xFF;
        }
        return 0;
    }
    
    /**
     * 解析接收到的音频数据
     * @return 音频数据字节数组
     */
    public byte[] parseAudioData() {
        if (responseType == RESP_RECEIVE_AUDIO_DATA && responseData.length > 0) {
            return responseData;
        }
        return new byte[0];
    }
    
    /**
     * 从原始数据创建响应对象
     * @param rawData 原始响应数据
     * @return 响应对象
     */
    public static IntercomResponse fromRawData(byte[] rawData) {
        if (rawData == null || rawData.length < 2) {
            return new IntercomResponse(RESP_ERROR, new byte[0], false);
        }
        
        // 第一个字节是响应类型
        int responseType = rawData[0] & 0xFF;
        
        // 第二个字节是状态码
        boolean success = (rawData[1] & 0xFF) == RESP_SUCCESS;
        
        // 提取响应数据
        byte[] responseData = new byte[rawData.length - 2];
        if (responseData.length > 0) {
            System.arraycopy(rawData, 2, responseData, 0, responseData.length);
        }
        
        return new IntercomResponse(responseType, responseData, success);
    }
} 