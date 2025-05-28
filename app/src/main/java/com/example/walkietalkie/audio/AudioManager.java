package com.example.walkietalkie.audio;

import android.content.Context;
import android.media.AudioAttributes;
import android.media.AudioFormat;
import android.media.AudioRecord;
import android.media.AudioTrack;
import android.media.MediaRecorder;
import android.util.Log;

import com.example.walkietalkie.R;
import com.example.walkietalkie.intercom.BaseIntercomManager;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 音频管理器
 * 负责处理音频录制和播放，支持双向音频传输
 */
public class AudioManager implements BaseIntercomManager.AudioDataListener {
    private static final String TAG = "AudioManager";
    
    // 音频参数
    private static final int SAMPLE_RATE = 8000; // 8kHz，常用于对讲机
    private static final int CHANNEL_CONFIG_IN = AudioFormat.CHANNEL_IN_MONO; // 单声道输入
    private static final int CHANNEL_CONFIG_OUT = AudioFormat.CHANNEL_OUT_MONO; // 单声道输出
    private static final int AUDIO_FORMAT = AudioFormat.ENCODING_PCM_16BIT; // 16位PCM
    private static final int BUFFER_SIZE_IN = AudioRecord.getMinBufferSize(
            SAMPLE_RATE, CHANNEL_CONFIG_IN, AUDIO_FORMAT) * 2; // 输入缓冲区大小
    private static final int BUFFER_SIZE_OUT = AudioTrack.getMinBufferSize(
            SAMPLE_RATE, CHANNEL_CONFIG_OUT, AUDIO_FORMAT) * 2; // 输出缓冲区大小
    
    private AudioRecord audioRecord;
    private AudioTrack audioTrack;
    private final BaseIntercomManager intercomManager;
    private final ExecutorService audioExecutor = Executors.newSingleThreadExecutor();
    private final ExecutorService playbackExecutor = Executors.newSingleThreadExecutor();
    private final AtomicBoolean isRecording = new AtomicBoolean(false);
    private final AtomicBoolean isPlaying = new AtomicBoolean(false);
    private final Context context;
    
    // 音频处理监听器
    public interface AudioProcessListener {
        void onAudioLevelChanged(int level); // 音量等级变化回调
        void onError(String errorMessage);
    }
    
    private AudioProcessListener listener;
    
    public AudioManager(BaseIntercomManager intercomManager) {
        this(intercomManager, null);
    }
    
    public AudioManager(BaseIntercomManager intercomManager, Context context) {
        this.intercomManager = intercomManager;
        this.context = context;
        // 注册为音频数据监听器，接收对讲机发送的音频
        if (intercomManager != null) {
            intercomManager.addAudioDataListener(this);
        }
    }
    
    public void setAudioProcessListener(AudioProcessListener listener) {
        this.listener = listener;
    }
    
    /**
     * 初始化音频录制器
     * @return 是否初始化成功
     */
    public boolean initRecorder() {
        try {
            audioRecord = new AudioRecord(
                    MediaRecorder.AudioSource.MIC,
                    SAMPLE_RATE,
                    CHANNEL_CONFIG_IN,
                    AUDIO_FORMAT,
                    BUFFER_SIZE_IN);
            
            if (audioRecord.getState() != AudioRecord.STATE_INITIALIZED) {
                notifyError(context != null ? 
                        context.getString(R.string.error_audio_init_failed) : 
                        "Failed to initialize audio recorder");
                return false;
            }
            
            // 初始化播放器
            initPlayer();
            
            return true;
        } catch (Exception e) {
            Log.e(TAG, "初始化音频录制器失败", e);
            String errorMsg = context != null ? 
                    context.getString(R.string.error_audio_init_failed_with_reason, e.getMessage()) : 
                    "Failed to initialize audio recorder: " + e.getMessage();
            notifyError(errorMsg);
            return false;
        }
    }
    
