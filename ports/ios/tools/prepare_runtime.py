#!/usr/bin/env python3
"""Derive the pinned MobiVM runtime with its ForkJoin/Thread layout corrected.

The 3.0 snapshot imports OpenJDK ForkJoinWorkerThread but keeps RoboVM's Android
ThreadLocal.Values layout. Java access-control contexts are absent from its
Thread implementation. Game identity/authentication checks are unaffected.
"""
import copy
import hashlib
import json
import os
import subprocess
import struct
import tempfile
import zipfile
from pathlib import Path
from toolchain import jdk_home

PORT = Path(__file__).resolve().parents[1]
SDK = PORT / ".cache/toolchains/robovm-3.0.0-SNAPSHOT"
DERIVED = PORT / ".cache/derived-runtime"


def expose_field(data, name, descriptor):
    """Change only one existing constant's access flags; retain its value/code."""
    result = bytearray(data)
    cursor = 8
    count = struct.unpack_from(">H", data, cursor)[0]
    cursor += 2
    utf8 = {}
    index = 1
    while index < count:
        tag = data[cursor]
        cursor += 1
        if tag == 1:
            size = struct.unpack_from(">H", data, cursor)[0]
            cursor += 2
            utf8[index] = data[cursor:cursor + size].decode("utf-8", errors="replace")
            cursor += size
        elif tag in (3, 4, 9, 10, 11, 12, 17, 18): cursor += 4
        elif tag in (5, 6): cursor += 8; index += 1
        elif tag in (7, 8, 16, 19, 20): cursor += 2
        elif tag == 15: cursor += 3
        else: raise RuntimeError("Unexpected constant-pool tag: " + str(tag))
        index += 1
    cursor += 6  # class access, this class, superclass
    interfaces = struct.unpack_from(">H", data, cursor)[0]
    cursor += 2 + 2 * interfaces
    fields = struct.unpack_from(">H", data, cursor)[0]
    cursor += 2
    found = 0
    for _ in range(fields):
        offset = cursor
        flags, field_name, field_type, attributes = struct.unpack_from(">HHHH", data, cursor)
        cursor += 8
        if utf8[field_name] == name and utf8[field_type] == descriptor:
            if not flags & 0x0008 or not flags & 0x0010:
                raise RuntimeError("Expected static final constant")
            struct.pack_into(">H", result, offset, (flags & ~0x0006) | 0x0001)
            found += 1
        for _ in range(attributes):
            size = struct.unpack_from(">I", data, cursor + 2)[0]
            cursor += 6 + size
    if found != 1:
        raise RuntimeError("Expected exactly one " + name + " field")
    return bytes(result)


def same_bytes(first, second):
    """Compare complete contents; stat metadata alone is not a cache key."""
    if not second.exists() or first.stat().st_size != second.stat().st_size:
        return False
    with first.open("rb") as left, second.open("rb") as right:
        while True:
            block = left.read(1024 * 1024)
            if block != right.read(1024 * 1024):
                return False
            if not block:
                return True


def write_runtime_jar(original, replacements, tzdb, result):
    """Publish only changed runtime bytes, retaining MobiVM's mtime cache key."""
    result.parent.mkdir(parents=True, exist_ok=True)
    existing = {}
    if result.exists():
        try:
            with zipfile.ZipFile(result) as previous:
                existing = {entry.filename: copy.copy(entry) for entry in previous.infolist()}
        except zipfile.BadZipFile:
            # Rebuild a damaged cache from the validated original inputs.
            pass
    remaining = dict(replacements)
    remaining["java/time/zone/tzdb.dat"] = tzdb
    with tempfile.NamedTemporaryFile(prefix=".robovm-rt-", suffix=".jar",
                                     dir=result.parent, delete=False) as temporary:
        candidate = Path(temporary.name)
    try:
        with zipfile.ZipFile(original) as src, zipfile.ZipFile(candidate, "w", zipfile.ZIP_DEFLATED) as dst:
            for entry in src.infolist():
                if entry.filename == "java/math/BigInteger.class":
                    # Android kept TWO private; Java 9 made the same constant public.
                    data = expose_field(src.read(entry), "TWO", "Ljava/math/BigInteger;")
                elif entry.filename in remaining:
                    data = remaining.pop(entry.filename).read_bytes()
                else:
                    data = src.read(entry)
                dst.writestr(copy.copy(entry), data)
            # Preserve the previous ordering and metadata of added entries so
            # the first run of this helper can retain an already audited JAR.
            names = [name for name in existing if name in remaining]
            names.extend(sorted(name for name in remaining if name not in existing))
            for name in names:
                entry = existing.get(name)
                if entry is None:
                    entry = zipfile.ZipInfo(name, date_time=(1980, 1, 1, 0, 0, 0))
                    entry.compress_type = zipfile.ZIP_DEFLATED
                dst.writestr(entry, remaining[name].read_bytes())
        if not same_bytes(candidate, result):
            os.replace(candidate, result)
    finally:
        candidate.unlink(missing_ok=True)


