#!/usr/bin/env python3
"""Build the pinned MobiVM GC's registered-thread policy in a private SDK clone.

The supplied SDK is never edited. No new collector, incremental collection,
thread-name exemptions or marker-count tuning is introduced. Native callbacks
continue to use the existing RVM attach/register protocol.
"""
import argparse
import hashlib
import json
import os
import re
import shlex
import shutil
import struct
import subprocess
import sys
import tarfile
import urllib.request
from pathlib import Path

PORT = Path(__file__).resolve().parents[1]
LOCK_PATH = PORT / "config/gc-registered-runtime.json"
ORIGINAL_SDK = PORT / ".cache/toolchains/robovm-3.0.0-SNAPSHOT"
ARCHITECTURES = {"ios": ("iphoneos", "arm64"),
                 "ios-simulator": ("iphonesimulator", "arm64-simulator")}
GETTER = """
/* CoronaPoker diagnostic: read the actual compiled/runtime policy. */
#if defined(GC_DARWIN_THREADS)
GC_API int GC_CALL CPGC_uses_task_threads(void) {
    return GC_query_task_threads ? 1 : 0;
}
#else
# error CoronaPoker GC policy getter requires Darwin thread support
#endif
"""


def digest(path):
    h = hashlib.sha256()
    with Path(path).open("rb") as stream:
        for chunk in iter(lambda: stream.read(1024 * 1024), b""):
            h.update(chunk)
    return h.hexdigest()


def tree_hashes(root):
    """Bind all SDK files, including compilers and every runtime consumer."""
    files = {}
    for path in sorted(Path(root).rglob("*")):
        if path.is_symlink():
            raise RuntimeError("SDK snapshot must not contain symlinks: " + str(path))
        if path.is_file():
            files[str(path.relative_to(root))] = digest(path)
    return files


def verify_sdk_clone(sdk_home, source_hashes, libraries):
    expected = dict(source_hashes)
    for arch, (_, sdk_arch) in ARCHITECTURES.items():
        expected["lib/vm/ios/" + sdk_arch + "/libgc.a"] = libraries[arch]["sha256"]
    if tree_hashes(sdk_home) != expected:
        raise RuntimeError("SDK clone changed beyond its two reviewed collectors")


def output(*args, cwd=None, env=None):
    return subprocess.check_output([str(a) for a in args], cwd=cwd, env=env,
                                   text=True, stderr=subprocess.STDOUT).strip()


def run(args, log, cwd=None, env=None):
    with Path(log).open("a") as stream:
        stream.write("\n$ " + shlex.join(map(str, args)) + "\n")
        stream.flush()
        subprocess.run(list(map(str, args)), cwd=cwd, env=env, stdout=stream,
                       stderr=subprocess.STDOUT, check=True)


def verify_optimization_flags(flags, context):
    """Reject implicit, conflicting or hidden optimization driver options."""
    levels = [flag for flag in flags if flag.startswith("-O")]
    hidden = [flag for flag in flags if flag.startswith(
        ("@", "--config", "-Xarch_", "-Xclang", "-mllvm", "-cc1"))]
    if levels != ["-O2"] or hidden:
        raise RuntimeError("Expected exactly one explicit -O2 and no hidden compiler options in "
                           + context + ": " + repr(levels + hidden))
    return levels


def verify_compiler_environment(env=None):
    # This Clang driver escape hatch can rewrite -O2 to -O1 without changing
    # either the echoed argv or __OPTIMIZE__. Do not silently accept it.
    current = os.environ if env is None else env
    if "CCC_OVERRIDE_OPTIONS" in current:
        raise RuntimeError("GC release build requires CCC_OVERRIDE_OPTIONS to be absent")


def verify_compiler_optimization(cc, flags, env=None):
    verify_compiler_environment(env)
    verify_optimization_flags(flags, "compiler flags")
    command = [str(cc), *flags, "-E", "-dM", "-x", "c", "/dev/null"]
    macros = dict(re.findall(r"^#define (\w+)[ \t]*(.*)$", output(*command, env=env), re.M))
    observed = {name: macros.get(name) for name in
                ["__OPTIMIZE__", "__OPTIMIZE_SIZE__", "__NO_INLINE__"]}
    if observed != {"__OPTIMIZE__": "1", "__OPTIMIZE_SIZE__": None, "__NO_INLINE__": None}:
        raise RuntimeError("Compiler did not report the expected -O2 optimization predefines: "
                           + repr(observed))
    return {"command": command, "predefines": observed,
            "environmentControls": {"CCC_OVERRIDE_OPTIONS": "absent"}}


