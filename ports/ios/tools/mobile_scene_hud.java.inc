    /** Persistent geometry node: the original layout formula feeds it on resize. */
    static final class LocalHudLayout {
        private final float x, y, width, height, stackWidth, investedWidth, actionX, actionWidth;
        private final Rectangle stack, invested, stackVisual, investedVisual;
        private final Rectangle stackCaption, stackValue, investedCaption, investedValue, bounds;
        LocalHudLayout(float x, float y, float width, float height, float stackWidth,
                float investedWidth, float actionX, float actionWidth) {
            this.x = x; this.y = y; this.width = width; this.height = height;
            this.stackWidth = stackWidth; this.investedWidth = investedWidth;
            this.actionX = actionX; this.actionWidth = actionWidth;
            stack = new Rectangle(x, LOCAL_HUD_COUNTER_Y, stackWidth, LOCAL_HUD_COUNTER_HEIGHT);
            invested = new Rectangle(actionX + actionWidth + LOCAL_HUD_COUNTER_GAP,
                    LOCAL_HUD_COUNTER_Y, investedWidth, LOCAL_HUD_COUNTER_HEIGHT);
            stackVisual = new Rectangle(stackChipCenterX() - 40f, counterChipCenterY() - 17f, 60f, 59f);
            investedVisual = new Rectangle(investedChipCenterX() - 20f, counterChipCenterY() - 17f, 60f, 59f);
            stackCaption = new Rectangle(stack.x + 8f, stack.y + stack.height - 32f, stack.width - 16f, 24f);
            stackValue = new Rectangle(stack.x + 82f, stack.y + 8f, stack.width - 92f, stack.height - 43f);
            investedCaption = new Rectangle(invested.x + 8f, invested.y + invested.height - 32f, invested.width - 16f, 24f);
            investedValue = new Rectangle(invested.x + 10f, invested.y + 8f, invested.width - 92f, invested.height - 43f);
            bounds = new Rectangle(x, y, width, height);
        }
        float x() { return x; } float y() { return y; }
        float width() { return width; } float height() { return height; }
        float stackWidth() { return stackWidth; } float investedWidth() { return investedWidth; }
        float actionX() { return actionX; } float actionWidth() { return actionWidth; }
        float actionY() { return y + 24f; } float actionHeight() { return 80f; }
        Rectangle stackBounds() { return stack; } Rectangle investedBounds() { return invested; }
        float stackChipCenterX() { return stack.x + 43f; }
        float investedChipCenterX() { return invested.x + invested.width - 43f; }
        float counterChipCenterY() { return stack.y + stack.height / 2f - 12.5f; }
        Rectangle stackChipVisualBounds() { return stackVisual; }
        Rectangle investedChipVisualBounds() { return investedVisual; }
        Rectangle stackCaptionBounds() { return stackCaption; }
        Rectangle stackValueBounds() { return stackValue; }
        Rectangle investedCaptionBounds() { return investedCaption; }
        Rectangle investedValueBounds() { return investedValue; }
        Rectangle bounds() { return bounds; }
    }