def strong_path_encoder_source(source):
    """Keep the ICU native peer reachable in its owning thread's path cache."""
    changes = {
        "import java.lang.ref.SoftReference;\n": "",
        "    private static ThreadLocal<SoftReference<CharsetEncoder>> encoder =\n"
        "        new ThreadLocal<SoftReference<CharsetEncoder>>();":
        "    // ICU encoders own a Cleaner-managed native converter. Keep one\n"
        "    // strongly reachable per thread; the thread owns the cache lifetime.\n"
        "    private static ThreadLocal<CharsetEncoder> encoder =\n"
        "        new ThreadLocal<CharsetEncoder>();",
        "        SoftReference<CharsetEncoder> ref = encoder.get();\n"
        "        CharsetEncoder ce = (ref != null) ? ref.get() : null;":
        "        CharsetEncoder ce = encoder.get();",
        "            encoder.set(new SoftReference<CharsetEncoder>(ce));":
        "            encoder.set(ce);",
    }
    if any(source.count(marker) != 1 for marker in changes):
        raise RuntimeError("UnixPath encoder cache changed; re-audit native peer ownership")
    if (source.count("ce.reset();") != 1
            or source.count(".onMalformedInput(CodingErrorAction.REPORT)") != 1
            or source.count(".onUnmappableCharacter(CodingErrorAction.REPORT)") != 1):
        raise RuntimeError("UnixPath encoding semantics changed; re-audit the cache adaptation")
    for marker, replacement in changes.items():
        source = source.replace(marker, replacement)
    if "SoftReference" in source:
        raise RuntimeError("Unexpected UnixPath soft reference; re-audit the cache adaptation")
    return source


