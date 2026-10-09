package com.tonikelope.coronapoker.iosvalidation;

import java.io.*;
import java.net.*;
import java.util.concurrent.*;

/** Real BSD sockets: handshake timeout, subsequent idle read, and async close. */
public final class SocketRuntimeProbe {
    public static void main(String[] args) throws Exception {
        ExecutorService workers=Executors.newCachedThreadPool();
        try (ServerSocket listener=new ServerSocket(0)) {
            Future<?> client=workers.submit(() -> {
                try (Socket socket=new Socket("127.0.0.1",listener.getLocalPort())) {
                    socket.getOutputStream().write(17);
                    if (socket.getInputStream().read()!=18) throw new AssertionError("Missing server acknowledgement");
                    Thread.sleep(250);
                    socket.getOutputStream().write(19);
                    if (socket.getInputStream().read()!=20) throw new AssertionError("Missing second acknowledgement");
                    Thread.sleep(1000);
                } catch (Exception failure) { throw new RuntimeException(failure); }
            });
            try (Socket accepted=listener.accept()) {
                accepted.setSoTimeout(1000);
                if (accepted.getInputStream().read()!=17) throw new AssertionError("Handshake read failed");
                accepted.setSoTimeout(0);
                long begin=System.nanoTime();accepted.getOutputStream().write(18);
                if (accepted.getInputStream().read()!=19 || System.nanoTime()-begin<100_000_000)
                    throw new AssertionError("Blocking read after handshake failed");
                accepted.getOutputStream().write(20);
                accepted.setSoTimeout(100);
                try { accepted.getInputStream().read(); throw new AssertionError("Positive timeout was ignored"); }
                catch (SocketTimeoutException expected) { }
                accepted.setSoTimeout(0);
                CountDownLatch reading=new CountDownLatch(1);
                Future<Boolean> blocked=workers.submit(() -> {
                    reading.countDown();
                    try { return accepted.getInputStream().read()==-1; }
                    catch (SocketException expected) { return true; }
                });
                reading.await(2,TimeUnit.SECONDS);Thread.sleep(50);accepted.close();
                if (!blocked.get(2,TimeUnit.SECONDS)) throw new AssertionError("Close did not unblock read");
            }
            client.get(3,TimeUnit.SECONDS);
            System.out.println("PASS native BSD socket: timed handshake, zero-timeout delayed read, positive read timeout, asynchronous close");
        } finally { workers.shutdownNow(); }
    }
}
