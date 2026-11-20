"""Render the shared video overlay controls around a native, interactive WKWebView."""
import re


def prepare_overlay(source, replace_method):
    def between(text, start, end, replacement):
        first = text.index(start)
        last = text.index(end, first)
        return text[:first] + replacement + text[last:]

    source = source.replace("import java.awt.event.MouseEvent;\n", "").replace(
        "import org.cef.browser.LibGdxCefBrowser;\n", "")
    source = between(source, "    private final PokerVideoFrameBuffer", "    private final ExecutorService", "")
    source = between(source, "        if (current != null) {\n            String startupError",
                     "        if (current != null && !catalogLoading", "")
    source = between(source, "        if (current != null && !enabled &&", "        if (shouldRun &&",
                     "        if (current != null) current.setActive(shouldRun);\n        runtimeActive = shouldRun;\n")
    source = between(source, "                BrowserSize initialSize", "                // Once CEF",
                     "                PokerVideoRuntime created = new PokerVideoRuntime(videos);\n")
    source = between(source, "                // Once CEF", "                synchronized (initializationLock)", "")
    source = between(source, "                BrowserSize currentSize", "            } catch (InterruptedException", "")
    source = source.replace("loadingError = created.initializationError();", "loadingError = null;")
    source = re.sub(r'(created|current)\.releaseBrowserSession\(.*?\);', r'\1.setActive(false);', source)
    source = source.replace("current.browser() != null", "current.ready()")
    source = source.replace("        frameBuffer.close();\n", "").replace("        frameBuffer.dispose();\n", "")
    source = source.replace("        if (minimized) frameBuffer.dispose();", "        if (minimized && runtime != null) runtime.hide();")
    source = replace_method(source, "    void prepareFrame()", "")
    source = between(source, "        Texture texture = visibleVideoTexture();", "            boolean empty =",
                     """        PokerVideoRuntime player = runtime;
        if (player != null && player.ready() && !catalogLoading && loadingError == null && !emptyFavorites()) {
            player.present(viewport, videoBounds, videoAlpha());
        } else {
            if (player != null) player.hide();
""")
    source = source.replace("text.translate(catalogLoading || playerLoading == null ? loadingState : playerLoading)", "text.translate(loadingState)")
    source = replace_method(source, "    float videoBackdropAlpha()", "    float videoBackdropAlpha() { return videoAlpha(); }")
    source = replace_method(source, "    private Texture visibleVideoTexture()", "")
    source = between(source, "        Texture frame = frameBuffer.texture();", "        if (alpha < 1f)",
                     "        if (runtime != null) runtime.hide();\n")
    source = source.replace(" && (current == null || current.initializationError() == null)", "")
    source = source.replace("        if (current != null && current.documentLoadError() != null) releaseBrowserSession(true);", "")
    source = source.replace("if (current != null && current.documentLoadError() != null)", "if (current != null && !current.ready())")
    source = source.replace("current == null ? null : current.initializationError()", "null")
    source = between(source, "        BrowserSize size = browserSize();\n        current.resumeBrowserSession", "        runtimeActive = true;", "        current.setActive(true);\n")
    # WKWebView handles touches in its own content rectangle, leaving window
    # controls and geometry gestures with the original GDX capture owner.
    source = re.sub(r'            case VIDEO -> dispatchMouse\(.*?;','            case VIDEO -> { }', source, flags=re.S)
    source = re.sub(r'(?:if \(videoHovered\) )?dispatchMouse\((?:MouseEvent|over \?).*?;', '', source, flags=re.S)
    source = source.replace("                current.requestFocus(true);", "").replace("        if (current != null) current.requestFocus(false);","")
    for signature in ("    private void dispatchMouse(", "    private int modifiers()", "    private static int awtButton("):
        source = replace_method(source, signature, "")
    source = replace_method(source, "    boolean scrolled(",
        """    boolean scrolled(float x, float y, float amount) { return ownsHover(x, y); }""")
    return source


def prepare_search(source, replace_method):
    source = re.sub(r"^import java.net.http.[^;]+;\n", "", source, flags=re.M)
    source = replace_method(source, "    private static final class Http ", "")
    first = source.index("        HttpRequest request = HttpRequest.newBuilder(searchUri")
    end = source.index("        if (videos.isEmpty())", first)
    source = source[:first] + """        String body = fetchBody(searchUri(language, normalizedType, variant), MAX_RESPONSE_BYTES,
                15000, "text/html,application/xhtml+xml", acceptedLanguage + "," + fallbackLanguage + ";q=0.8");
        List<Video> videos = parse(body, normalizedType);
""" + source[end:]
    first = source.index("        HttpRequest request = HttpRequest.newBuilder(uri)")
    end = source.index("\n    }", first)
    source = source[:first] + '        return embedTitle(fetchBody(uri, 32 * 1024, 5000, "application/json", null));' + source[end:]
    source = replace_method(source, "    static HttpResponse.BodyHandler<String> boundedBodyHandler(", "")
    return replace_method(source, "    static String fetchBody(", """    private static String fetchBody(URI uri, int limit, int timeout, String accept, String language)
            throws IOException, InterruptedException {
        java.net.HttpURLConnection connection = (java.net.HttpURLConnection) uri.toURL().openConnection();
        try {
            connection.setConnectTimeout(Math.min(timeout, 8000));
            connection.setReadTimeout(timeout);
            connection.setRequestProperty("User-Agent", USER_AGENT);
            connection.setRequestProperty("Accept", accept);
            if (language != null) connection.setRequestProperty("Accept-Language", language);
            long deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(timeout);
            int status = connection.getResponseCode();
            if (status < 200 || status >= 300) throw new IOException("YouTube returned HTTP " + status);
            try (java.io.InputStream in = connection.getInputStream()) {
                ByteArrayOutputStream bytes = new ByteArrayOutputStream();
                byte[] chunk = new byte[8192];
                for (int count; (count = in.read(chunk)) != -1;) {
                    if (Thread.currentThread().isInterrupted()) throw new InterruptedException();
                    if (System.nanoTime() > deadline) throw new java.net.SocketTimeoutException();
                    if (count > limit - bytes.size()) throw new IOException("YouTube response exceeds size limit");
                    bytes.write(chunk, 0, count);
                }
                return bytes.toString("UTF-8");
            }
        } finally {
            connection.disconnect();
        }
    }""")