    /**
     * 初始化音频播放器
     * @return 是否初始化成功
     */
    private boolean initPlayer() {
        try {
            AudioAttributes audioAttributes = new AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                    .build();
            
            AudioFormat audioFormat = new AudioFormat.Builder()
                    .setSampleRate(SAMPLE_RATE)
                    .setEncoding(AUDIO_FORMAT)
                    .setChannelMask(CHANNEL_CONFIG_OUT)
                    .build();
            
            audioTrack = new AudioTrack.Builder()
                    .setAudioAttributes(audioAttributes)
                    .setAudioFormat(audioFormat)
                    .setBufferSizeInBytes(BUFFER_SIZE_OUT)
                    .setTransferMode(AudioTrack.MODE_STREAM)
                    .build();
            
            if (audioTrack.getState() != AudioTrack.STATE_INITIALIZED) {
                notifyError(context != null ? 
                        context.getString(R.string.error_audio_player_init_failed) : 
                        "Failed to initialize audio player");
                return false;
            }
            
            return true;
        } catch (Exception e) {
            Log.e(TAG, "初始化音频播放器失败", e);
            String errorMsg = context != null ? 
                    context.getString(R.string.error_audio_player_init_failed_with_reason, e.getMessage()) : 
                    "Failed to initialize audio player: " + e.getMessage();
            notifyError(errorMsg);
            return false;
        }
    }
    
    /**
     * 开始录音并发送
     */
    public void startRecording() {
        if (isRecording.get()) {
            return; // 已经在录制中
        }
        
        if (audioRecord == null && !initRecorder()) {
            return; // 初始化失败
        }
        
        isRecording.set(true);
        
        try {
            audioRecord.startRecording();
            
            audioExecutor.execute(() -> {
                byte[] buffer = new byte[BUFFER_SIZE_IN];
                while (isRecording.get()) {
                    try {
                        int bytesRead = audioRecord.read(buffer, 0, BUFFER_SIZE_IN);
                        
                        if (bytesRead > 0) {
                            // 简单音量计算
                            int audioLevel = calculateAudioLevel(buffer, bytesRead);
                            notifyAudioLevel(audioLevel);
                            
                            // 处理音频数据并发送到对讲机
                            byte[] processedData = processAudioData(buffer, bytesRead);
                            if (intercomManager != null && intercomManager.isConnected()) {
                                intercomManager.sendAudioData(processedData);
                            }
                        }
                    } catch (Exception e) {
                        Log.e(TAG, "录音时出错", e);
                        String errorMsg = context != null ? 
                                context.getString(R.string.error_recording, e.getMessage()) : 
                                "Recording error: " + e.getMessage();
                        notifyError(errorMsg);
                        break;
                    }
                }
                
                // 停止录音
                stopRecording();
            });
        } catch (Exception e) {
            Log.e(TAG, "开始录音失败", e);
            String errorMsg = context != null ? 
                    context.getString(R.string.error_start_recording, e.getMessage()) : 
                    "Failed to start recording: " + e.getMessage();
            notifyError(errorMsg);
            isRecording.set(false);
        }
    }
    
    /**
     * 停止录音
     */
    public void stopRecording() {
        // 先标记为非录音状态，防止并发问题
        isRecording.set(false);
        
        if (audioRecord != null) {
            try {
                if (audioRecord.getRecordingState() == AudioRecord.RECORDSTATE_RECORDING) {
                    audioRecord.stop();
                }
            } catch (Exception e) {
                Log.e(TAG, "停止录音异常", e);
            }
        }
    }
    
    /**
     * 播放来自对讲机的音频数据
     * @param audioData 接收到的音频数据
     */
    @Override
    public void onAudioDataReceived(byte[] audioData) {
        if (audioData == null || audioData.length == 0) {
            return;
        }
        
        if (audioTrack == null) {
            if (!initPlayer()) {
                return;
            }
        }
        
        // 确保播放器已启动
        if (audioTrack.getPlayState() != AudioTrack.PLAYSTATE_PLAYING) {
            try {
                audioTrack.play();
                isPlaying.set(true);
            } catch (Exception e) {
                Log.e(TAG, "开始播放失败", e);
                String errorMsg = context != null ? 
                        context.getString(R.string.error_start_playback, e.getMessage()) : 
                        "Failed to start playback: " + e.getMessage();
                notifyError(errorMsg);
                return;
            }
        }
        
        // 写入数据进行播放
        try {
            audioTrack.write(audioData, 0, audioData.length);
            
            // 计算并通知音频级别
            int audioLevel = calculateAudioLevel(audioData, audioData.length);
            notifyAudioLevel(audioLevel);
        } catch (Exception e) {
            Log.e(TAG, "播放音频数据失败", e);
            String errorMsg = context != null ? 
                    context.getString(R.string.error_audio_playback, e.getMessage()) : 
                    "Failed to play audio data: " + e.getMessage();
            notifyError(errorMsg);
        }
    }
    
