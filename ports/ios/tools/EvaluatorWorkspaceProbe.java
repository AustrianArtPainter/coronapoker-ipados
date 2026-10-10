package org.alberta.poker;

import java.util.Arrays;
import java.util.SplittableRandom;
import java.util.concurrent.*;

/** Rank parity with the unmodified desktop oracle, including two evaluation threads. */
public final class EvaluatorWorkspaceProbe {
    private static void same(Hand hand) {
        int expected;
        try { expected = DesktopRankOracle.rankHand_Java(hand); }
        catch (RuntimeException originalFailure) {
            try { HandEvaluator.rankHand_Java(hand); }
            catch (RuntimeException mobileFailure) {
                if (mobileFailure.getClass() == originalFailure.getClass()) return;
                throw new AssertionError("Exception differs for " + Arrays.toString(hand.getCardArray()), mobileFailure);
            }
            throw new AssertionError("Mobile accepted a hand rejected by desktop: " + Arrays.toString(hand.getCardArray()));
        }
        int actual = HandEvaluator.rankHand_Java(hand);
        if (actual != expected) throw new AssertionError(Arrays.toString(hand.getCardArray())
                + ": " + actual + " != " + expected);
    }
    private static int random(long seed, int count) {
        SplittableRandom random = new SplittableRandom(seed);
        Hand hand = new Hand();
        boolean[] used = new boolean[52];
        for (int test = 0; test < count; test++) {
            hand.makeEmpty(); Arrays.fill(used, false);
            int size = 1 + random.nextInt(7);
            while (hand.size() < size) {
                int card = random.nextInt(52);
                if (!used[card]) { used[card] = true; hand.addCard(card); }
            }
            same(hand);
        }
        return count;
    }
    public static void main(String[] args) throws Exception {
        Hand hand = new Hand();
        int[] cards = hand.getCardArray();
        cards[0] = 5;
        int count = 0;
        for (int a=0;a<48;a++) for(int b=a+1;b<49;b++) for(int c=b+1;c<50;c++)
            for(int d=c+1;d<51;d++) for(int e=d+1;e<52;e++) {
                cards[1]=a;cards[2]=b;cards[3]=c;cards[4]=d;cards[5]=e;
                same(hand);count++;
            }
        same(new Hand());
        count += random(25479, 100_000);
        ExecutorService threads = Executors.newFixedThreadPool(2);
        try {
            Future<Integer> one=threads.submit(() -> random(501, 100_000));
            Future<Integer> two=threads.submit(() -> random(502, 100_000));
            count += one.get() + two.get();
        } finally { threads.shutdownNow(); }
        System.out.println("PASS " + count + " desktop outcome comparisons; exhaustive five-card ranks, 1–7-card outcomes (including upstream exceptions) and two-thread workspace isolation");
    }
}
