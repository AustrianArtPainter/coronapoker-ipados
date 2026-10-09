
    private final com.tonikelope.coronapoker.ios.TouchTextHold mobileTextHold =
            new com.tonikelope.coronapoker.ios.TouchTextHold();
    private void mobileUpdateTextHold() {
        if (!mobileTextHold.ready(Gdx.input.getX(), Gdx.input.getY(),
                Gdx.input.isTouched(0), System.nanoTime())) return;
        if (activeField == null || !activeField.equals(pointerSelectionField)
                || hasBlockingFrontendModal() && !frontendModalTextFieldActive()) return;
        textEdit.focus(activeField, activeValue());
        if (!textEdit.hasSelection(activeValue())) textEdit.selectAll(activeValue());
        pointerSelectionField = null;
        pressedHit = null;
        pointer.set(Gdx.input.getX(), Gdx.input.getY());
        viewport.unproject(pointer);
        openEditMenu(pointer.x, pointer.y);
    }
