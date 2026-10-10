import com.sun.management.ThreadMXBean;
import com.tonikelope.coronapoker.DeterministicShuffle;
import com.tonikelope.coronapoker.crypto.*;
import com.tonikelope.coronapoker.ios.IOSCompute;
import java.lang.management.ManagementFactory;
import java.util.Arrays;
import java.util.function.Supplier;

/** Whole business operations measured on their actual compute worker, not the waiting caller. */
public final class ApplicationAllocationProbe {
    static final ThreadMXBean BEAN = (ThreadMXBean) ManagementFactory.getThreadMXBean();
    static <T> T measure(String label, Supplier<T> action) {
        long thread = Thread.currentThread().getId(), before = BEAN.getThreadAllocatedBytes(thread);
        long start = System.nanoTime();
        T result = action.get();
        System.out.printf("%s bytes=%d millis=%.2f%n", label,
                BEAN.getThreadAllocatedBytes(thread) - before, (System.nanoTime() - start) / 1e6);
        return result;
    }
    public static void main(String[] args) {
        BEAN.setThreadAllocatedMemoryEnabled(true);
        IOSCompute.call(() -> {
            byte[] genesis = RistrettoSRA.getGenesisDeck();
            byte[] key = new byte[32];
            for (int i = 0; i < key.length; i++) key[i] = (byte) (37 + i * 3);
            key[31] &= 15;
            byte[] seed = new byte[48]; Arrays.fill(seed, (byte) 55);
            int[] permutation = DeterministicShuffle.shufflePermutation(52, seed);
            for (int cycle = 0; cycle < 3; cycle++) {
                byte[] locked = measure("cycle" + cycle + " deckLock52", () -> RistrettoSRA.applyCommutativeLock(genesis, key));
                byte[] shuffled = DeterministicShuffle.shuffleDeck(locked, seed);
                byte[] proof = measure("cycle" + cycle + " shuffleProve52", () -> ShuffleCascade.proveStepWire(genesis, shuffled, permutation, key));
                boolean valid = measure("cycle" + cycle + " shuffleVerify52", () -> ShuffleCascade.verifyStepWire(genesis, shuffled, proof));
                if (!valid) throw new AssertionError("Invalid shuffle proof");
            }
            return null;
        });
    }
}
