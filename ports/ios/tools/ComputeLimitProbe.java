import com.tonikelope.coronapoker.ios.IOSCompute;
import com.tonikelope.coronapoker.core.game.GameAsync;
import java.util.concurrent.*;

/** Concurrency/reentrancy, clock separation, and owner cancellation under saturated CPU admission. */
public final class ComputeLimitProbe {
    public static void main(String[] args) throws Exception {
        CountDownLatch occupied = new CountDownLatch(2), release = new CountDownLatch(1);
        Future<Integer> first = IOSCompute.submit(() -> {
            occupied.countDown(); release.await();
            return IOSCompute.call(() -> 11);
        });
        Future<Integer> second = IOSCompute.submit(() -> {
            occupied.countDown(); release.await();
            return IOSCompute.call(() -> 22);
        });
        require(occupied.await(3, TimeUnit.SECONDS));
        Future<Integer> third = IOSCompute.submit(() -> 33);
        require(!third.isDone() && IOSCompute.active() == 2 && IOSCompute.queued() == 1);
        GameAsync.OwnedAsync owner = GameAsync.owned("clock-probe");
        CountDownLatch clock = new CountDownLatch(1), waiting = new CountDownLatch(1);
        owner.execute(clock::countDown);
        require(clock.await(2, TimeUnit.SECONDS));
        Future<?> work = owner.execute(() -> {
            waiting.countDown();
            GameAsync.compute(() -> 44);
        });
        require(waiting.await(2, TimeUnit.SECONDS));
        owner.close();
        require(work.isDone());
        release.countDown();
        require(first.get(3, TimeUnit.SECONDS) == 11);
        require(second.get(3, TimeUnit.SECONDS) == 22);
        require(third.get(3, TimeUnit.SECONDS) == 33);
        require(IOSCompute.maxActive() == 2);
        boolean forwarded = false;
        try { IOSCompute.call(() -> { throw new IllegalArgumentException("invalid proof"); }); }
        catch (IllegalArgumentException expected) { forwarded = true; }
        require(forwarded);
        System.out.println("PASS compute limit=2, queued overflow work, nested calls, independent clock, owner cancellation, exception propagation");
    }
    private static void require(boolean condition) {
        if (!condition) throw new AssertionError("Compute scheduling invariant failed");
    }
}
