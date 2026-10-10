package com.tonikelope.coronapoker.gdx;

import java.util.Objects;

/** Fixed seat-owned semantic text key; unrelated table clocks do not invalidate it. */
final class IOSSeatLabelKey {
    private int kind = -1;
    private String language, first, second;
    private long number;
    private String text = "";
    boolean matches(int kind, String language, String first, String second, long number) {
        return this.kind==kind && this.number==number && Objects.equals(this.language,language)
                && Objects.equals(this.first,first) && Objects.equals(this.second,second);
    }
    String text() { return text; }
    void update(int kind, String language, String first, String second, long number, String text) {
        this.kind=kind; this.language=language; this.first=first; this.second=second; this.number=number; this.text=text;
    }
}
