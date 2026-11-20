#!/usr/bin/env python3
"""Narrow GC release-flag checks; no GC/App build, AOT or simulator launch."""
import json
from pathlib import Path
import shlex
import tempfile
import unittest
from unittest.mock import patch

import build_gc


class OptimizationChecks(unittest.TestCase):
    compiler_evidence = {}
    retained_build_evidence = None

    @staticmethod
    def command(member="alloc.o", flags=("-O2",), compiler="/usr/bin/clang"):
        return "libtool: compile:  " + shlex.join([
            compiler, "-DHAVE_CONFIG_H", *flags, "-c", "/pinned/" + member[:-2] + ".c", "-o", member])

    def test_pinned_flags_are_explicit(self):
        lock = json.loads(build_gc.LOCK_PATH.read_text())
        self.assertEqual(["-O2"], build_gc.verify_optimization_flags(lock["commonCFlags"], "lock"))

    def test_missing_conflicting_and_hidden_flags_rejected(self):
        invalid = [[], ["-O0"], ["-O1"], ["-O3"], ["-Os"], ["-Oz"], ["-Ofast"], ["-O"],
                   ["-O2", "-O2"], ["-O2", "-O0"], ["-O1", "-O2"],
                   ["-O2", "@later-flags"], ["-O2", "--config=override.cfg"],
                   ["-O2", "-Xclang", "-O0"], ["-O2", "-mllvm", "option"],
                   ["-O2", "-Xclang=-O1"], ["-O2", "-mllvm=option"],
                   ["-O2", "-Xarch_arm64", "-O1"]]
        for flags in invalid:
            with self.subTest(flags=flags), self.assertRaises(RuntimeError):
                build_gc.verify_optimization_flags(flags, "negative oracle")

    def test_actual_commands_cover_every_archive_member(self):
        text = "configure intent: -O0\n" + self.command() + "\n" + self.command("mark.o")
        evidence = build_gc.inspect_compile_commands(text, "/usr/bin/clang", ["alloc.o", "mark.o"])
        self.assertEqual(2, evidence["archiveMemberCount"])
        self.assertEqual(2, evidence["observedNativeCompileCommands"])
        self.assertEqual(["alloc.o", "mark.o"], [Path(row["object"]).name
                         for row in evidence["archiveCompileCommands"]])
        self.assertEqual(["-O2"], evidence["archiveCompileCommands"][0]["optimizationFlags"])

    def test_intent_cannot_mask_actual_optimization(self):
        for flags in [(), ("-O0",), ("-O2", "-O1"), ("-O2", "-O2")]:
            with self.subTest(flags=flags), self.assertRaises(RuntimeError):
                build_gc.inspect_compile_commands("CFLAGS=-O2\n" + self.command(flags=flags),
                                                  "/usr/bin/clang", ["alloc.o"])

    def test_missing_duplicate_and_interleaved_commands_rejected(self):
        cases = [(self.command(), ["alloc.o", "mark.o"]),
                 (self.command() + "\n" + self.command(), ["alloc.o"]),
                 (self.command() + self.command("mark.o"), ["alloc.o", "mark.o"]),
                 ("shell output prefix " + self.command(), ["alloc.o"]),
                 (self.command(), ["alloc.o", "alloc.o"]),
                 ("/bin/sh ./libtool --mode=compile clang -O2 -c alloc.c -o alloc.lo", ["alloc.o"])]
        for text, members in cases:
            with self.subTest(text=text), self.assertRaises(RuntimeError):
                build_gc.inspect_compile_commands(text, "/usr/bin/clang", members)

    def test_wrong_compiler_or_ambiguous_output_rejected(self):
        cases = [self.command(compiler="/unexpected/clang"),
                 self.command().replace("-c /pinned/alloc.c", "-c"),
                 self.command().replace("-o alloc.o", "-o"),
                 self.command().replace("-o alloc.o", "-o alloc.o -o mark.o")]
        for text in cases:
            with self.subTest(text=text), self.assertRaises(RuntimeError):
                build_gc.inspect_compile_commands(text, "/usr/bin/clang", ["alloc.o"])

    def test_predefines_must_confirm_optimization(self):
        for macros in ["", "#define __OPTIMIZE__ 0", "#define __OPTIMIZE__ 1\n#define __OPTIMIZE_SIZE__ 1",
                       "#define __OPTIMIZE__ 1\n#define __NO_INLINE__ 1"]:
            with self.subTest(macros=macros), patch.object(build_gc, "output", return_value=macros), \
                    self.assertRaises(RuntimeError):
                build_gc.verify_compiler_optimization("/usr/bin/clang", ["-O2"])

    def test_clang_environment_cannot_rewrite_optimization(self):
        for value in ["", "+-O1", "s/-O2/-O1/"]:
            with self.subTest(value=value), patch.object(build_gc, "output") as compiler, \
                    self.assertRaisesRegex(RuntimeError, "CCC_OVERRIDE_OPTIONS"):
                build_gc.verify_compiler_optimization("/usr/bin/clang", ["-O2"],
                                                     {"CCC_OVERRIDE_OPTIONS": value})
            compiler.assert_not_called()

    def test_log_hash_binds_actual_command_evidence(self):
        with tempfile.TemporaryDirectory() as tmp:
            log = Path(tmp) / "build.log"
            log.write_text(self.command() + "\n")
            def fake_output(*args, **kwargs):
                return "__.SYMDEF SORTED\nalloc.o" if args[0] == "ar" else "#define __OPTIMIZE__ 1"
            with patch.object(build_gc, "output", side_effect=fake_output):
                original = build_gc.verify_build_optimization("fixture.a", log, "/usr/bin/clang", ["-O2"])
                log.write_text(log.read_text() + "after make\n")
                changed = build_gc.verify_build_optimization("fixture.a", log, "/usr/bin/clang", ["-O2"])
            self.assertNotEqual(original["buildLogSHA256"], changed["buildLogSHA256"])
            self.assertEqual(original["archiveCompileCommands"], changed["archiveCompileCommands"])
            self.assertNotEqual(original, changed)  # The production cache-reuse comparison rejects this.

    def test_actual_target_compiler_predefines(self):
        lock = json.loads(build_gc.LOCK_PATH.read_text())
        cc = build_gc.output("xcrun", "--find", "clang")
        for arch, (sdk, _) in build_gc.ARCHITECTURES.items():
            sysroot = build_gc.output("xcrun", "--sdk", sdk, "--show-sdk-path")
            flags = [*lock["commonCFlags"], lock["registeredPolicyFlag"],
                     "-target", lock["targetTriples"][arch], "--sysroot=" + sysroot]
            with self.subTest(arch=arch):
                evidence = build_gc.verify_compiler_optimization(cc, flags)
                self.assertEqual("1", evidence["predefines"]["__OPTIMIZE__"])
                self.compiler_evidence[arch] = evidence

    def test_retained_unoptimized_build_is_rejected_and_preserved(self):
        retained = None
        for path in (build_gc.PORT / "build/gc-registered").glob("*/manifest.json"):
            manifest = json.loads(path.read_text())
            item = manifest["libraries"]["ios-simulator"]
            if not any(flag.startswith("-O") for flag in item["flags"]):
                retained = path, item
                break
        if retained is None:
            self.skipTest("No retained pre-correction GC build available")
        path, item = retained
        log, library = Path(item["buildLog"]), Path(item["path"])
        before = {str(p): build_gc.digest(p) for p in [path, log, library]}
        members = [name for name in build_gc.output("ar", "-t", library).splitlines()
                   if not name.startswith("__.SYMDEF")]
        cc = build_gc.output("xcrun", "--find", "clang")
        with self.assertRaisesRegex(RuntimeError, "Expected exactly one explicit -O2"):
            build_gc.inspect_compile_commands(log.read_text(), cc, members)
        after = {str(p): build_gc.digest(p) for p in [path, log, library]}
        self.assertEqual(before, after)
        type(self).retained_build_evidence = {"oldUnoptimizedActualLogRejected": True,
                                             "retainedFilesUnchanged": True, "sha256": before}


if __name__ == "__main__":
    result = unittest.TextTestRunner(verbosity=2).run(unittest.defaultTestLoader.loadTestsFromTestCase(OptimizationChecks))
    report = {"passed": result.wasSuccessful(), "tests": result.testsRun,
              "failures": len(result.failures), "errors": len(result.errors), "skipped": len(result.skipped),
              "actualCompilerPredefines": OptimizationChecks.compiler_evidence,
              "retainedBuild": OptimizationChecks.retained_build_evidence,
              "sourceSHA256": {str(path): build_gc.digest(path) for path in
                               [build_gc.LOCK_PATH, Path(build_gc.__file__), Path(__file__)]},
              "gcOrAppBuildRun": False, "aotOrSimulatorRun": False}
    destination = build_gc.PORT / "build/gc-optimization-tests/result.json"
    destination.parent.mkdir(parents=True, exist_ok=True)
    destination.write_text(json.dumps(report, indent=2) + "\n")
    raise SystemExit(0 if result.wasSuccessful() else 1)
