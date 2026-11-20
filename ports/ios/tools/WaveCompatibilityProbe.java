import com.tonikelope.coronapoker.ios.PortableWave;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.util.Arrays;
import javax.sound.sampled.*;

/** Compare every signed 16-bit PCM value with the original Java Sound codec. */
public final class WaveCompatibilityProbe {
    public static void main(String[] args) throws Exception {
        byte[] pcm = new byte[65536 * 2];
        for (int index = 0; index < 65536; index++) {
            int value = index - 32768;
            pcm[index * 2] = (byte) value; pcm[index * 2 + 1] = (byte) (value >>> 8);
        }
        AudioFormat input = new AudioFormat(AudioFormat.Encoding.PCM_SIGNED, 16000, 16, 1, 2, 16000, false);
        AudioFormat output = new AudioFormat(AudioFormat.Encoding.ULAW, 16000, 8, 1, 1, 16000, false);
        byte[] original;
        try (AudioInputStream source = new AudioInputStream(new ByteArrayInputStream(pcm), input, 65536);
             AudioInputStream encoded = AudioSystem.getAudioInputStream(output, source)) {
            original = encoded.readAllBytes();
        }
        byte[] mobileWave = PortableWave.encodePcm(pcm);
        byte[] mobile = Arrays.copyOfRange(mobileWave, 44, mobileWave.length);
        for (int i = 0; i < original.length; i++)
            if (original[i] != mobile[i]) throw new IllegalStateException("mu-law differs at PCM " + (i - 32768) + ": " + (original[i] & 255) + " vs " + (mobile[i] & 255));
        byte[] expected;
        try (AudioInputStream source = new AudioInputStream(new ByteArrayInputStream(original), output, original.length);
             AudioInputStream decoded = AudioSystem.getAudioInputStream(input, source)) {
            expected = decoded.readAllBytes();
        }
        byte[] nativePcm = PortableWave.pcmFromWave(PortableWave.decodePcmWave(mobileWave));
        if (!Arrays.equals(expected, nativePcm)) throw new IllegalStateException("mu-law decoding differs");
        System.out.println("{\"allSigned16BitValues\":65536,\"desktopMuLawEncodingExact\":true,\"desktopMuLawDecodingExact\":true,\"voiceWireContract\":true}");
    }
}
