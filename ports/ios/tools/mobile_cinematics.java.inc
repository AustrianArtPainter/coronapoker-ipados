
    private final IOSCinematicAssets mobileAllInAssets = new IOSCinematicAssets();
    private boolean mobileAllInWarmed;

    private StreamingGifTextureAnimation allInAnimation(String filename) {
        PreloadedShuffleAnimation prepared = mobileAllInAssets.prepared(filename);
        if (prepared == null && (presentationSettings == null
                || presentationSettings.modAsset("cinematics/allin/" + filename).isEmpty())
                && !Gdx.files.internal("cinematics/allin/" + filename).exists())
            prepared = mobileAllInAssets.prepared("rounders.gif");
        if (prepared == null) {
            IOSFrameEvidence.allInFallbackPlaybacks++;
            return mobileLegacyAllInAnimation(filename);
        }
        IOSFrameEvidence.allInPreparedPlaybacks++;
        return StreamingGifTextureAnimation.borrowed(prepared);
    }

    private void mobileWarmAllInEffects() {
        if (startupIntroOnly || mobileAllInWarmed) return;
        // Companion WAVs are assets too; never create their Sound at START.
        for (String name : mobileAllInAssets.names()) allInCompanionSound(name);
        if (allInFireShader != null) {
            mobileClosingLayer.begin();
            try {
                batch.setProjectionMatrix(camera.combined);
                batch.setShader(allInFireShader);
                for (int pass = 0; pass < 2; pass++) {
                    batch.setBlendFunction(GL20.GL_SRC_ALPHA,
                            pass == 0 ? GL20.GL_ONE : GL20.GL_ONE_MINUS_SRC_ALPHA);
                    batch.begin();
                    allInFireShader.setUniformf("u_time", 0f);
                    allInFireShader.setUniformf("u_seed", 0f);
                    allInFireShader.setUniformf("u_alpha", 1f);
                    allInFireShader.setUniformf("u_bloom", pass == 0 ? 1f : 0f);
                    batch.draw(allInFireCanvas, 0f, 0f, 32f, 32f);
                    batch.end();
                }
            } finally {
                if (batch.isDrawing()) batch.end();
                batch.setShader(null);
                batch.setBlendFunction(GL20.GL_SRC_ALPHA, GL20.GL_ONE_MINUS_SRC_ALPHA);
                mobileClosingLayer.end();
                mobileClosingLayer.invalidate();
            }
        }
        mobileAllInWarmed = true;
    }
