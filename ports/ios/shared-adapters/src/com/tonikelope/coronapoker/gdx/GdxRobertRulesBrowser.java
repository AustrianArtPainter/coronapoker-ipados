package com.tonikelope.coronapoker.gdx;

import com.badlogic.gdx.Gdx;
import java.util.HashMap;
import java.util.Map;
import java.util.logging.Level;
import java.util.logging.Logger;

/** The bundled PDF.js viewer, both rulebooks and the current game language. */
final class GdxRobertRulesBrowser extends IOSWebPanel {
    private volatile boolean selectionMenu;
    GdxRobertRulesBrowser(int width, int height, double scale, String language) {
        Thread loader = new Thread(() -> open(language), "iPad-rules");
        loader.setDaemon(true);
        loader.start();
    }
    private void open(String language) {
        try {
            Map<String,byte[]> assets = new HashMap<>(GdxRobertRulesAssets.load());
            assets.putAll(GdxWebLocalization.fonts(language));
            assets.put(GdxWebLocalization.COPY_PATH,GdxWebLocalization.script(language,"gdx.web.rules_"));
            String path = GdxRobertRulesAssets.initialUrl(language).substring(GdxRobertRulesAssets.ORIGIN.length());
            IOSWebOrigin server = new IOSWebOrigin(path, new IOSWebOrigin.Content() {
                public IOSWebOrigin.Asset get(String path) {
                    byte[] data = assets.get(path);
                    if (data == null) return null;
                    return GdxRobertRulesAssets.VIEWER.equals(path) ? IOSWebOrigin.bridgePage(data)
                        : new IOSWebOrigin.Asset(data,GdxRobertRulesAssets.mimeType(path));
                }
                public void query(String request, IOSWebOrigin.Reply reply) {
                    if (request.equals("menu:open") || request.equals("menu:close")) {
                        selectionMenu = request.equals("menu:open");
                        reply.success("");
                    } else if (request.startsWith("copy:") && !request.substring(5).isBlank()) {
                        Gdx.app.postRunnable(() -> {
                            if (closed) return;
                            try { Gdx.app.getClipboard().setContents(request.substring(5)); reply.success(""); }
                            catch (RuntimeException e) { reply.failure(500,"Clipboard unavailable"); }
                        });
                    } else reply.failure(400,"Unknown rulebook action");
                }
            }, "default-src 'self' blob: data:; script-src 'self' 'wasm-unsafe-eval' blob:; style-src 'self' 'unsafe-inline'; object-src 'none'; frame-ancestors 'none'",262149);
            attach(server,"#zoom=page-width");
            ready=true;
        } catch (Exception e) {
            failed=true;
            Logger.getLogger(getClass().getName()).log(Level.WARNING,"Could not open rulebook",e);
        }
    }
    boolean dismissSelectionMenu() {
        if (!selectionMenu) return false;
        selectionMenu=false;
        evaluate(owner,"window.dispatchEvent(new Event('rules-dismiss-selection-menu'))");
        return true;
    }
}
