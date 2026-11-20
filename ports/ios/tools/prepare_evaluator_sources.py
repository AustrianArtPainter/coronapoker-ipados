"""Keep the upstream ranking algorithm; give each evaluation thread its workspace."""
import re


def prepare_evaluator(source, replace_method):
    # These helpers do not recurse into ranking. Their complete initialization
    # loops remain upstream, so reused scratch never inherits an earlier hand.
    def rank(method):
        method = method.replace("byte[] group_size = new byte[POKER_HAND + 1];",
                                "MobileRankWorkspace workspace = MOBILE_RANK.get();\n        byte[] group_size = workspace.groups;")
        method = method.replace("byte[] paired = new byte[Card.NUM_RANKS];", "byte[] paired = workspace.paired;")
        method = method.replace("byte[][] pair_rank = new byte[POKER_HAND + 1][2];", "byte[][] pair_rank = workspace.pairRanks;")
        return method.replace("byte[] suit_size = new byte[Card.NUM_SUITS];", "byte[] suit_size = workspace.suits;")
    source = replace_method(source, "    public static int rankHand_Java(", rank)
    source = replace_method(source, "    private static boolean ID_ExistsStraightFlush(", lambda method:
                            method.replace("new boolean[Card.NUM_RANKS]", "MOBILE_RANK.get().straightPresence"))
    source = replace_method(source, "    private static int ID_KickerValueSuited(", lambda method:
                            method.replace("new boolean[Card.NUM_RANKS]", "MOBILE_RANK.get().flushPresence"))
    for signature in ("    public static int rankHand_Java(", "    private static boolean ID_ExistsStraightFlush(",
                      "    private static int ID_KickerValueSuited("):
        source = replace_method(source, signature, lambda method: re.sub(
            r"h\.getCard\(([^)]+)\)\.get(Rank|Suit)\(\)",
            lambda match: "(mobileCardIndex(h, " + match[1] + ") "
                          + ("%" if match[2] == "Rank" else "/") + " Card.NUM_RANKS)", method))
    fields = '''
    private static final ThreadLocal<MobileRankWorkspace> MOBILE_RANK =
            ThreadLocal.withInitial(MobileRankWorkspace::new);
    private static final class MobileRankWorkspace {
        final byte[] groups = new byte[POKER_HAND + 1];
        final byte[] paired = new byte[Card.NUM_RANKS];
        final byte[][] pairRanks = new byte[POKER_HAND + 1][2];
        final byte[] suits = new byte[Card.NUM_SUITS];
        final boolean[] straightPresence = new boolean[Card.NUM_RANKS];
        final boolean[] flushPresence = new boolean[Card.NUM_RANKS];
    }
    private static int mobileCardIndex(Hand hand, int position) {
        int index = hand.getCardArray()[position];
        // Match Card(int)'s BAD_CARD normalization, including invalid input.
        return index >= 0 && index < Card.NUM_CARDS ? index : -1;
    }
'''
    return source[:source.rfind("}")] + fields + "}\n"