def inspect_compile_commands(text, cc, members):
    """Audit executed libtool commands, never the configure/make command intent.

    The build is serial so libtool's multi-write command echoes cannot interleave.
    Every archive member must have exactly one observed native compile command.
    """
    marker = "libtool: compile:"
    expected = set(members)
    if not expected or len(expected) != len(members):
        raise RuntimeError("Expected a nonempty GC archive without duplicate member names")
    records = {}
    total = 0
    for number, line in enumerate(text.splitlines(), 1):
        if marker not in line:
            continue
        if not line.startswith(marker) or line.count(marker) != 1:
            raise RuntimeError("Interleaved or ambiguous actual compiler command at line " + str(number))
        command = shlex.split(line[len(marker):])
        if not command or Path(command[0]).resolve() != Path(cc).resolve():
            raise RuntimeError("Unexpected actual GC compiler at line " + str(number))
        verify_optimization_flags(command[1:], "actual compiler command at line " + str(number))
        if command.count("-c") != 1 or command.count("-o") != 1:
            raise RuntimeError("Ambiguous actual GC compile inputs at line " + str(number))
        try:
            source = command[command.index("-c") + 1]
            object_path = command[command.index("-o") + 1]
        except IndexError as failure:
            raise RuntimeError("Missing actual GC compile input/output at line " + str(number)) from failure
        if not source.endswith(".c") or not object_path.endswith(".o"):
            raise RuntimeError("Unexpected actual GC compile input/output at line " + str(number))
        total += 1
        member = Path(object_path).name
        if member not in expected:
            continue  # The unchanged default make target also builds libcord.
        if member in records:
            raise RuntimeError("Duplicate actual compiler command for GC archive member: " + member)
        records[member] = {"line": number, "source": source, "object": object_path,
                           "optimizationFlags": ["-O2"], "command": command}
    if set(records) != expected:
        raise RuntimeError("Missing actual compiler commands for GC archive members: "
                           + repr(sorted(expected - set(records))))
    return {"observedNativeCompileCommands": total, "archiveMemberCount": len(members),
            "archiveCompileCommands": [records[name] for name in members]}


def verify_build_optimization(library, log, cc, flags, env=None):
    members = [name for name in output("ar", "-t", library).splitlines() if not name.startswith("__.SYMDEF")]
    evidence = inspect_compile_commands(Path(log).read_text(), cc, members)
    evidence.update(compiler=verify_compiler_optimization(cc, flags, env),
                    buildLogSHA256=digest(log), requiredOptimization="-O2",
                    scope="Compiler predefines plus executed libtool compile commands for every GC archive member; not runtime performance evidence")
    return evidence


def source_archive(item, stem):
    archive = PORT / ".cache/downloads" / (stem + "-" + item["commit"] + ".tar.gz")
    archive.parent.mkdir(parents=True, exist_ok=True)
    if not archive.exists():
        request = urllib.request.Request(item["url"], headers={"User-Agent": "CoronaPoker-iPad-GC-build"})
        with urllib.request.urlopen(request, timeout=60) as response:
            data = response.read()
        if hashlib.sha256(data).hexdigest() != item["sha256"]:
            raise RuntimeError("Downloaded source hash mismatch: " + stem)
        temporary = archive.with_suffix(".partial")
        temporary.write_bytes(data)
        temporary.replace(archive)
    if digest(archive) != item["sha256"]:
        raise RuntimeError("Cached source hash mismatch: " + str(archive))
    return archive


def extract(archive, destination, expected_root):
    destination.mkdir(parents=True, exist_ok=True)
    with tarfile.open(archive, "r:gz") as tar:
        for member in tar.getmembers():
            if not member.name.startswith(expected_root + "/") and member.name != expected_root:
                raise RuntimeError("Unexpected archive root: " + member.name)
        tar.extractall(destination, filter="data")
    return destination / expected_root


def symbols(archive, defined):
    args = ["nm", "-gU" if defined else "-gu", str(archive)]
    text = output(*args)
    names = set()
    for line in text.splitlines():
        if line.endswith(":"):
            continue
        match = re.search(r"(?:^|\s)(_\w+)\s*$", line)
        if match:
            names.add(match.group(1)[1:])
    return names