def prepare():
    sources = SDK / "lib/robovm-rt-sources.jar"
    original = SDK / "lib/robovm-rt.jar"
    with zipfile.ZipFile(sources) as archive:
        thread = archive.read("java/lang/Thread.java").decode()
        worker = archive.read("java/util/concurrent/ForkJoinWorkerThread.java").decode()
        local = archive.read("java/lang/ThreadLocal.java").decode()
        zones = archive.read("java/time/zone/ZoneRulesProvider.java").decode()
        sockets = archive.read("java/net/PlainSocketImpl.java").decode()
        path_source = archive.read("sun/nio/fs/UnixPath.java").decode()
    for field in ["localValues", "inheritableValues"]:
        if field not in thread:
            raise RuntimeError("Thread layout changed; re-audit the runtime patch")
    if "inheritedAccessControlContext" in thread:
        raise RuntimeError("Access-control layout changed; re-audit the runtime patch")
    modified = worker.replace('getDeclaredField("threadLocals")', 'getDeclaredField("localValues")')
    modified = modified.replace('getDeclaredField("inheritableThreadLocals")', 'getDeclaredField("inheritableValues")')
    modified = modified.replace("        U.putOrderedObject(this, INHERITEDACCESSCONTROLCONTEXT, acc);\n", "")
    modified = modified.replace("    private static final long INHERITEDACCESSCONTROLCONTEXT;\n", "")
    modified = modified.replace('            INHERITEDACCESSCONTROLCONTEXT = U.objectFieldOffset\n                (Thread.class.getDeclaredField("inheritedAccessControlContext"));\n', "")
    if modified == worker or "INHERITEDACCESSCONTROLCONTEXT" in modified:
        raise RuntimeError("Unexpected ForkJoinWorkerThread source; patch must be reviewed")
    source = DERIVED / "src/java/util/concurrent/ForkJoinWorkerThread.java"
    source.parent.mkdir(parents=True, exist_ok=True)
    source.write_text(modified)
    if "withInitial(" in local:
        raise RuntimeError("ThreadLocal API changed; re-audit the runtime patch")
    supplied = """
    /** Java 8 factory, using RoboVM's existing ThreadLocal.Values storage. */
    public static <T> ThreadLocal<T> withInitial(java.util.function.Supplier<? extends T> supplier) {
        return new SuppliedThreadLocal<T>(supplier);
    }
    private static final class SuppliedThreadLocal<T> extends ThreadLocal<T> {
        private final java.util.function.Supplier<? extends T> supplier;
        SuppliedThreadLocal(java.util.function.Supplier<? extends T> supplier) {
            this.supplier = java.util.Objects.requireNonNull(supplier);
        }
        @Override protected T initialValue() { return supplier.get(); }
    }
"""
    closing = local.rfind("}")
    local_source = DERIVED / "src/java/lang/ThreadLocal.java"
    local_source.parent.mkdir(parents=True, exist_ok=True)
    local_source.write_text(local[:closing] + supplied + local[closing:])
    # Darwin accept() inherits the listener's O_NONBLOCK flag. Android/Linux
    # does not; this imported implementation assumes an accepted blocking fd.
    # Restore that contract before OpenJDK SocketInputStream performs an idle
    # zero-timeout read. Wire bytes, encryption and timeout values stay intact.
    accept = "            FileDescriptor newfd = Libcore.os.accept(fd, peerAddress);\n"
    if sockets.count(accept) != 1 or "IoUtils.setBlocking(newfd, true)" in sockets:
        raise RuntimeError("Socket acceptance changed; re-audit the Darwin adaptation")
    patched_sockets = sockets.replace(accept, accept + '''            try {
                IoUtils.setBlocking(newfd, true);
            } catch (IOException failure) {
                IoUtils.closeQuietly(newfd);
                throw failure;
            }
''')
    socket_source = DERIVED / "src/java/net/PlainSocketImpl.java"
    socket_source.parent.mkdir(parents=True, exist_ok=True)
    socket_source.write_text(patched_sockets)
    # Build 7 faulted in ICU _reset through a corrupted converter callback while
    # UnixPath reused its soft-referenced encoder. The exact GC/free timing is
    # unobserved; a strong per-thread cache protects this native peer without
    # changing normalization, charset selection, or REPORT error handling.
    patched_path = strong_path_encoder_source(path_source)
    unix_path_source = DERIVED / "src/sun/nio/fs/UnixPath.java"
    unix_path_source.parent.mkdir(parents=True, exist_ok=True)
    unix_path_source.write_text(patched_path)
    # The JDBC 4.2 public type declarations are absent from this Android-derived
    # runtime. Copy the exact declarations from the pinned host JDK source ZIP.
    home = jdk_home()
    jdbc_sources = []
    with zipfile.ZipFile(home / "lib/src.zip") as archive:
        for name in ["SQLType", "JDBCType", "Types"]:
            path = DERIVED / ("src/java/sql/" + name + ".java")
            path.parent.mkdir(parents=True, exist_ok=True)
            path.write_bytes(archive.read("java.sql/java/sql/" + name + ".java"))
            jdbc_sources.append(path)
        tzdb = archive.read("java.base/java/time/zone/TzdbZoneRulesProvider.java").decode()
    # This snapshot's Android ICU provider has no bundled ICU time-zone data.
    # Use the pinned OpenJDK provider/data instead, retaining real DST rules.
    marker = "ZoneRulesProvider provider = new IcuZoneRulesProvider();"
    if zones.count(marker) != 1:
        raise RuntimeError("Time-zone provider changed; re-audit the runtime patch")
    zones = zones.replace(marker, "ZoneRulesProvider provider = new TzdbZoneRulesProvider();")
    zones_source = DERIVED / "src/java/time/zone/ZoneRulesProvider.java"
    zones_source.parent.mkdir(parents=True, exist_ok=True)
    zones_source.write_text(zones)
    tzdb = tzdb.replace("import jdk.internal.util.StaticProperty;", "")
    tzdb = tzdb.replace("if (obj instanceof byte[] bytes) {", "if (obj instanceof byte[]) {\n                byte[] bytes = (byte[]) obj;")
    start = tzdb.index("    public TzdbZoneRulesProvider() {")
    end = tzdb.index("\n    @Override", start)
    tzdb = tzdb[:start] + '''    public TzdbZoneRulesProvider() {
        try (java.io.InputStream input = TzdbZoneRulesProvider.class.getResourceAsStream("tzdb.dat")) {
            if (input == null) throw new java.io.IOException("Bundled tzdb.dat is missing");
            try (DataInputStream data = new DataInputStream(new BufferedInputStream(input))) { load(data); }
        } catch (Exception failure) {
            throw new ZoneRulesException("Unable to load bundled TZDB time-zone rules", failure);
        }
    }
''' + tzdb[end:]
    tzdb_source = zones_source.with_name("TzdbZoneRulesProvider.java")
    tzdb_source.write_text(tzdb)
    classes = DERIVED / "classes"
    classes.mkdir(parents=True, exist_ok=True)
    subprocess.run([str(home / "bin/javac"), "-source", "8", "-target", "8", "-Xlint:-options", "-bootclasspath", str(original),
                    "-d", str(classes), str(source), str(local_source), str(socket_source), str(unix_path_source), str(zones_source), str(tzdb_source), *map(str, jdbc_sources)], check=True)
    result = DERIVED / "robovm-rt.jar"
    replacements = {str(path.relative_to(classes)): path for path in classes.rglob("*.class")}
    write_runtime_jar(original, replacements, home / "lib/tzdb.dat", result)
    report = {"baseRuntimeSHA256": hashlib.sha256(original.read_bytes()).hexdigest(),
              "workerSourceSHA256": hashlib.sha256(worker.encode()).hexdigest(),
              "patchedWorkerSHA256": hashlib.sha256(modified.encode()).hexdigest(),
              "socketSourceSHA256": hashlib.sha256(sockets.encode()).hexdigest(),
              "patchedSocketSourceSHA256": hashlib.sha256(patched_sockets.encode()).hexdigest(),
              "unixPathSourceSHA256": hashlib.sha256(path_source.encode()).hexdigest(),
              "patchedUnixPathSourceSHA256": hashlib.sha256(patched_path.encode()).hexdigest(),
              "derivedRuntimeSHA256": hashlib.sha256(result.read_bytes()).hexdigest(),
              "changes": ["Use RoboVM localValues and inheritableValues offsets",
                          "Omit absent inheritedAccessControlContext field on this runtime",
                          "Expose the existing BigInteger.TWO constant to match Java 9+ visibility",
                          "Add ThreadLocal.withInitial with native ThreadLocal.Values storage",
                          "Reset accepted BSD sockets to blocking mode, matching the imported Linux contract",
                          "Keep one UnixPath charset encoder strongly reachable in its owning thread, protecting the Cleaner-managed ICU native peer",
                          "Add exact OpenJDK SQLType, JDBCType and Types declarations for JDBC 4.2",
                          "Use pinned OpenJDK TZDB provider and data, preserving DST and IANA zone IDs"],
              "tzdbSHA256": hashlib.sha256((home / "lib/tzdb.dat").read_bytes()).hexdigest()}
    (PORT / "validation").mkdir(parents=True, exist_ok=True)
    (PORT / "validation/runtime-derivation.json").write_text(json.dumps(report, indent=2) + "\n")
    return result


if __name__ == "__main__":
    print(prepare())