    /**
     * 停止播放
     */
    public void stopPlaying() {
        // 先标记为非播放状态，防止并发问题
        isPlaying.set(false);
        
        if (audioTrack != null) {
            try {
                if (audioTrack.getPlayState() == AudioTrack.PLAYSTATE_PLAYING) {
                    audioTrack.pause();
                    audioTrack.flush();
                }
            } catch (Exception e) {
                Log.e(TAG, "停止播放异常", e);
            }
        }
    }
    
    /**
     * 获取是否已经初始化
     */
    public boolean isInitialized() {
        return audioRecord != null && audioTrack != null;
    }
    
    /**
     * 释放资源
     */
    public void release() {
        stopRecording();
        stopPlaying();
        
        if (audioRecord != null) {
            try {
                audioRecord.release();
            } catch (Exception e) {
                Log.e(TAG, "释放AudioRecord异常", e);
            } finally {
                audioRecord = null;
            }
        }
        
        if (audioTrack != null) {
            try {
                audioTrack.release();
            } catch (Exception e) {
                Log.e(TAG, "释放AudioTrack异常", e);
            } finally {
                audioTrack = null;
            }
        }
        
        if (intercomManager != null) {
            intercomManager.removeAudioDataListener(this);
        }
        
        audioExecutor.shutdown();
        playbackExecutor.shutdown();
    }
    
    /**
     * 处理音频数据
     * 实现音频数据处理和优化，以适合对讲机传输
     */
    private byte[] processAudioData(byte[] rawData, int bytesRead) {
        // 这里可以添加音频处理算法，如噪声抑制、音量放大、动态范围压缩等
        // 简单示例：直接返回原始数据，实际应用中可能需要更复杂的处理
        
        // 如果需要处理大量数据，可以考虑使用JNI调用C++代码进行高性能处理
        // 或者使用Android的音频效果API
        
        byte[] processedData = new byte[bytesRead];
        System.arraycopy(rawData, 0, processedData, 0, bytesRead);
        return processedData;
    }
    
    /**
     * 计算音频级别
     * @return 0-100的音量等级
     */
    private int calculateAudioLevel(byte[] buffer, int bytesRead) {
        // 简单计算音量等级
        long sum = 0;
        for (int i = 0; i < bytesRead; i += 2) {
            if (i + 1 < bytesRead) {
                short sample = (short) ((buffer[i] & 0xFF) | ((buffer[i + 1] & 0xFF) << 8));
                sum += Math.abs(sample);
            }
        }
        
        // 计算平均值并映射到0-100
        int sampleCount = bytesRead / 2;
        if (sampleCount > 0) {
            double average = sum / (double) sampleCount;
            int level = (int) (average * 100 / 32768); // 16位音频最大值为32768
            return Math.min(100, level);
        }
        return 0;
    }
    
    /**
     * 通知音频等级变化
     */
    private void notifyAudioLevel(int level) {
        if (listener != null) {
            listener.onAudioLevelChanged(level);
        }
    }
    
    /**
     * 通知错误
     */
    private void notifyError(String errorMessage) {
        Log.e(TAG, errorMessage);
        if (listener != null) {
            listener.onError(errorMessage);
        }
    }
    
    /**
     * 是否正在录音
     */
    public boolean isRecording() {
        return isRecording.get();
    }
    
    /**
     * 是否正在播放
     */
    public boolean isPlaying() {
        return isPlaying.get();
    }
} 