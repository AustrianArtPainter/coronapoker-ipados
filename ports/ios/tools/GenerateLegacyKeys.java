import java.awt.event.KeyEvent;
import java.awt.event.InputEvent;
import java.nio.file.*;
import java.util.*;
import java.util.regex.*;

/** Export only stable persisted key constants; no AWT reaches the device. */
public final class GenerateLegacyKeys {
    public static void main(String[] args) throws Exception {
        String source = Files.readString(Path.of(args[0]));
        Path output = Path.of(args[1]); Files.createDirectories(output);
        for (String type : List.of("KeyEvent", "InputEvent")) {
            Class<?> origin = type.equals("KeyEvent") ? KeyEvent.class : InputEvent.class;
            Matcher matcher = Pattern.compile(type + "\\.([A-Z][A-Z0-9_]*)").matcher(source);
            Set<String> fields = new TreeSet<>();
            while (matcher.find()) fields.add(matcher.group(1));
            StringBuilder code = new StringBuilder("package com.tonikelope.coronapoker.ios;\npublic final class " + type + " {\n");
            Map<Integer, String> labels = new TreeMap<>();
            for (String field : fields) {
                int value = origin.getField(field).getInt(null);
                code.append(" public static final int ").append(field).append(" = ").append(value).append(";\n");
                if (type.equals("KeyEvent")) labels.put(value, KeyEvent.getKeyText(value));
            }
            if (type.equals("KeyEvent")) {
                code.append(" public static String getKeyText(int code) { switch(code) {\n");
                for (var entry : labels.entrySet()) code.append(" case ").append(entry.getKey()).append(": return \"").append(entry.getValue().replace("\\", "\\\\").replace("\"", "\\\"")).append("\";\n");
                code.append(" default: if (code >= 65 && code <= 90 || code >= 48 && code <= 57) return String.valueOf((char)code); if(code>=112&&code<=123) return \"F\"+(code-111); return \"KEY \"+code; } }\n");
            }
            code.append(" private ").append(type).append("() {}\n}\n");
            Files.writeString(output.resolve(type + ".java"), code);
        }
    }
}
