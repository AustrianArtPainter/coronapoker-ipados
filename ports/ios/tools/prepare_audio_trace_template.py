#!/usr/bin/env python3
"""Make a private Deferred Audio System Trace template without altering Xcode."""
import argparse
import copy
import hashlib
import json
import os
import plistlib
from datetime import datetime, timezone
from pathlib import Path

DEFAULT_SOURCE = Path(
    "/Applications/Xcode.app/Contents/Applications/Instruments.app/Contents/Packages/"
    "Audio.instrdst/Contents/Templates/Audio System Trace.tracetemplate")
OPTIONS_FIELDS = ("recordingMode", "windowLimit", "timeLimit", "supportsImmediateMode",
                  "supportsDeferredMode", "supportsWindowedMode")


def inspect_archive(archive):
    """Follow typed archive references, never assume a particular object index."""
    if not isinstance(archive, dict) or archive.get("$archiver") != "NSKeyedArchiver":
        raise ValueError("Source is not an NSKeyedArchiver template")
    objects, top = archive.get("$objects"), archive.get("$top")
    if not isinstance(objects, list) or not isinstance(top, dict):
        raise ValueError("Template archive objects/top are missing")

    def resolve(reference, label):
        if not isinstance(reference, plistlib.UID) or not 0 <= reference.data < len(objects):
            raise ValueError("Invalid archive reference: " + label)
        return objects[reference.data]

    def classname(obj, label):
        if not isinstance(obj, dict):
            raise ValueError("Missing typed object: " + label)
        descriptor = resolve(obj.get("$class"), label + ".$class")
        if not isinstance(descriptor, dict) or not isinstance(descriptor.get("$classname"), str):
            raise ValueError("Missing class descriptor: " + label)
        return descriptor["$classname"]

    command = resolve(top.get("templateRunCommand"), "$top.templateRunCommand")
    if classname(command, "templateRunCommand") != "PFTInstrumentCommand":
        raise ValueError("templateRunCommand is not PFTInstrumentCommand; recheck this template")
    reference = command.get("$3")
    options = resolve(reference, "PFTInstrumentCommand.$3")
    if classname(options, "recording options") != "XRRecordingOptions":
        raise ValueError("PFTInstrumentCommand.$3 is not XRRecordingOptions")
    required = {"recordingMode", "windowLimit", "timeLimit"}
    candidates = [i for i, obj in enumerate(objects)
                  if isinstance(obj, dict) and required.issubset(obj)
                  and classname(obj, "recording options candidate") == "XRRecordingOptions"]
    if candidates != [reference.data]:
        raise ValueError("Expected one unambiguous XRRecordingOptions object")
    if type(options.get("recordingMode")) is not int or options["recordingMode"] != 2:
        raise ValueError("Expected the locally verified recordingMode=2 template")
    if options.get("supportsImmediateMode") is not False or options.get("supportsDeferredMode") is not True:
        raise ValueError("Template must support Deferred and retain supportsImmediateMode=false")
    for key in ("windowLimit", "timeLimit"):
        if type(options.get(key)) is not int or options[key] < 0:
            raise ValueError("Invalid nonnegative nanosecond option: " + key)
    count = resolve(top.get("$0"), "instrument count")
    if type(count) is not int or count < 1:
        raise ValueError("Template instrument count is missing or invalid")
    instruments = []
    for index in range(count):
        instrument = resolve(top.get("$" + str(2 * index + 1)), "instrument type")
        settings = resolve(top.get("$" + str(2 * index + 2)), "instrument settings")
        if not isinstance(instrument, dict) or not isinstance(instrument.get("NS.string"), str) or not isinstance(settings, dict):
            raise ValueError("Incomplete instrument entry: " + str(index))
        instruments.append(instrument["NS.string"])
    return reference.data, options, instruments


def write_private_pair(files):
    """Reserve both new artifacts exclusively; remove only our files on failure."""
    opened = []
    try:
        for path, data in files:
            fd = os.open(path, os.O_WRONLY | os.O_CREAT | os.O_EXCL, 0o600)
            status = os.fstat(fd)
            opened.append((path, fd, status.st_dev, status.st_ino, data))
        for path, fd, _, _, data in opened:
            with os.fdopen(fd, "wb", closefd=False) as stream:
                stream.write(data)
    except Exception:
        for path, _, device, inode, _ in opened:
            try:
                status = path.lstat()
                if (status.st_dev, status.st_ino) == (device, inode):
                    path.unlink()
            except FileNotFoundError:
                pass
        raise
    finally:
        for _, fd, _, _, _ in opened:
            os.close(fd)