def object_const(path, symbol):
    """Read a target constant array without executing the ARM64 object."""
    raw = Path(path).read_bytes()
    magic, _, _, _, count, _, _, _ = struct.unpack_from("<8I", raw)
    if magic != 0xFEEDFACF:
        raise RuntimeError("Expected little-endian Mach-O 64-bit object")
    cursor, sections, symtab = 32, [], None
    for _ in range(count):
        command, size = struct.unpack_from("<II", raw, cursor)
        if command == 0x19:
            number = struct.unpack_from("<I", raw, cursor + 64)[0]
            for index in range(number):
                pos = cursor + 72 + index * 80
                address, length, offset = struct.unpack_from("<QQI", raw, pos + 32)
                sections.append((address, length, offset))
        elif command == 0x2:
            symtab = struct.unpack_from("<4I", raw, cursor + 8)
        cursor += size
    if symtab is None:
        raise RuntimeError("No Mach-O symbol table")
    symoff, nsyms, stroff, strsize = symtab
    for index in range(nsyms):
        string_index, _, section, _, value = struct.unpack_from("<IBBHQ", raw, symoff + 16 * index)
        end = raw.find(b"\0", stroff + string_index, stroff + strsize)
        name = raw[stroff + string_index:end].decode()
        if name == "_" + symbol:
            address, length, offset = sections[section - 1]
            return raw[offset + value - address:offset + length]
    raise RuntimeError("Missing target constant: " + symbol)


def verify_layout(source, directory, cc, flags, lock, log, env):
    names = ["sizeof(struct GC_stack_base)", "sizeof(struct GC_Thread_Rep)", "sizeof(struct thread_stop_info)"]
    names += ["offsetof(struct GC_Thread_Rep, " + field + ")" for field in lock["layoutFields"]]
    probe = directory / "gc-layout.c"
    probe.write_text("#include <stddef.h>\n#include \"private/gc_priv.h\"\n#include \"private/pthread_support.h\"\n"
                     + "const unsigned long CPGC_layout[] = {" + ",".join(names) + "};\n")
    results = {}
    for policy in ["original", "registered"]:
        object_path = directory / ("layout-" + policy + ".o")
        policy_flags = [f for f in flags if f != lock["registeredPolicyFlag"]]
        policy_flags.append(lock[policy + "PolicyFlag"])
        includes = ["-DHAVE_CONFIG_H", "-I" + str(directory / "include"), "-I" + str(source / "include"),
                    "-I" + str(directory / "libatomic_ops/src"), "-I" + str(source / "libatomic_ops/src")]
        run([cc, *policy_flags, *includes, "-c", probe, "-o", object_path], log, env=env)
        data = object_const(object_path, "CPGC_layout")[:8 * len(names)]
        values = struct.unpack("<" + "Q" * len(names), data)
        results[policy] = dict(zip(names, values))
    if results["original"] != results["registered"]:
        raise RuntimeError("Changing discovery policy changed private thread layout")
    macro_text = output(cc, *flags, *includes, "-E", "-dM", probe, env=env)
    macros = dict(re.findall(r"^#define (\w+)[ \t]*(.*)$", macro_text, re.M))
    for name in lock["requiredFeatures"]:
        if name not in macros:
            raise RuntimeError("Missing original collector feature: " + name)
    if macros.get("MARK_DESCR_OFFSET") != "24" or "GC_NO_THREADS_DISCOVERY" not in macros or "GC_DISCOVER_TASK_THREADS" in macros:
        raise RuntimeError("Collector feature/policy preprocessor mismatch")
    results["verifiedFeatures"] = {name: macros[name] for name in [*lock["requiredFeatures"], "MARK_DESCR_OFFSET", "GC_NO_THREADS_DISCOVERY"]}
    return results


def platform_info(library, expected):
    text = output("otool", "-l", library)
    platforms = re.findall(r"\bplatform\s+(\w+)", text)
    expected_id = "2" if expected == "ios" else "7"
    legacy_ios = len(re.findall(r"\bcmd LC_VERSION_MIN_IPHONEOS\b", text))
    members = [name for name in output("ar", "-t", library).splitlines() if not name.startswith("__.SYMDEF")]
    if set(platforms) - {expected_id, "IOS" if expected == "ios" else "IOSSIMULATOR"} or (legacy_ios and expected != "ios"):
        raise RuntimeError("Wrong Mach-O platform in GC archive: " + str(set(platforms)))
    if len(platforms) + legacy_ios != len(members):
        raise RuntimeError("Every archive object must declare its target platform")
    if output("lipo", "-archs", library) != "arm64":
        raise RuntimeError("Wrong architecture in GC archive")
    return {"architecture": "arm64", "platforms": sorted(set(platforms)),
            "legacyIOSLoadCommands": legacy_ios, "objectCount": len(members)}


