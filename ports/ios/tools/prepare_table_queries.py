"""Narrow iOS table query rewrites; desktop methods remain the exact oracle."""

# Full old methods intentionally fail closed when an upstream guard, predicate or
# priority changes. This module does not cache mutable table/lobby decisions.
REPLACEMENTS = (
    ("canKickTimedOutPlayer", '''    private boolean canKickTimedOutPlayer(String nickname) {
        if (!tableHost || liveState == null || tableChat == null
                || nickname == null || nickname.isBlank()) {
            return false;
        }
        TableSnapshot.PlayerSnapshot player = livePlayer(nickname);
        if (player == null) return false;
        LobbyParticipant participant = tableChat.snapshot().participants()
                .stream()
                .filter(candidate -> candidate.nickname().equals(nickname))
                .findFirst().orElse(null);
        return participant != null && timedOutKickEligible(tableHost,
                participant.local(), participant.bot(), player.timedOut(),
                player.exited());
    }''', '''    private boolean canKickTimedOutPlayer(String nickname) {
        if (!tableHost || liveState == null || tableChat == null
                || nickname == null || nickname.isBlank()) {
            return false;
        }
        TableSnapshot.PlayerSnapshot player = livePlayer(nickname);
        if (player == null) return false;
        List<LobbyParticipant> participants = tableChat.snapshot().participants();
        LobbyParticipant participant = null;
        for (int index = 0; index < participants.size(); index++) {
            LobbyParticipant candidate = participants.get(index);
            if (candidate.nickname().equals(nickname)) {
                participant = candidate;
                break;
            }
        }
        return participant != null && timedOutKickEligible(tableHost,
                participant.local(), participant.bot(), player.timedOut(),
                player.exited());
    }'''),
    ("availableDeck", '''    private String availableDeck(String deck) {
        if (deck == null) return "goliat";
        if (presentationSettings != null) {
            for (String candidate : presentationSettings.availableDecks()) {
                if (candidate.equalsIgnoreCase(deck)) return candidate;
            }
        }
        return GdxGamePresentationSettings.OFFICIAL_DECKS.stream()
                .filter(candidate -> candidate.equalsIgnoreCase(deck))
                .findFirst().orElse("goliat");
    }''', '''    private String availableDeck(String deck) {
        if (deck == null) return "goliat";
        if (presentationSettings != null) {
            List<String> available = presentationSettings.availableDecks();
            for (int index = 0; index < available.size(); index++) {
                String candidate = available.get(index);
                if (candidate.equalsIgnoreCase(deck)) return candidate;
            }
        }
        List<String> official = GdxGamePresentationSettings.OFFICIAL_DECKS;
        for (int index = 0; index < official.size(); index++) {
            String candidate = official.get(index);
            if (candidate.equalsIgnoreCase(deck)) return candidate;
        }
        return "goliat";
    }'''),
    ("tableAvatar", '''    private Texture tableAvatar(String nickname) {
        if (tableChat != null && nickname != null) {
            for (com.tonikelope.coronapoker.core.LobbyParticipant participant
                    : tableChat.snapshot().participants()) {
                if (!participant.nickname().equals(nickname)) continue;
                if (participant.avatar() == null) {
                    return participant.bot() ? avatarBot : avatarDefault;
                }
                String path = participant.avatar().toString();
                Texture cached = tableAvatarTextures.get(path);
                if (cached != null) return cached;
                try {
                    Texture loaded = new Texture(Gdx.files.absolute(path), true);
                    loaded.setFilter(TextureFilter.MipMapLinearLinear,
                            TextureFilter.Linear);
                    tableAvatarTextures.put(path, loaded);
                    return loaded;
                } catch (RuntimeException invalidAvatar) {
                    return participant.bot() ? avatarBot : avatarDefault;
                }
            }
        }
        return BotNickname.isBot(nickname)
                ? avatarBot : avatarDefault;
    }''', '''    private Texture tableAvatar(String nickname) {
        if (tableChat != null && nickname != null) {
            List<com.tonikelope.coronapoker.core.LobbyParticipant> participants
                    = tableChat.snapshot().participants();
            for (int participantIndex = 0; participantIndex < participants.size(); participantIndex++) {
                com.tonikelope.coronapoker.core.LobbyParticipant participant
                        = participants.get(participantIndex);
                if (!participant.nickname().equals(nickname)) continue;
                if (participant.avatar() == null) {
                    return participant.bot() ? avatarBot : avatarDefault;
                }
                String path = participant.avatar().toString();
                Texture cached = tableAvatarTextures.get(path);
                if (cached != null) return cached;
                try {
                    Texture loaded = new Texture(Gdx.files.absolute(path), true);
                    loaded.setFilter(TextureFilter.MipMapLinearLinear,
                            TextureFilter.Linear);
                    tableAvatarTextures.put(path, loaded);
                    return loaded;
                } catch (RuntimeException invalidAvatar) {
                    return participant.bot() ? avatarBot : avatarDefault;
                }
            }
        }
        return BotNickname.isBot(nickname)
                ? avatarBot : avatarDefault;
    }'''),
)


def prepare(text):
    """Replace exactly the owned current upstream methods, once each."""
    for name, old, new in REPLACEMENTS:
        count = text.count(old)
        if count != 1:
            raise RuntimeError("iOS table query ownership changed: " + name
                               + " (expected one exact original method, found " + str(count) + ")")
        text = text.replace(old, new, 1)
    return text