def prepare(source, output):
    source = source.expanduser().resolve(strict=True)
    output = output.expanduser().absolute()
    manifest_path = output.with_suffix(".manifest.json")
    if output.suffix != ".tracetemplate":
        raise ValueError("Output must be a new .tracetemplate file")
    if output.resolve() == source:
        raise ValueError("Output cannot replace the source template")
    for path in (output, manifest_path):
        if path.exists() or path.is_symlink():
            raise ValueError("Refusing to overwrite an existing artifact: " + str(path))
    original = source.read_bytes()
    archive = plistlib.loads(original)
    options_index, options, instruments = inspect_archive(archive)
    modified = copy.deepcopy(archive)
    modified["$objects"][options_index]["windowLimit"] = 0
    generated = plistlib.dumps(modified, fmt=plistlib.FMT_BINARY, sort_keys=False)
    roundtrip = plistlib.loads(generated)
    if roundtrip != modified:
        raise ValueError("Generated archive failed semantic roundtrip validation")
    _, generated_options, generated_instruments = inspect_archive(roundtrip)
    preserved = copy.deepcopy(roundtrip)
    preserved["$objects"][options_index]["windowLimit"] = options["windowLimit"]
    if preserved != archive or generated_instruments != instruments:
        raise ValueError("Template changed beyond windowLimit; no artifacts written")
    manifest = {
        "kind": "private-audio-trace-template-v1",
        "createdUTC": datetime.now(timezone.utc).isoformat(),
        "source": str(source), "sourceSHA256": hashlib.sha256(original).hexdigest(),
        "output": str(output), "outputSHA256": hashlib.sha256(generated).hexdigest(),
        "optionsLocator": "$top.templateRunCommand (PFTInstrumentCommand).$3 (XRRecordingOptions)",
        "optionsObjectIndexObserved": options_index,
        "sourceOptions": {key: options.get(key) for key in OPTIONS_FIELDS},
        "generatedOptions": {key: generated_options.get(key) for key in OPTIONS_FIELDS},
        "changedFields": ["windowLimit"] if options["windowLimit"] != 0 else [],
        "instrumentCount": len(instruments), "instrumentTypes": instruments,
        "archiveSemanticsPreservedExceptWindowLimit": True,
        "expectedRecordingMode": "Deferred",
        "actualRecordingModeVerified": False, "rowCoverageVerified": False,
        "recordingPerformed": False,
        "limitations": [
            "This prepares template configuration; it does not record or verify a device trace.",
            "The raw recordingMode value is retained. Confirm Deferred in the actual recorded run.",
            "Verify table row timestamps and coverage after a short recording before a formal capture.",
            "A window limit is a retention ceiling, not a guarantee that system buffers cover that duration."
        ]
    }
    output.parent.mkdir(parents=True, exist_ok=True)
    write_private_pair([(output, generated),
                        (manifest_path, (json.dumps(manifest, indent=2) + "\n").encode("utf-8"))])
    return {"template": str(output), "manifest": str(manifest_path),
            "sourceSHA256": manifest["sourceSHA256"], "outputSHA256": manifest["outputSHA256"],
            "instrumentCount": len(instruments), "recordingMode": generated_options["recordingMode"],
            "windowLimit": generated_options["windowLimit"],
            "actualRecordingModeVerified": False, "rowCoverageVerified": False}


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--source", type=Path, default=DEFAULT_SOURCE,
                        help="Audio System Trace source template (default: the local Xcode package)")
    parser.add_argument("--output", type=Path, required=True,
                        help="New private .tracetemplate path; its adjacent manifest must also be new")
    args = parser.parse_args()
    try:
        result = prepare(args.source, args.output)
    except (OSError, ValueError, plistlib.InvalidFileException) as failure:
        parser.exit(1, "Audio template preparation failed: " + str(failure) + "\n")
    print(json.dumps(result, indent=2))


if __name__ == "__main__":
    main()