def build():
    lock = json.loads(LOCK_PATH.read_text())
    verify_optimization_flags(lock["commonCFlags"], "pinned commonCFlags")
    verify_compiler_environment()
    archive = source_archive(lock["gc"], "bdwgc")
    atomic_archive = source_archive(lock["atomicOps"], "libatomic_ops")
    cc = output("xcrun", "--find", "clang")
    sdk_paths = {arch: output("xcrun", "--sdk", sdk, "--show-sdk-path")
                 for arch, (sdk, _) in ARCHITECTURES.items()}
    sdk_file_hashes = tree_hashes(ORIGINAL_SDK)
    original = {}
    for arch, (_, sdk_arch) in ARCHITECTURES.items():
        original[arch] = sdk_file_hashes.get("lib/vm/ios/" + sdk_arch + "/libgc.a")
        if original[arch] != lock["originalLibraries"][arch]:
            raise RuntimeError("Original SDK collector changed: " + arch)
    identity = {"lockSHA256": digest(LOCK_PATH), "builderSHA256": digest(__file__),
                "getterSHA256": hashlib.sha256(GETTER.encode()).hexdigest(),
                "originalSDKFilesSHA256": hashlib.sha256(json.dumps(sdk_file_hashes, sort_keys=True).encode()).hexdigest(),
                "clang": output(cc, "--version"), "sdkPaths": sdk_paths,
                "sdkVersions": {arch: output("xcrun", "--sdk", sdk, "--show-sdk-version")
                                for arch, (sdk, _) in ARCHITECTURES.items()}}
    fingerprint = hashlib.sha256(json.dumps(identity, sort_keys=True).encode()).hexdigest()
    root = PORT / "build/gc-registered" / fingerprint[:16]
    manifest_path = root / "manifest.json"
    sdk_home = root / "sdk"
    if manifest_path.exists():
        previous = json.loads(manifest_path.read_text())
        if previous.get("identity") == identity and all(Path(item["path"]).is_file()
                and digest(item["path"]) == item["sha256"]
                and Path(item["sdkClonePath"]).is_file()
                and digest(item["sdkClonePath"]) == item["sha256"] for item in previous["libraries"].values()):
            verify_sdk_clone(sdk_home, sdk_file_hashes, previous["libraries"])
            for item in previous["libraries"].values():
                evidence = verify_build_optimization(item["path"], item["buildLog"], cc, item["flags"])
                if evidence != item.get("optimization"):
                    raise RuntimeError("Completed GC build optimization evidence changed; preserve its fingerprint")
            return {"sdkHome": str(sdk_home), "libraries": previous["libraries"],
                    "manifestPath": str(manifest_path)}
        raise RuntimeError("Completed GC build changed; preserve it and use a fresh fingerprint")
    root.mkdir(parents=True, exist_ok=True)
    source = root / ("bdwgc-" + lock["gc"]["commit"])
    if source.exists():
        shutil.rmtree(source)
    source = extract(archive, root, source.name)
    atomic = root / ("libatomic_ops-" + lock["atomicOps"]["commit"])
    if atomic.exists():
        shutil.rmtree(atomic)
    atomic = extract(atomic_archive, root, atomic.name)
    atomic_link = source / "libatomic_ops"
    if atomic_link.exists() or atomic_link.is_symlink():
        if atomic_link.is_dir() and not atomic_link.is_symlink():
            shutil.rmtree(atomic_link)
        else:
            atomic_link.unlink()
    atomic_link.symlink_to(atomic)
    darwin = source / "darwin_stop_world.c"
    unpatched_hash = digest(darwin)
    darwin.write_text(darwin.read_text() + GETTER)
    libraries = {}
    env = {key: value for key, value in os.environ.items()
           if key not in {"CC", "CXX", "CFLAGS", "CPPFLAGS", "LDFLAGS", "SDKROOT", "MACOSX_DEPLOYMENT_TARGET",
                          "IPHONEOS_DEPLOYMENT_TARGET", "GC_MARKERS", "GC_NPROCS"}}
    for arch, (_, sdk_arch) in ARCHITECTURES.items():
        directory = root / arch
        if directory.exists():
            shutil.rmtree(directory)
        directory.mkdir()
        log = directory / "build.log"
        triple = lock["targetTriples"][arch]
        target = ["-target", triple, "--sysroot=" + sdk_paths[arch]]
        flags = [*lock["commonCFlags"], lock["registeredPolicyFlag"], *target]
        build_env = dict(env, CC=cc, CFLAGS=shlex.join(flags), LDFLAGS=shlex.join(["-fPIC", *target]))
        verify_compiler_optimization(cc, flags, build_env)
        prefix = directory / "installed"
        print("Building " + arch + " registered-thread GC", file=sys.stderr, flush=True)
        run([source / "configure", *lock["configureArguments"], "--prefix=" + str(prefix)], log, cwd=directory, env=build_env)
        # libtool emits commands using several writes. Serial output is required
        # to bind each archive member to an unambiguous actual compiler command.
        run(["make", "-j", "1"], log, cwd=directory, env=build_env)
        run(["make", "install"], log, cwd=directory, env=build_env)
        library = prefix / "lib/libgc.a"
        required = set(lock["requiredSymbols"])
        for sdk_library in (ORIGINAL_SDK / "lib/vm/ios" / sdk_arch).glob("*.a"):
            if sdk_library.name == "libgc.a":
                continue  # Removed discovery-internal self-references are not a RVM ABI requirement.
            required.update(name for name in symbols(sdk_library, False) if name.startswith("GC_"))
        provided = symbols(library, True)
        original_symbols = symbols(ORIGINAL_SDK / "lib/vm/ios" / sdk_arch / "libgc.a", True)
        missing = required - provided
        if missing:
            raise RuntimeError("Collector no longer supplies SDK ABI: " + str(sorted(missing)))
        if "CPGC_uses_task_threads" not in provided:
            raise RuntimeError("Missing actual policy getter")
        undefined = output("nm", "-A", "-u", library)
        darwin_references = "\n".join(line for line in undefined.splitlines() if "darwin_stop_world" in line)
        if "_task_threads" in darwin_references or "_thread_suspend" not in darwin_references or "_thread_resume" not in darwin_references:
            raise RuntimeError("Collector is not using the registered-only Darwin stop/start path")
        layout = verify_layout(source, directory, cc, flags, lock, log, build_env)
        # Layout checks append compiler commands to this log; bind its final form.
        optimization = verify_build_optimization(library, log, cc, flags, build_env)
        libraries[arch] = {"path": str(library), "sha256": digest(library), "flags": flags,
                           "triple": triple, "platform": platform_info(library, arch),
                           "requiredSDKSymbols": sorted(required), "layouts": layout,
                           "originalSDKSymbolDiff": {"removed": sorted(original_symbols - provided), "added": sorted(provided - original_symbols)},
                           "darwinStopWorldReferences": darwin_references, "buildLog": str(log),
                           "optimization": optimization}
    if not sdk_home.exists():
        run(["/bin/cp", "-cR", ORIGINAL_SDK, sdk_home], root / "clone.log")
    for arch, (_, sdk_arch) in ARCHITECTURES.items():
        target = sdk_home / "lib/vm/ios" / sdk_arch / "libgc.a"
        shutil.copy2(libraries[arch]["path"], target)
        libraries[arch]["sdkClonePath"] = str(target)
        if digest(target) != libraries[arch]["sha256"]:
            raise RuntimeError("SDK clone collector copy mismatch")
        original_path = ORIGINAL_SDK / "lib/vm/ios" / sdk_arch / "libgc.a"
        if digest(original_path) != original[arch]:
            raise RuntimeError("Original SDK was unexpectedly changed")
    verify_sdk_clone(sdk_home, sdk_file_hashes, libraries)
    if tree_hashes(ORIGINAL_SDK) != sdk_file_hashes:
        raise RuntimeError("Original SDK files changed during the private build")
    manifest = {"kind": "registered-mobivm-gc-v1", "identity": identity,
                "sourceLock": lock, "sourceRelationship": lock["sourceRelationship"],
                "originalSDK": str(ORIGINAL_SDK), "originalLibrarySHA256": original,
                "originalSDKFileSHA256": sdk_file_hashes,
                "sdkHome": str(sdk_home), "libraries": libraries,
                "source": {"path": str(source), "darwinOriginalSHA256": unpatched_hash,
                           "darwinWithReadOnlyGetterSHA256": digest(darwin)},
                "collectorBehaviorChange": "Replace GC_DISCOVER_TASK_THREADS with GC_NO_THREADS_DISCOVERY. AArch64 DARWIN_DONT_PARSE_STACK and the registered stack scanning path are preserved.",
                "actualPolicyGetter": {"symbol": "CPGC_uses_task_threads", "abi": "int(void)", "registeredReturnValue": 0},
                "runtimeValidation": "Not run by this builder; ABI/layout/platform checks are not audio or application acceptance."}
    manifest_path.write_text(json.dumps(manifest, indent=2) + "\n")
    return {"sdkHome": str(sdk_home), "libraries": libraries, "manifestPath": str(manifest_path)}


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--json", action="store_true", help="Print the integration paths as JSON")
    arguments = parser.parse_args()
    result = build()
    if arguments.json:
        print(json.dumps(result, indent=2))
    else:
        print(result["sdkHome"])
