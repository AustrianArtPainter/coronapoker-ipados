package com.tonikelope.coronapoker.ios;

import com.tonikelope.coronapoker.core.audio.VoiceWavContract;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;

/** Preserve the desktop 16 kHz mono G.711 mu-law voice wire format. */
public final class PortableWave {
    private record Header(int format, int channels, int rate, int bits, int offset, int bytes, int byteRate) {}

    private static Header inspect(byte[] data) throws IOException {
        if (data == null || data.length < 44 || !ascii(data, 0, "RIFF") || !ascii(data, 8, "WAVE"))
            throw new IOException("Invalid WAVE signature");
        long riffSize = u32(data, 4) + 8;
        if (riffSize != data.length) throw new IOException("WAVE length mismatch");
        int format = 0, channels = 0, rate = 0, bits = 0, byteRate = 0, payload = -1, size = -1;
        boolean fmt = false;
        long cursor = 12;
        while (cursor < data.length) {
            if (cursor + 8 > data.length) throw new IOException("Truncated WAVE chunk");
            int start = (int) cursor;
            long length = u32(data, start + 4);
            long end = cursor + 8 + length;
            if (end + (length & 1) > data.length) throw new IOException("WAVE chunk exceeds payload");
            if (ascii(data, start, "fmt ")) {
                if (fmt || length < 16) throw new IOException("Invalid WAVE format chunk");
                fmt = true;
                format = u16(data, start + 8); channels = u16(data, start + 10);
                rate = (int) u32(data, start + 12); byteRate = (int) u32(data, start + 16); bits = u16(data, start + 22);
            } else if (ascii(data, start, "data")) {
                if (payload != -1 || !fmt) throw new IOException("Invalid WAVE data chunk");
                payload = start + 8; size = (int) length;
            }
            cursor = end + (length & 1);
        }
        if (!fmt || payload < 0 || byteRate <= 0 || rate <= 0) throw new IOException("Incomplete WAVE metadata");
        return new Header(format, channels, rate, bits, payload, size, byteRate);
    }
    public static float durationSeconds(byte[] data, float fallback) {
        try { Header header = inspect(data); return header.bytes() / (float) header.byteRate(); }
        catch (Exception failure) { return fallback; }
    }
    public static byte[] pcmFromWave(byte[] wav) throws IOException {
        Header h = inspect(wav);
        if (h.format() != 1 || h.rate() != 16000 || h.channels() != 1 || h.bits() != 16 || h.bytes() % 2 != 0)
            throw new IOException("Expected 16 kHz mono signed 16-bit PCM");
        return Arrays.copyOfRange(wav, h.offset(), h.offset() + h.bytes());
    }
    public static byte[] encodePcm(byte[] pcm) throws IOException {
        if (pcm == null || pcm.length < 3200 || pcm.length > 480000 || pcm.length % 2 != 0)
            throw new IOException("PCM voice payload outside desktop limits");
        byte[] encoded = new byte[pcm.length / 2];
        for (int i = 0; i < encoded.length; i++) {
            int sample = (short) ((pcm[i * 2] & 255) | pcm[i * 2 + 1] << 8);
            encoded[i] = muLaw(sample);
        }
        byte[] wav = wave(encoded, 7, 8);
        String error = VoiceWavContract.validationError(wav);
        if (error != null) throw new IOException(error);
        return wav;
    }
    public static byte[] decodePcmWave(byte[] wav) throws IOException {
        if (!VoiceWavContract.isValid(wav)) throw new IOException("Invalid CoronaPoker voice-note WAV");
        Header h = inspect(wav);
        byte[] pcm = new byte[h.bytes() * 2];
        for (int i = 0; i < h.bytes(); i++) {
            int value = (~wav[h.offset() + i]) & 255;
            int sample = ((value & 15) << 3) + 132;
            sample <<= (value & 112) >>> 4;
            sample = (value & 128) != 0 ? 132 - sample : sample - 132;
            pcm[i * 2] = (byte) sample; pcm[i * 2 + 1] = (byte) (sample >>> 8);
        }
        return wave(pcm, 1, 16);
    }
    private static byte muLaw(int sample) {
        int mask;
        // Match Java Sound's signed-short arithmetic, including full-scale
        // overflow. The unmodified desktop recorder uses this exact mapping.
        if (sample < 0) { sample = (short) (132 - sample); mask = 127; }
        else { sample = (short) (sample + 132); mask = 255; }
        int segment = 0;
        while (segment < 8 && sample > ((255 << segment) | ((1 << segment) - 1))) segment++;
        if (segment >= 8) return (byte) (127 ^ mask);
        return (byte) (((segment << 4) | ((sample >> (segment + 3)) & 15)) ^ mask);
    }
    private static byte[] wave(byte[] samples, int encoding, int bits) {
        int padded = samples.length + (samples.length & 1);
        ByteBuffer output = ByteBuffer.allocate(44 + padded).order(ByteOrder.LITTLE_ENDIAN);
        output.put("RIFF".getBytes(StandardCharsets.US_ASCII)).putInt(36 + padded).put("WAVEfmt ".getBytes(StandardCharsets.US_ASCII));
        output.putInt(16).putShort((short) encoding).putShort((short) 1).putInt(16000).putInt(16000 * bits / 8);
        output.putShort((short) (bits / 8)).putShort((short) bits).put("data".getBytes(StandardCharsets.US_ASCII)).putInt(samples.length).put(samples);
        return output.array();
    }
    private static int u16(byte[] data, int offset) { return (data[offset] & 255) | (data[offset + 1] & 255) << 8; }
    private static long u32(byte[] data, int offset) { return u16(data, offset) | (long) u16(data, offset + 2) << 16; }
    private static boolean ascii(byte[] data, int offset, String text) {
        if (offset + text.length() > data.length) return false;
        for (int i = 0; i < text.length(); i++) if (data[offset + i] != text.charAt(i)) return false;
        return true;
    }
    private PortableWave() {}
}
