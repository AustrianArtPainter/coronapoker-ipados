#!/usr/bin/env python3
"""Exercise the actual generated shell entry; no App, native runtime or AOT."""
import hashlib
from pathlib import Path
import subprocess
import tempfile
import unittest
from unittest.mock import patch

import prepare_game_sources as generator
from toolchain import jdk_home

PORT = Path(__file__).resolve().parents[1]
SHELL = "com/tonikelope/coronapoker/gdx/GdxApplicationShell.java"


class ShellValidationGenerationTests(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.temporary = tempfile.TemporaryDirectory(prefix="shell-validation-", dir=PORT / "validation")
        cls.output = Path(cls.temporary.name)
        (cls.output / "tools").symlink_to(PORT / "tools", target_is_directory=True)
        cls.desktop = generator.GDX / "GdxApplicationShell.java"
        cls.desktop_hash = hashlib.sha256(cls.desktop.read_bytes()).hexdigest()
        original = generator.prepare_shell_validation_entry

        def capture(source):
            cls.before = source
            return original(source)

        with patch.object(generator, "PORT", cls.output), \
                patch.object(generator, "GENERATED", cls.output / "generated-src"), \
                patch.object(generator, "prepare_shell_validation_entry", capture):
            generator.prepare()
        cls.after = (cls.output / "generated-src" / SHELL).read_text()

    @classmethod
    def tearDownClass(cls):
        cls.temporary.cleanup()

    def test_only_the_guard_changes_and_voice_remains_independent(self):
        guarded = "            if (IOSGameValidation.ENABLED)\n    "
        self.assertEqual(self.after.replace(guarded, "            ", 1).split(), self.before.split())
        self.assertEqual(hashlib.sha256(self.desktop.read_bytes()).hexdigest(), self.desktop_hash)

    def test_changed_missing_duplicate_and_repeated_entry_fail_closed(self):
        call = "            IOSGameValidation.startOnce(this, sessionGateway, application.service(DatabaseService.class));"
        for source in (self.before.replace(call, "", 1),
                       self.before.replace(call, call + "\n" + call, 1),
                       self.before.replace(call, call.replace("DatabaseService.class", "Other.class"), 1),
                       self.after):
            with self.assertRaisesRegex(RuntimeError, "Shell validation entry changed"):
                generator.prepare_shell_validation_entry(source)

    def test_actual_generated_entry_suppresses_lookup_only_when_qa_is_disabled(self):
        # Compile the exact generated entry before the first unrelated shell
        # operation. The counter stubs observe operand evaluation and order.
        entry = self.after[self.after.index("    public void render() {"):]
        entry = entry[:entry.index("        synchronizeScreenWakeLock();")] + "    }\n"
        stub = """
class DatabaseService {}
class Application {
    int lookups;
    Object service(Class<?> type) {
        lookups++; ShellValidationProbe.order.append('L');
        if (ShellValidationProbe.failLookup) throw new IllegalStateException("lookup");
        return this;
    }
}
class Table { String mobileVisibleScene() { return "summary"; } }
class IOSFrameEvidence { static final boolean ENABLED = false; static void scene(int p, long n) {} }
class IOSGameValidation {
    static final boolean ENABLED = Boolean.getBoolean("qa"); static int calls;
    static void visibleScene(String s) {}
    static void startOnce(Object shell, Object gateway, Object db) {
        calls++; ShellValidationProbe.order.append('Q');
    }
}
class IOSVoiceValidation { static int calls; static void startOnce() {
    calls++; ShellValidationProbe.order.append('V');
} }
class GdxApplicationShell {
    Table table; Object startupIntro, menu = new Object(), sessionGateway;
    Application application = new Application();
ACTUAL_ENTRY
}
public class ShellValidationProbe {
    static StringBuilder order = new StringBuilder(); static boolean failLookup;
    public static void main(String[] args) {
        boolean qa = IOSGameValidation.ENABLED;
        GdxApplicationShell shell = new GdxApplicationShell();
        shell.render(); shell.table = new Table(); shell.render();
        if (shell.application.lookups != (qa ? 2 : 0)
                || IOSGameValidation.calls != (qa ? 2 : 0) || IOSVoiceValidation.calls != 2)
            throw new AssertionError("entry calls");
        if (!order.toString().equals(qa ? "LQVLQV" : "VV")) throw new AssertionError(order);
        shell.startupIntro = new Object(); shell.render(); shell.startupIntro = null;
        shell.menu = null; shell.render();
        if (IOSVoiceValidation.calls != 2) throw new AssertionError("startup/menu gate");
        shell.menu = new Object(); failLookup = true;
        try { shell.render(); if (qa) throw new AssertionError("lookup exception lost"); }
        catch (IllegalStateException expected) { if (!qa) throw expected; }
        if (IOSVoiceValidation.calls != (qa ? 2 : 3)) throw new AssertionError("voice order");
        System.out.println("PASS qa=" + qa);
    }
}
""".replace("ACTUAL_ENTRY", entry)
        source = self.output / "ShellValidationProbe.java"
        source.write_text(stub)
        classes = self.output / "classes"
        classes.mkdir(exist_ok=True)
        java = jdk_home() / "bin"
        subprocess.run([str(java / "javac"), "--release", "8", "-d", str(classes), str(source)],
                       check=True, capture_output=True, text=True)
        for enabled in (False, True):
            result = subprocess.run([str(java / "java"), "-Dqa=" + str(enabled).lower(),
                                     "-cp", str(classes), "ShellValidationProbe"],
                                    check=True, capture_output=True, text=True)
            self.assertIn("PASS qa=" + str(enabled).lower(), result.stdout)


if __name__ == "__main__":
    unittest.main()
