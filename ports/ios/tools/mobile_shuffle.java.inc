    private PreloadedShuffleAnimation mobileShuffleCache;
    private TableVisualEvent.Shuffle mobilePendingShuffle;
    private CompletableFuture<Void> mobilePendingShuffleBarrier;

    private void mobilePreparePendingShuffle() {
        if (mobilePendingShuffle == null) return;
        try {
            if (!completeShufflePreparation()) return;
            TableVisualEvent.Shuffle event = mobilePendingShuffle;
            CompletableFuture<Void> barrier = mobilePendingShuffleBarrier;
            mobilePendingShuffle = null;
            mobilePendingShuffleBarrier = null;
            acceptLiveShuffle(event, barrier);
        } catch (Throwable failure) {
            mobilePendingShuffleBarrier.completeExceptionally(failure);
            mobilePendingShuffle = null;
            mobilePendingShuffleBarrier = null;
        }
    }
