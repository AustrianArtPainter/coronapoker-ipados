package com.tonikelope.coronapoker.gdx;

import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.*;

/** A private HTTP origin for one WebKit document, its workers and its game actions. */
final class IOSWebOrigin implements AutoCloseable {
    record Asset(byte[] bytes, String mime) {
        static Asset text(String value, String mime) {
            return new Asset(value.getBytes(StandardCharsets.UTF_8), mime);
        }
    }
    interface Content {
        Asset get(String path) throws Exception;
        void query(String request, Reply reply);
    }
    interface Reply {
        void success(String result);
        void failure(int code, String reason);
    }
    private final ServerSocket listener;
    private final String host;
    private final String secret = UUID.randomUUID().toString();
    private final String cookie = "session=" + secret;
    private final String document;
    private final String initialPath;
    private final Content content;
    private final String policy;
    private final Set<Socket> active = ConcurrentHashMap.newKeySet();
    private final ThreadPoolExecutor requests = new ThreadPoolExecutor(4, 4, 0, TimeUnit.SECONDS,
            new ArrayBlockingQueue<>(32), task -> daemon(task, "iPad-web-request"));
    private final int queryLimit;

    IOSWebOrigin(String page, Content content, String policy, int queryLimit) throws IOException {
        this.content = content;
        this.policy = policy;
        this.queryLimit = queryLimit;
        this.document = URI.create(page).getPath();
        this.initialPath = page.split("#", 2)[0] + (page.contains("?") ? "&" : "?") + "session=" + secret;
        listener = new ServerSocket(0, 16, InetAddress.getByName("127.0.0.1"));
        host = "127.0.0.1:" + listener.getLocalPort();
        daemon(this::accept, "iPad-web-origin").start();
    }
    String entry(String fragment) { return "http://" + host + initialPath + fragment; }

    private static Thread daemon(Runnable task, String name) {
        Thread t = new Thread(task, name);
        t.setDaemon(true);
        return t;
    }
    private void accept() {
        while (!listener.isClosed()) {
            try {
                Socket socket = listener.accept();
                active.add(socket);
                try {
                    requests.execute(() -> handle(socket));
                } catch (RejectedExecutionException full) {
                    active.remove(socket);
                    socket.close();
                }
            } catch (IOException stopped) {
                close();
            }
        }
    }
    private static String line(InputStream in) throws IOException {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        while (bytes.size() < 8192) {
            int b = in.read();
            if (b < 0) throw new EOFException();
            if (b == '\r') {
                if (in.read() != '\n') throw new IOException("Invalid HTTP header");
                return bytes.toString("US-ASCII");
            }
            if (b < 32 && b != 9) throw new IOException("Invalid HTTP header");
            bytes.write(b);
        }
        throw new IOException("HTTP header too long");
    }
    private void handle(Socket socket) {
        try (socket) {
            socket.setSoTimeout(8000);
            InputStream in = new BufferedInputStream(socket.getInputStream());
            OutputStream out = socket.getOutputStream();
            String[] request = line(in).split(" ", -1);
            if (request.length != 3) return;
            Map<String,String> headers = new HashMap<>();
            for (int n=0; n<64; n++) {
                String h = line(in);
                if (h.isEmpty()) break;
                int colon = h.indexOf(':');
                if (colon < 1 || n == 63) return;
                headers.put(h.substring(0,colon).toLowerCase(java.util.Locale.ROOT),h.substring(colon+1).trim());
            }
            boolean first = request[0].equals("GET") && request[1].equals(initialPath);
            if (!host.equals(headers.get("host")) || !first && !cookie.equals(headers.get("cookie"))) {
                respond(out,403,null,false); return;
            }
            URI uri = URI.create(request[1]);
            if (uri.isAbsolute() || uri.getAuthority()!=null) { respond(out,400,null,false); return; }
            if (request[0].equals("POST") && "/game-query".equals(uri.getPath())) {
                if (!("http://" + host).equals(headers.get("origin")) || !"1".equals(headers.get("x-game-query"))) {
                    respond(out,403,null,false); return;
                }
                int length = Integer.parseInt(headers.getOrDefault("content-length","-1"));
                if (length < 0 || length > 4L*queryLimit) { respond(out,413,null,false); return; }
                byte[] bytes = new byte[length];
                new DataInputStream(in).readFully(bytes);
                String value = new String(bytes, StandardCharsets.UTF_8);
                if (value.length()>queryLimit) { respond(out,413,null,false); return; }
                record Result(int code,String body) {}
                var future = new CompletableFuture<Result>();
                content.query(value, new Reply() {
                    public void success(String result) { future.complete(new Result(200,result)); }
                    public void failure(int code,String reason) { future.complete(new Result(code,reason)); }
                });
                Result result = future.get(document.startsWith("/image-search/") ? 60 : 32,TimeUnit.SECONDS);
                respond(out,result.code(),Asset.text(result.body(),"text/plain; charset=utf-8"),false);
            } else if (request[0].equals("GET")) {
                Asset asset;
                try {
                    asset = uri.getPath().equals("/game-bridge.js") ? BRIDGE : content.get(uri.getPath());
                } catch (Exception failed) { respond(out,500,null,false); return; }
                respond(out,asset == null ? 404 : 200,asset,first && document.equals(uri.getPath()));
            } else respond(out,405,null,false);
        } catch (Exception disconnected) {
            if (disconnected instanceof InterruptedException) Thread.currentThread().interrupt();
        } finally { active.remove(socket); }
    }
    private void respond(OutputStream out, int code, Asset asset, boolean first) throws IOException {
        byte[] bytes = asset == null ? new byte[0] : asset.bytes();
        String headers = "HTTP/1.1 " + code + " " + (code==200 ? "OK":"Error")
            + "\r\nConnection: close\r\nCache-Control: no-store\r\nReferrer-Policy: no-referrer"
            + "\r\nX-Content-Type-Options: nosniff\r\nContent-Security-Policy: " + policy
            + (first ? "\r\nSet-Cookie: " + cookie + "; Path=/; HttpOnly; SameSite=Strict" : "")
            + "\r\nContent-Type: " + (asset == null ? "text/plain" : asset.mime())
            + "\r\nContent-Length: " + bytes.length + "\r\n\r\n";
        out.write(headers.getBytes(StandardCharsets.US_ASCII));
        out.write(bytes);
    }
    static Asset bridgePage(byte[] html) {
        String page = new String(html,StandardCharsets.UTF_8);
        return Asset.text(page.replace("<head>", "<head><script src=\"/game-bridge.js\"></script>"),"text/html; charset=utf-8");
    }
    private static final Asset BRIDGE = Asset.text("""
            window.cefQuery = async ({request, onSuccess, onFailure}) => {
                try {
                    const r = await fetch('/game-query', {method:'POST',
                        headers:{'X-Game-Query':'1'}, body:request});
                    const body = await r.text();
                    if (r.ok) onSuccess?.(body); else onFailure?.(r.status,body);
                } catch(e) { onFailure?.(503,String(e)); }
            };
            ""","text/javascript");
    public void close() {
        try { listener.close(); } catch (IOException ignored) { }
        requests.shutdownNow();
        for (Socket socket : active) try { socket.close(); } catch (IOException ignored) { }
    }
}
