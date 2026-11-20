"""Keep the shipped web applications and game actions, replace the CEF transport on iOS."""
import re


def span(source, start, end):
    a = source.index(start)
    return source[a:source.index(end, a)]


def shared(source):
    source = re.sub(r"^import (?:org\.cef\.|java\.awt\.|javax\.imageio\.).+;\n", "", source, flags=re.M)
    return source.replace("implements AutoCloseable {", "extends IOSWebPanel {")


def stats(source):
    s = shared(source)
    header = s[:s.index("    private final PokerVideoFrameBuffer")]
    fields = span(s, "    private final ExecutorService", "    private volatile boolean closed")
    data = span(s, "    private StatsDashboard snapshot", "    private final GdxBrowserKeyboard")
    constructor = span(s, "    PokerStatsBrowser(", "    private void open(")
    constructor = constructor.replace("        this.width = width;\n        this.height = height;\n", "")
    constructor = constructor[:constructor.index("        double scale =")] + "        worker.execute(() -> open(language));\n    }\n"
    load = span(s, "            Map<String, Asset> assets", "            if (closed) return;")
    load = load.replace("pageUrl, asset(", 'ORIGIN + ROOT + "index.html", asset(')
    transport = """
            attach(new IOSWebOrigin(pageUrl.substring(ORIGIN.length()), new IOSWebOrigin.Content() {
                public IOSWebOrigin.Asset get(String path) {
                    Asset a = assets.get(ORIGIN + path);
                    if (a == null) return null;
                    return path.endsWith("/index.html") ? IOSWebOrigin.bridgePage(a.bytes)
                            : new IOSWebOrigin.Asset(a.bytes,a.mimeType);
                }
                public void query(String request, IOSWebOrigin.Reply reply) {
                    switch (request) {
                        case "ready" -> { ready = true; reply.success(""); }
                        case "broken" -> { failed = true; reply.success(""); }
                        default -> worker.execute(() -> answer(request,reply));
                    }
                }
            }, CSP + "; connect-src 'self'", 65), "");
        } catch (Exception ex) { failed=true; LOG.log(Level.WARNING,"Could not open iPad statistics",ex); }
    }
"""
    return (header + fields + "    private volatile Selection selection = new Selection(0,0,\"\",false);\n"
            + data + constructor + "    private void open(String language) {\n        try {\n" + load + transport
            + span(s, "    private void answer(", "    boolean failed()").replace("CefQueryCallback", "IOSWebOrigin.Reply")
            + "    Selection selection() { return selection; }\n"
            + "    @Override public void close() { super.close(); worker.shutdownNow(); }\n"
            + span(s, "    record Selection(", "    private static final class AssetHandler") + "}\n")


def windows(name, source):
    source = source.replace("PokerChromium.available()", "PokerVideoPlatformSupport.isAvailable()")
    if name == "GdxRobertRulesWindow":
        source = source.replace("Texture pdf = failed ? null : browser.texture(browserWidth, browserHeight, browserScale);",
                                "boolean visible = !failed && browser.present(viewport, web, opacity / 100f);")
        source = source.replace("if (pdf == null)", "if (!visible)").replace(
            "if (pdf != null) {\n            drawPdf(batch, pdf, web);\n        } else {", "if (!visible) {")
    else:
        source = source.replace("Texture frame = browser.failed() ? null : browser.texture(renderWidth, renderHeight);",
                                "boolean visible = !browser.failed() && browser.present(viewport, web, 1f);")
        start = source.index("        if (frame != null) {")
        end = source.index("            String message =", start)
        source = source[:start] + "        if (!visible) {\n" + source[end:]
    return source


def frontend(source):
    start = source.index("        Texture rendered = statsBrowser.texture(")
    end = source.index("        Texture spade =", start)
    source = source[:start] + "        if (statsBrowserVisible() && statsBrowser.present(viewport,bounds,1f)) return;\n" + source[end:]
    source = re.sub(r" *PokerChromium\.(useLanguage|shutdown)\(.*?;\n", "", source)
    return source


