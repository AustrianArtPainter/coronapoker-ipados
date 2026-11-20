    // Diagnostic boundary only. All audiovisual presentation remains in the original caller.
    private boolean mobileMockDeal() {
        if (!gameSession().isHost()) throw new IllegalStateException("Crypto mock cannot join a room");
        for (java.util.Map.Entry<String, GamePeerController> entry : peers().entrySet())
            if (!entry.getKey().equals(gameSession().localNickname()) && !entry.getValue().isCpu())
                throw new IllegalStateException("Crypto mock cannot deal to a remote human");
        com.tonikelope.coronapoker.ios.MockCardCrypto.DEALS.incrementAndGet();
        java.util.ArrayList<GamePlayerController> ring = getAnilloCriptografico();
        if (ring.size() < 2) return false;
        this.active_crypto_ring = new String[ring.size()];
        this.current_hand_id = new byte[CanonicalActionRecord.HAND_ID_BYTES];
        this.current_hand_id[0] = (byte) getMano();
        this.local_mega_packet = com.tonikelope.coronapoker.ios.MockCardCrypto.deck(getMano());
        this.local_sra_lock = com.tonikelope.coronapoker.ios.MockCardCrypto.scalar();
        this.local_sra_unlock = this.local_sra_lock;
        this.local_sra_lock_community = this.local_sra_lock;
        this.local_sra_unlock_community = this.local_sra_lock;
        this.bot_community_locks.clear(); this.peer_k_pocket.clear(); this.peer_k_community.clear();
        this.single_locked_pocket_cards.clear();
        for (int i=0; i<ring.size(); i++) {
            GamePlayerController player = ring.get(i);
            String nick = player.getNickname(); this.active_crypto_ring[i] = nick;
            this.peer_k_pocket.put(nick, this.local_sra_lock);
            this.peer_k_community.put(nick, this.local_sra_lock);
            byte[] pocket = java.util.Arrays.copyOfRange(this.local_mega_packet, i*64, (i+1)*64);
            this.single_locked_pocket_cards.put(nick, pocket);
            if (nick.equals(gameSession().localNickname())) {
                this.local_original_cards[0] = pocket[0]; this.local_original_cards[1] = pocket[32];
            } else {
                GamePeerController peer = peers().get(nick);
                peer.setReceived_token(this.local_sra_lock); peer.setSra_unlock_community(this.local_sra_lock);
                this.bot_community_locks.put(nick, this.local_sra_lock);
                if (player.isActivo()) {
                    player.getHoleCard1().iniciarConValorNumerico((pocket[0] & 255)+1);
                    player.getHoleCard2().iniciarConValorNumerico((pocket[32] & 255)+1);
                }
            }
            emitShuffleTurn(nick);
        }
        // No cascade, rotation, proof construction, validation, or proof worker is submitted.
        this.cascade_chain_decks = null; this.cascade_step_perm = null; this.cascade_step_k = null;
        this.cascade_step_owner = null; this.cascade_rotation_states = null;
        this.cascade_rotation_scalars = null; this.cascade_rotation_remote_proofs = null;
        synchronized (shuffle_proof_gate_lock) {
            this.dual_lock_expect_bundle_for = this.local_mega_packet;
            this.dual_lock_verified_megapacket = this.local_mega_packet;
            this.dual_lock_failed_megapacket = null;
            this.dual_lock_persisted_megapacket = null;
            shuffle_proof_gate_lock.notifyAll();
        }
        initHandStateChain();
        if (!guardarFosilSRA()) throw new IllegalStateException("Mock diagnostic fossil was not persisted");
        markShuffleProofPersisted(this.local_mega_packet);
        return true;
    }
