package com.example.hachimophone;

import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.File;
import java.io.FileOutputStream;

/** PCM（16 bit 单声道）套 WAV 头：PhoneVoice（内存）与 DeviceActivity（落盘）共用，别再各写一份 */
final class WavUtil {

    private WavUtil() {
    }

    /** 44 字节 WAV 头 + PCM 数据，失败返回空数组（调用方自行处理） */
    static byte[] wavBytes(byte[] pcm, int sampleRate) {
        try {
            ByteArrayOutputStream out = new ByteArrayOutputStream(44 + pcm.length);
            DataOutputStream d = new DataOutputStream(out);
            d.writeBytes("RIFF");
            d.writeInt(Integer.reverseBytes(36 + pcm.length));
            d.writeBytes("WAVE");
            d.writeBytes("fmt ");
            d.writeInt(Integer.reverseBytes(16));
            d.writeShort(Short.reverseBytes((short) 1));
            d.writeShort(Short.reverseBytes((short) 1));
            d.writeInt(Integer.reverseBytes(sampleRate));
            d.writeInt(Integer.reverseBytes(sampleRate * 2));
            d.writeShort(Short.reverseBytes((short) 2));
            d.writeShort(Short.reverseBytes((short) 16));
            d.writeBytes("data");
            d.writeInt(Integer.reverseBytes(pcm.length));
            d.write(pcm);
            d.flush();
            return out.toByteArray();
        } catch (Exception e) {
            return new byte[0];
        }
    }

    /** 同 {@link #wavBytes(byte[], int)}，结果直接写入文件（交给 MediaPlayer 播放） */
    static void writeWav(File file, byte[] pcm, int sampleRate) throws Exception {
        try (FileOutputStream fo = new FileOutputStream(file)) {
            fo.write(wavBytes(pcm, sampleRate));
        }
    }
}