def gallery(source):
    s = shared(source)
    header = s[:s.index("    private final PokerVideoFrameBuffer")]
    header = header.replace("SEARCH_TIMEOUT = TimeUnit.SECONDS.toNanos(17)", "SEARCH_TIMEOUT = TimeUnit.SECONDS.toNanos(55)")
    workers = span(s, "    private final ScheduledThreadPoolExecutor worker", "    private final long started")
    fields = span(s, "    private int selectedSearch;", "    private CefClient client")
    constructor = span(s, "    PokerImageGalleryBrowser(", "        LibGdxCefBrowser current = browser;")
    constructor = constructor.replace("        this.width = width;\n        this.height = height;\n", "").replace(
        "double scale = scale();\n        worker.execute(() -> open(width, height, scale, language));",
        "worker.execute(() -> open(language));")
    setup = span(s, "            Map<String, Asset> assets", "            if (closed) return;")
    dispatch = span(s, '                if ("ready".equals(request))', '                return true;\n            }\n        }, true);')
    begin = span(s, "    private synchronized void beginSearch", "    private void failProviderLoad").replace(" || client == null", "")
    tabs = span(s, "    private synchronized void searchTabAction", "        try {\n            if (google == null)")
    polling = """
        openSearch(owner,url);
        pollDocument(search,300);
    }
    private void pollDocument(Search search,long delay) {
        worker.schedule(() -> {
            if (closed || activeSearch != search) return;
            if (System.nanoTime() >= search.deadline()) { failSearch(search,SearchFailure.UNAVAILABLE); return; }
            String result = searchDocument(owner);
            if (result == null) pollDocument(search,350);
            else if (result.startsWith("html:")) readDocument(search,result.substring(5));
            else if (result.startsWith("rate:")) reportRateLimit(search,result.substring(5));
            else failSearch(search,switch (result) {
                case "consent" -> SearchFailure.CONSENT;
                case "unusual" -> SearchFailure.UNUSUAL_TRAFFIC;
                default -> SearchFailure.UNAVAILABLE;
            });
        },delay,TimeUnit.MILLISECONDS);
    }
"""
    preview = span(s, "    private CefResourceHandler previewHandler(", "    private static Asset previewAsset").replace(
            "CefResourceHandler previewHandler(String requestUrl)", "Asset previewHandler(String requestUrl) throws IOException").replace(
            "new PreviewHandler(", "preview(")
    return (header + workers + "    private volatile Search activeSearch;\n" + fields + constructor
        + '        if (ready) evaluate(owner,"window.refreshLocalGallery?.()");\n    }\n'
        + "    private void open(String language) {\n        try {\n" + setup
        + """
            attach(new IOSWebOrigin(ROOT+"index.html",new IOSWebOrigin.Content() {
                public IOSWebOrigin.Asset get(String path) throws Exception {
                    Asset a = assets.get(ORIGIN + path);
                    if (a == null) a = previewHandler(ORIGIN + path);
                    if (a == null) return null;
                    return path.equals(ROOT+"index.html") ? IOSWebOrigin.bridgePage(a.bytes)
                        : new IOSWebOrigin.Asset(a.bytes,a.mimeType);
                }
                public void query(String request,IOSWebOrigin.Reply reply) {
""" + dispatch + """
                }
            }, CSP + "; connect-src 'self'",170),"");
        } catch (Exception e) { failed=true; LOG.log(Level.WARNING,"Could not open image gallery",e); }
    }
    private static native void openSearch(long owner,String url);
    private static native String searchDocument(long owner);
    private static native void closeSearch(long owner);
"""
        + span(s, "    private void imageCacheAction", "    private CefResourceRequestHandler providerResponse")
        + span(s, "    private void reportRateLimit", "    private static boolean unusedProviderResource")
        + span(s, "    private void galleryAction", "    private CefResourceHandler previewHandler")
        + preview + """
    private static Asset preview(String url,boolean search) throws IOException {
        byte[] data = search ? GdxChatImageLoader.downloadSearchPreview(url) : GdxChatImageLoader.download(url);
        if (GdxChatImageLoader.isGif(data)) return new Asset(data,"image/gif");
        if (GdxChatImageLoader.isWebp(data)) return new Asset(data,"image/webp");
        byte[] thumbnail = thumbnail(data);
        return thumbnail == null ? null : new Asset(thumbnail,"image/png");
    }
    private static native byte[] thumbnail(byte[] data);
""" + begin + tabs.replace(" || client == null", "") + polling + span(s, "    private void readDocument", "        navigatingSearch = null;")
        + "        closeSearch(owner);\n" + span(s,"        return PROVIDER_GATE.finish", "    boolean failed()")
        + span(s, "    private record Search(", "    Texture texture(")
        + """    @Override public synchronized void close() {
        if (closed) return;
        super.close(); stopProviderSearch(0); downloading.clear(); worker.shutdownNow();
    }
""" + span(s, "    private record Asset(", "    private static class AssetHandler")
        + span(s, "    private enum SearchFailure", "    private final class PreviewHandler") + "}\n").replace("CefQueryCallback","IOSWebOrigin.Reply")
