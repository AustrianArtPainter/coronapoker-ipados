"""Replace only the exact iOS table amount entry; retain its original algorithm."""

ORIGINAL = '''    static String formatAmount(double amount) {
        return java.math.BigDecimal.valueOf(amount)
                .setScale(2, java.math.RoundingMode.HALF_UP)
                .stripTrailingZeros()
                .toPlainString();
    }'''

UNCACHED = ORIGINAL.replace("formatAmount(", "mobileUncachedFormatAmount(", 1)
WRAPPER = '''    static String formatAmount(double amount) {
        return IOSTableAmountCache.formatAmount(amount);
    }'''


def prepare(text):
    """Fail closed if the upstream formatter or this single ownership changes."""
    if (text.count(ORIGINAL) != 1
            or text.count("    static String formatAmount(") != 1
            or "mobileUncachedFormatAmount(" in text):
        raise RuntimeError("iOS table amount ownership changed; expected one exact original formatter")
    return text.replace(ORIGINAL, WRAPPER + "\n\n" + UNCACHED, 1)
