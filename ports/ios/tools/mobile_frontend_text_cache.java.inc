    // Each retained item owns its immutable colour snapshot. Eviction only
    // replaces a slot: this frame's text queue may still reference the item.
    private static final int MOBILE_MENU_TEXT_CAPACITY = 64;
    private final TextItem[] mobileMenuTextItems = new TextItem[MOBILE_MENU_TEXT_CAPACITY];
    private int mobileMenuTextCursor;

    private TextItem mobileTextItem(BitmapFont font, String value, float x, float y,
            Color color, boolean centered, boolean italic) {
        return mobileTextItem(font, value, x, y, color, centered, italic, 1f, false);
    }

    private TextItem mobileTextItem(BitmapFont font, String value, float x, float y,
            Color color, boolean centered, boolean italic, float scale) {
        return mobileTextItem(font, value, x, y, color, centered, italic, scale, false);
    }

    private TextItem mobileTextItem(BitmapFont font, String value, float x, float y,
            Color color, boolean centered, boolean italic, float scale, boolean pixelSnapped) {
        if (mobileDrawingMainMenu) {
            for (TextItem item : mobileMenuTextItems) {
                if (item != null && item.font == font && Objects.equals(item.text, value)
                        && mobileSameTextFloat(item.x, x) && mobileSameTextFloat(item.y, y)
                        && mobileSameTextFloat(item.scale, scale)
                        && mobileSameTextFloat(item.color.r, color.r)
                        && mobileSameTextFloat(item.color.g, color.g)
                        && mobileSameTextFloat(item.color.b, color.b)
                        && mobileSameTextFloat(item.color.a, color.a)
                        && item.centered == centered && item.italic == italic
                        && item.pixelSnapped == pixelSnapped) return item;
            }
        }
        TextItem item = new TextItem(font, value, x, y, new Color(color),
                centered, italic, scale, pixelSnapped);
        if (mobileDrawingMainMenu) {
            mobileMenuTextItems[mobileMenuTextCursor] = item;
            mobileMenuTextCursor = (mobileMenuTextCursor + 1) & (MOBILE_MENU_TEXT_CAPACITY - 1);
        }
        return item;
    }

    private static boolean mobileSameTextFloat(float first, float second) {
        return Float.floatToRawIntBits(first) == Float.floatToRawIntBits(second);
    }

    private void mobileClearMenuTextItems() {
        if (mobileMenuTextItems != null) java.util.Arrays.fill(mobileMenuTextItems, null);
        mobileMenuTextCursor = 0;
        if (mobileMenuHits != null) java.util.Arrays.fill(mobileMenuHits, null);
        mobileMenuHitCursor = 0;
        mobileMenuSoundBounds = null;
        mobileMenuVersionItem = null;
        mobileMenuVersionSettings = null;
        mobileMenuVersionLabel = null;
        mobileMasterVolumeSnapshot = null;
    }

    private final Runnable[] mobileMenuActions = new Runnable[10];
    private Runnable mobileMenuAction(int index) {
        Runnable result = mobileMenuActions[index];
        if (result == null) {
            switch (index) {
                case 0: result = () -> openNewGame(NewGameConnectionDraft.Mode.CREATE); break;
                case 1: result = () -> openNewGame(NewGameConnectionDraft.Mode.JOIN); break;
                case 2: result = this::openStats; break;
                case 3: result = this::openScreenshotViewer; break;
                case 4: result = this::openSettings; break;
                case 6: result = this::openAboutDialog; break;
                // Resolve the active application at invocation, never retain an old Gdx.app.
                case 7: result = this::requestApplicationExit; break;
                case 8: result = this::showUpdatePrompt; break;
                case 9: result = this::toggleMasterSound; break;
                default: throw new IllegalArgumentException("Unknown menu action");
            }
            mobileMenuActions[index] = result;
        }
        return result;
    }

    // Hits and their rectangles are read-only after construction. Eviction
    // never mutates a hit still referenced by pressedHit or this frame's map.
    private final Hit[] mobileMenuHits = new Hit[16];
    private int mobileMenuHitCursor;
    private Hit mobileMainMenuHit(float x, float y, float width, float height, Runnable action) {
        for (Hit hit : mobileMenuHits) {
            if (hit != null && hit.action == action
                    && mobileSameTextFloat(hit.bounds.x, x) && mobileSameTextFloat(hit.bounds.y, y)
                    && mobileSameTextFloat(hit.bounds.width, width) && mobileSameTextFloat(hit.bounds.height, height))
                return hit;
        }
        Hit hit = new Hit(clippedSettingsHit(x, y, width, height), action, false);
        mobileMenuHits[mobileMenuHitCursor] = hit;
        mobileMenuHitCursor = (mobileMenuHitCursor + 1) & 15;
        return hit;
    }

    private Rectangle mobileMenuSoundBounds;
    private Rectangle mobileMainMenuSoundBounds() {
        if (mobileMenuSoundBounds == null) mobileMenuSoundBounds = mainMenuSoundBounds();
        return mobileMenuSoundBounds;
    }

    private static final Color MOBILE_VERSION_COLOR = new Color((GdxProductVersionBrand.RGB << 8)
            | Math.round(GdxProductVersionBrand.ALPHA * 255f));
    private GdxGamePresentationSettings mobileMenuVersionSettings;
    private String mobileMenuProductVersion, mobileMenuVersionLabel;
    private String mobileMainMenuVersionLabel() {
        // A settings instance owns a final immutable ModMediaCatalog whose
        // installed/name/version values are final. Version is static final.
        String product = com.tonikelope.coronapoker.core.ApplicationMetadata.VERSION;
        if (mobileMenuVersionLabel == null || mobileMenuVersionSettings != presentationSettings
                || !Objects.equals(mobileMenuProductVersion, product)) {
            mobileMenuVersionLabel = GdxProductVersionBrand.label(presentationSettings);
            mobileMenuVersionSettings = presentationSettings;
            mobileMenuProductVersion = product;
        }
        return mobileMenuVersionLabel;
    }

    private TextItem mobileMenuVersionItem;
    private BitmapFont mobileMenuVersionFont;
    private BitmapFont.BitmapFontData mobileMenuVersionFontData;
    private float mobileMenuVersionScaleX, mobileMenuVersionScaleY, mobileMenuVersionMaxWidth;
    private String mobileMenuVersionText;
    private TextItem mobileMainMenuVersionItem(BitmapFont font, String text, float x, float y,
            Color color, float maxWidth) {
        BitmapFont.BitmapFontData data = font.getData();
        TextItem item = mobileMenuVersionItem;
        if (item != null && mobileMenuVersionFont == font && mobileMenuVersionFontData == data
                && Objects.equals(mobileMenuVersionText, text)
                && mobileSameTextFloat(mobileMenuVersionScaleX, data.scaleX)
                && mobileSameTextFloat(mobileMenuVersionScaleY, data.scaleY)
                && mobileSameTextFloat(mobileMenuVersionMaxWidth, maxWidth)
                && mobileSameTextFloat(item.x, x) && mobileSameTextFloat(item.y, y)
                && mobileSameTextFloat(item.color.r, color.r) && mobileSameTextFloat(item.color.g, color.g)
                && mobileSameTextFloat(item.color.b, color.b) && mobileSameTextFloat(item.color.a, color.a))
            return item;
        // The original fitting algorithm remains the miss oracle. This helper
        // is called after main-menu drawing, outside its temporary text scope.
        item = fittedTextItem(font, text, x, y, color, false, maxWidth, false);
        mobileMenuVersionItem = item;
        mobileMenuVersionFont = font;
        mobileMenuVersionFontData = data;
        mobileMenuVersionText = text;
        mobileMenuVersionScaleX = data.scaleX;
        mobileMenuVersionScaleY = data.scaleY;
        mobileMenuVersionMaxWidth = maxWidth;
        return item;
    }

    private static final class MobileMasterVolumeSnapshot {
        final String raw;
        final float value;
        MobileMasterVolumeSnapshot(String raw, float value) { this.raw = raw; this.value = value; }
    }
    // Voice generation may read volume from its completion worker while the
    // render thread reads it. Publish the immutable key/value as one unit.
    private volatile MobileMasterVolumeSnapshot mobileMasterVolumeSnapshot;
    private float mobileMasterVolume(String raw) {
        MobileMasterVolumeSnapshot snapshot = mobileMasterVolumeSnapshot;
        if (snapshot != null && Objects.equals(snapshot.raw, raw)) return snapshot.value;
        float value = GdxSettingsContract.DEFAULT_MASTER_VOLUME;
        if (raw != null) {
            try {
                float parsed = Float.parseFloat(raw);
                if (Float.isFinite(parsed) && parsed >= 0f && parsed <= 1f) value = parsed;
            } catch (NumberFormatException invalid) { }
        }
        // Parse only the captured immutable value. Never re-read mutable
        // Properties after recording its key, which could pair a key with
        // another thread's newer value.
        mobileMasterVolumeSnapshot = new MobileMasterVolumeSnapshot(raw, value);
        return value;
    }
