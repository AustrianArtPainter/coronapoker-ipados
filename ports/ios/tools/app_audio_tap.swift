// Host-only, per-process simulator output capture. No microphone or global tap.
// API reference: https://developer.apple.com/documentation/coreaudio/capturing-system-audio-with-core-audio-taps
import Foundation
import CoreAudio
import AudioToolbox
import CryptoKit
import Darwin
import Synchronization

struct Failure: Error, CustomStringConvertible {
    let description: String
    init(_ message: String) { description = message }
}
func checked(_ status: OSStatus, _ operation: String) throws {
    if status != noErr { throw Failure("\(operation): OSStatus \(status)") }
}
func address(_ selector: AudioObjectPropertySelector,
             _ scope: AudioObjectPropertyScope = kAudioObjectPropertyScopeGlobal) -> AudioObjectPropertyAddress {
    AudioObjectPropertyAddress(mSelector: selector, mScope: scope, mElement: kAudioObjectPropertyElementMain)
}
func scalar<T: BitwiseCopyable>(_ object: AudioObjectID, _ selector: AudioObjectPropertySelector, _ initial: T,
                               _ scope: AudioObjectPropertyScope = kAudioObjectPropertyScopeGlobal) throws -> T {
    var key = address(selector, scope), value = initial, size = UInt32(MemoryLayout<T>.size)
    try checked(AudioObjectGetPropertyData(object, &key, 0, nil, &size, &value), "read scalar \(selector)")
    guard size == MemoryLayout<T>.size else { throw Failure("Unexpected scalar property size") }
    return value
}
func objectIDs(_ object: AudioObjectID, _ selector: AudioObjectPropertySelector,
               _ scope: AudioObjectPropertyScope = kAudioObjectPropertyScopeGlobal) throws -> [AudioObjectID] {
    var key = address(selector, scope), size: UInt32 = 0
    try checked(AudioObjectGetPropertyDataSize(object, &key, 0, nil, &size), "read object-list size")
    guard size % UInt32(MemoryLayout<AudioObjectID>.size) == 0 else { throw Failure("Invalid object-list size") }
    var values = [AudioObjectID](repeating: 0, count: Int(size) / MemoryLayout<AudioObjectID>.size)
    if size != 0 {
        try values.withUnsafeMutableBytes { bytes in
            try checked(AudioObjectGetPropertyData(object, &key, 0, nil, &size, bytes.baseAddress!), "read object list")
        }
    }
    guard Int(size) == values.count * MemoryLayout<AudioObjectID>.size else { throw Failure("Audio object list changed during read") }
    return values
}
func bundleID(_ object: AudioObjectID) throws -> String {
    var key = address(kAudioProcessPropertyBundleID), value: Unmanaged<CFString>?
    var size = UInt32(MemoryLayout<Unmanaged<CFString>?>.size)
    try checked(AudioObjectGetPropertyData(object, &key, 0, nil, &size, &value), "read target audio bundle ID")
    guard let result = value else { throw Failure("Target audio bundle ID is missing") }
    return result.takeRetainedValue() as String
}
func retainedProperty(_ object: AudioObjectID, _ selector: AudioObjectPropertySelector) throws -> AnyObject {
    var key = address(selector), value: Unmanaged<CFTypeRef>?
    var size = UInt32(MemoryLayout<Unmanaged<CFTypeRef>?>.size)
    try checked(AudioObjectGetPropertyData(object, &key, 0, nil, &size, &value), "read retained property \(selector)")
    guard size == MemoryLayout<Unmanaged<CFTypeRef>?>.size, let value else { throw Failure("Missing retained property \(selector)") }
    return value.takeRetainedValue()
}
func aggregateReadback(_ aggregate: AudioObjectID, diagnostics result: inout [String: Any]) throws -> Bool {
    result["aggregateObjectID"] = aggregate
    for (name, selector) in [("clockDeviceUID", kAudioAggregateDevicePropertyClockDevice),
                             ("mainSubDeviceUID", kAudioAggregateDevicePropertyMainSubDevice)] {
        do {
            guard let value = try retainedProperty(aggregate, selector) as? String else { throw Failure("Expected a UID string") }
            result[name] = value
        } catch { result[name + "ReadError"] = String(describing: error) }
    }
    do {
        guard let value = try retainedProperty(aggregate, kAudioAggregateDevicePropertyComposition) as? [String: Any],
              JSONSerialization.isValidJSONObject(value) else { throw Failure("Expected a JSON-compatible aggregate composition") }
        result["composition"] = value
    } catch { result["compositionReadError"] = String(describing: error) }
    // Keep raw list/size/status evidence even when a newly created private
    // sub-tap is not yet visible through the client AudioObject namespace.
    var key = address(kAudioAggregateDevicePropertySubTapList), size: UInt32 = 0
    let sizeStatus = AudioObjectGetPropertyDataSize(aggregate, &key, 0, nil, &size)
    result["activeSubTapListSizeOSStatus"] = sizeStatus
    result["activeSubTapListReportedByteSize"] = size
    try checked(sizeStatus, "read active sub-tap list size")
    guard size % UInt32(MemoryLayout<AudioObjectID>.size) == 0 else { throw Failure("Invalid active sub-tap list size") }
    var objects = [AudioObjectID](repeating: 0, count: Int(size) / MemoryLayout<AudioObjectID>.size)
    let dataStatus: OSStatus = objects.isEmpty ? noErr : objects.withUnsafeMutableBytes { bytes in
        AudioObjectGetPropertyData(aggregate, &key, 0, nil, &size, bytes.baseAddress!)
    }
    result["activeSubTapListDataOSStatus"] = dataStatus
    result["activeSubTapListReturnedByteSize"] = size
    result["activeSubTapObjectIDs"] = objects
    try checked(dataStatus, "read active sub-tap list")
    guard Int(size) == objects.count * MemoryLayout<AudioObjectID>.size,
          objects.count == 1, objects[0] != kAudioObjectUnknown else {
        throw Failure("Cannot uniquely identify the private aggregate's active sub-tap")
    }
    var reportedClass: AudioClassID?
    do {
        let value = try scalar(objects[0], kAudioObjectPropertyClass, AudioClassID(0))
        reportedClass = value; result["activeSubTapClassID"] = value
    } catch { result["activeSubTapClassReadError"] = String(describing: error) }
    if let reportedClass, reportedClass != kAudioSubTapClassID { throw Failure("Active sub-tap reported a different AudioObject class") }
    // Class visibility alone must not be confused with actual drift state:
    // this direct property read must succeed, or the capture remains unready.
    let enabled: UInt32
    do { enabled = try scalar(objects[0], kAudioSubTapPropertyDriftCompensation, UInt32(0)) }
    catch {
        result["nativeDriftPropertyUnavailable"] = true
        result["subTapDriftCompensationReadError"] = String(describing: error)
        throw error
    }
    guard enabled <= 1 else { throw Failure("Unknown actual sub-tap drift-compensation state") }
    result["subTapDriftCompensationEnabled"] = enabled == 1
    do { result["subTapDriftCompensationQuality"] = try scalar(objects[0], kAudioSubTapPropertyDriftCompensationQuality, UInt32(0)) }
    catch { result["subTapDriftCompensationQualityReadError"] = String(describing: error) }
    return enabled == 1
}
func strictBoolean(_ value: Any?) -> Bool? {
    guard let number = value as? NSNumber, CFGetTypeID(number) == CFBooleanGetTypeID() else { return nil }
    return number.boolValue
}
func validateTapOnlyComposition(_ composition: [String: Any], tapUID: String, requested: Bool) throws -> Bool {
    guard strictBoolean(composition[kAudioAggregateDeviceIsPrivateKey]) == true,
          let devices = composition[kAudioAggregateDeviceSubDeviceListKey] as? [Any], devices.isEmpty,
          let taps = composition[kAudioAggregateDeviceTapListKey] as? [[String: Any]], taps.count == 1,
          let uid = taps[0][kAudioSubTapUIDKey] as? String,
          let expectedUUID = UUID(uuidString: tapUID), UUID(uuidString: uid) == expectedUUID,
          let drift = strictBoolean(taps[0][kAudioSubTapDriftCompensationKey]), drift == requested else {
        throw Failure("System aggregate composition is not the exact private tap-only scope and requested drift configuration")
    }
    return drift
}
func verifiedAggregateReadback(_ aggregate: AudioObjectID, tapUID: String, requested: Bool,
                              allowCompositionOnly: Bool, diagnostics result: inout [String: Any]) throws -> Bool? {
    do {
        let enabled = try aggregateReadback(aggregate, diagnostics: &result)
        guard enabled == requested else { throw Failure("Actual sub-tap drift compensation differs from the requested control") }
        result["driftReadbackLevel"] = "native-property"
        result["implementationVerification"] = true
        return enabled
    } catch {
        guard allowCompositionOnly, result["nativeDriftPropertyUnavailable"] as? Bool == true,
              let composition = result["composition"] as? [String: Any] else { throw error }
        let drift = try validateTapOnlyComposition(composition, tapUID: tapUID, requested: requested)
        result["nativeDriftReadbackError"] = String(describing: error)
        result["driftReadbackLevel"] = "composition-only"
        result["compositionDriftCompensationEnabled"] = drift
        result["actualDriftCompensationEnabled"] = NSNull()
        result["implementationVerification"] = false
        return nil
    }
}
func executablePath(_ pid: pid_t) throws -> String {
    // PROC_PIDPATHINFO_MAXSIZE is the unimportable C macro (4 * MAXPATHLEN).
    var buffer = [CChar](repeating: 0, count: 4 * Int(MAXPATHLEN))
    guard proc_pidpath(pid, &buffer, UInt32(buffer.count)) > 0 else { throw Failure("Cannot identify target executable") }
    return URL(fileURLWithPath: String(cString: buffer)).resolvingSymlinksInPath().path
}
func processBirth(_ pid: pid_t) throws -> (UInt64, UInt64) {
    var info = proc_bsdinfo()
    guard proc_pidinfo(pid, PROC_PIDTBSDINFO, 0, &info, Int32(MemoryLayout<proc_bsdinfo>.size)) == MemoryLayout<proc_bsdinfo>.size else {
        throw Failure("Cannot identify target process start time")
    }
    return (info.pbi_start_tvsec, info.pbi_start_tvusec)
}
func sha256(_ path: String) throws -> String {
    let file = try FileHandle(forReadingFrom: URL(fileURLWithPath: path))
    defer { try? file.close() }
    var hash = SHA256()
    while let data = try file.read(upToCount: 1024 * 1024), !data.isEmpty { hash.update(data: data) }
    return hash.finalize().map { String(format: "%02x", $0) }.joined()
}
func json(_ object: [String: Any]) throws -> Data {
    var data = try JSONSerialization.data(withJSONObject: object, options: [.prettyPrinted, .sortedKeys])
    data.append(10)
    return data
}

struct Target {
    let pid: pid_t
    let executable: String
    let audioObject: AudioObjectID
    let hash: String
    let outputDevices: [AudioObjectID]
    let birth: (UInt64, UInt64)
    var metadata: [String: Any] {
        ["pid": pid, "bundleID": "com.tonikelope.coronapoker.ios", "executableSHA256": hash,
         "audioObjectIDs": [audioObject], "outputDeviceIDs": outputDevices,
         "scope": "exactly one simulator App process", "privateTap": true,
         "exclusive": false, "muteBehavior": "unmuted", "microphone": false,
         "processStartUnixSeconds": birth.0, "processStartMicroseconds": birth.1]
    }
    func revalidate() throws {
        guard try processBirth(pid) == birth, try executablePath(pid) == executable,
              try scalar(audioObject, kAudioProcessPropertyPID, pid_t(0)) == pid,
              try bundleID(audioObject) == "com.tonikelope.coronapoker.ios" else {
            throw Failure("Target identity changed; refusing to follow another process")
        }
        var matches: [AudioObjectID] = []
        for object in try objectIDs(AudioObjectID(kAudioObjectSystemObject), kAudioHardwarePropertyProcessObjectList) {
            // A disappearing/unreadable object makes uniqueness unknown; do
            // not silently treat it as belonging to a different process.
            if try scalar(object, kAudioProcessPropertyPID, pid_t(0)) == pid { matches.append(object) }
        }
        guard matches == [audioObject] else { throw Failure("Target audio ownership is no longer unique") }
    }
}
func inspect(pid: pid_t, app: String, expectedHash: String?) throws -> Target {
    let bundle = URL(fileURLWithPath: app).resolvingSymlinksInPath()
    guard bundle.path.contains("/Library/Developer/CoreSimulator/Devices/"), bundle.pathExtension == "app",
          let info = NSDictionary(contentsOf: bundle.appendingPathComponent("Info.plist")),
          info["CFBundleIdentifier"] as? String == "com.tonikelope.coronapoker.ios",
          let name = info["CFBundleExecutable"] as? String, name == "CoronaPoker" else {
        throw Failure("Expected the installed CoronaPoker simulator App bundle; shared/system processes are forbidden")
    }
    let path = bundle.appendingPathComponent(name).resolvingSymlinksInPath().path
    guard pid > 1, try executablePath(pid) == path else { throw Failure("PID does not match the specified App executable") }
    var key = address(kAudioHardwarePropertyTranslatePIDToProcessObject), process = AudioObjectID(0), wanted = pid
    var size = UInt32(MemoryLayout<AudioObjectID>.size)
    try checked(AudioObjectGetPropertyData(AudioObjectID(kAudioObjectSystemObject), &key,
        UInt32(MemoryLayout<pid_t>.size), &wanted, &size, &process), "translate target PID")
    guard process != kAudioObjectUnknown else { throw Failure("Target PID has no independent HAL audio object; no fallback capture is permitted") }
    let hash = try sha256(path)
    if let expectedHash, hash != expectedHash.lowercased() { throw Failure("App executable SHA256 differs from the pinned binary") }
    let devices = try objectIDs(process, kAudioProcessPropertyDevices, kAudioObjectPropertyScopeOutput)
    let target = Target(pid: pid, executable: path, audioObject: process, hash: hash, outputDevices: devices,
                        birth: try processBirth(pid))
    try target.revalidate()
    guard try scalar(process, kAudioObjectPropertyClass, AudioClassID(0)) == kAudioProcessClassID,
          try scalar(process, kAudioProcessPropertyIsRunningOutput, UInt32(0)) == 1, !devices.isEmpty else {
        throw Failure("Target has no verified active output stream; start game music before inspection")
    }
    return target
}

struct Chunk {
    var sequence: UInt64 = 0
    var callbackHostTime: UInt64 = 0
    var sourceHostTime: UInt64 = 0
    var sourceSampleTime: Double = 0
    var sourceFlags: UInt32 = 0
    var sourceRateScalar: Double = 0
    var frames: Int = 0
}
// One CoreAudio producer, one file-writing consumer. The callback performs no
// allocation, file IO, dispatch, locks, Java calls, or resampling.
final class Ring: @unchecked Sendable {
    let slots: Int
    let frameCapacity: Int
    let channels: Int
    let samples: UnsafeMutablePointer<Float>
    let chunks: UnsafeMutablePointer<Chunk>
    let produced = Atomic<UInt64>(0)
    let consumed = Atomic<UInt64>(0)
    let callbackCount = Atomic<UInt64>(0)
    let droppedCallbacks = Atomic<UInt64>(0)
    let droppedFrames = Atomic<UInt64>(0)
    let invalidCallbacks = Atomic<UInt64>(0)
    init(channels: Int, slots: Int = 256, frameCapacity: Int = 8192) {
        self.channels = channels; self.slots = slots; self.frameCapacity = frameCapacity
        samples = .allocate(capacity: slots * frameCapacity * channels)
        samples.initialize(repeating: 0, count: slots * frameCapacity * channels)
        chunks = .allocate(capacity: slots); chunks.initialize(repeating: Chunk(), count: slots)
    }
    deinit {
        samples.deinitialize(count: slots * frameCapacity * channels); samples.deallocate()
        chunks.deinitialize(count: slots); chunks.deallocate()
    }
    func push(_ input: UnsafePointer<AudioBufferList>, time: AudioTimeStamp, now: UInt64) {
        let sequence = callbackCount.wrappingAdd(1, ordering: .relaxed).oldValue
        let buffers = UnsafeMutableAudioBufferListPointer(UnsafeMutablePointer(mutating: input))
        if buffers.isEmpty { return }
        var frameCount = -1, channelCount = 0
        for buffer in buffers {
            let count = Int(buffer.mNumberChannels)
            guard count > 0, count <= channels, buffer.mDataByteSize == 0 || buffer.mData != nil,
                  Int(buffer.mDataByteSize) % (count * MemoryLayout<Float>.size) == 0 else {
                invalidCallbacks.wrappingAdd(1, ordering: .relaxed); return
            }
            let frames = Int(buffer.mDataByteSize) / (count * MemoryLayout<Float>.size)
            if frameCount < 0 { frameCount = frames }
            guard frames == frameCount else { invalidCallbacks.wrappingAdd(1, ordering: .relaxed); return }
            channelCount += count
        }
        // Zero-length callbacks do not contribute PCM or a timing interval.
        if frameCount == 0 { return }
        guard frameCount > 0, channelCount == channels else { invalidCallbacks.wrappingAdd(1, ordering: .relaxed); return }
        let write = produced.load(ordering: .relaxed)
        guard frameCount <= frameCapacity, write - consumed.load(ordering: .acquiring) < UInt64(slots) else {
            droppedCallbacks.wrappingAdd(1, ordering: .relaxed)
            droppedFrames.wrappingAdd(UInt64(frameCount), ordering: .relaxed); return
        }
        let slot = Int(write % UInt64(slots)), destination = samples.advanced(by: slot * frameCapacity * channels)
        var channel = 0
        for buffer in buffers {
            let count = Int(buffer.mNumberChannels), source = buffer.mData!.assumingMemoryBound(to: Float.self)
            if count == channels {
                destination.update(from: source, count: frameCount * channels)
            } else {
                for frame in 0..<frameCount {
                    for offset in 0..<count { destination[frame * channels + channel + offset] = source[frame * count + offset] }
                }
            }
            channel += count
        }
        chunks[slot] = Chunk(sequence: sequence, callbackHostTime: now, sourceHostTime: time.mHostTime,
            sourceSampleTime: time.mSampleTime, sourceFlags: time.mFlags.rawValue,
            sourceRateScalar: time.mRateScalar, frames: frameCount)
        produced.store(write + 1, ordering: .releasing)
    }
}

func capture(_ target: Target, seconds: Double, directory: String, tapDriftCompensation: Bool,
             allowCompositionOnlyDriftReadback: Bool) throws {
    let destination = URL(fileURLWithPath: directory)
    guard !FileManager.default.fileExists(atPath: destination.path) else { throw Failure("Capture directory must be new") }
    try target.revalidate()
    guard try sha256(target.executable) == target.hash else { throw Failure("App binary changed before capture") }
    try FileManager.default.createDirectory(at: destination, withIntermediateDirectories: false,
                                          attributes: [.posixPermissions: 0o700])
    let pcmURL = destination.appendingPathComponent("output.f32le")
    let timelineURL = destination.appendingPathComponent("chunks.tsv")
    guard FileManager.default.createFile(atPath: pcmURL.path, contents: nil, attributes: [.posixPermissions: 0o600]),
          FileManager.default.createFile(atPath: timelineURL.path, contents: nil, attributes: [.posixPermissions: 0o600]) else {
        throw Failure("Cannot reserve capture files")
    }
    let pcm = try FileHandle(forWritingTo: pcmURL), timeline = try FileHandle(forWritingTo: timelineURL)
    defer { try? pcm.close(); try? timeline.close() }
    var metadata = target.metadata
    metadata["captureHelperExecutableSHA256"] = try sha256(CommandLine.arguments[0])
    metadata["captureHelperProducerVersion"] = "exact-pid-tap-v5-rate-scalar"
    metadata["schema"] = 1; metadata["requestedSeconds"] = seconds; metadata["completed"] = false
    metadata["validEvidence"] = false; metadata["captureReady"] = false
    metadata["tapDriftCompensationRequested"] = tapDriftCompensation
    metadata["allowCompositionOnlyDriftReadback"] = allowCompositionOnlyDriftReadback
    metadata["tapDriftImplementationVerification"] = false
    let metadataURL = destination.appendingPathComponent("metadata.json")
    func save() throws { try json(metadata).write(to: metadataURL, options: [.atomic]); try FileManager.default.setAttributes([.posixPermissions: 0o600], ofItemAtPath: metadataURL.path) }
    try save()
    var tap = AudioObjectID(0), aggregate = AudioObjectID(0), io: AudioDeviceIOProcID?
    var started = false
    defer {
        if started { AudioDeviceStop(aggregate, io) }
        if let io { AudioDeviceDestroyIOProcID(aggregate, io) }
        if aggregate != 0 { AudioHardwareDestroyAggregateDevice(aggregate) }
        if tap != 0 { AudioHardwareDestroyProcessTap(tap) }
    }
    do {
        let description = CATapDescription(stereoMixdownOfProcesses: [target.audioObject])
        description.name = "CoronaPoker exact PID output"
        description.isPrivate = true; description.isExclusive = false; description.muteBehavior = .unmuted
        if #available(macOS 26.0, *) { description.isProcessRestoreEnabled = false }
        // This is the first permission-sensitive operation; --inspect never reaches it.
        try checked(AudioHardwareCreateProcessTap(description, &tap), "create exact-process audio tap (system-audio permission may be required)")
        let format = try scalar(tap, kAudioTapPropertyFormat, AudioStreamBasicDescription())
        guard format.mFormatID == kAudioFormatLinearPCM, format.mFormatFlags & kAudioFormatFlagIsFloat != 0,
              format.mFormatFlags & kAudioFormatFlagIsBigEndian == 0, format.mBitsPerChannel == 32,
              format.mFormatFlags & kAudioFormatFlagIsPacked != 0,
              format.mFramesPerPacket == 1,
              format.mBytesPerFrame == 4 * (format.mFormatFlags & kAudioFormatFlagIsNonInterleaved != 0 ? 1 : format.mChannelsPerFrame),
              format.mBytesPerPacket == format.mBytesPerFrame,
              (1...2).contains(format.mChannelsPerFrame), format.mSampleRate.isFinite, format.mSampleRate > 0 else {
            throw Failure("Unsupported tap format; only native Float32 mono/stereo PCM is accepted")
        }
        metadata["format"] = ["encoding": "float32le", "interleaved": true, "sampleRate": format.mSampleRate,
            "channels": format.mChannelsPerFrame, "tapFormatID": format.mFormatID, "tapFormatFlags": format.mFormatFlags,
            "tapBytesPerFrame": format.mBytesPerFrame, "tapBitsPerChannel": format.mBitsPerChannel]
        let aggregateDescription: [String: Any] = [kAudioAggregateDeviceNameKey: "CoronaPoker private capture",
            kAudioAggregateDeviceUIDKey: UUID().uuidString, kAudioAggregateDeviceIsPrivateKey: true,
            // Do not wait indefinitely if the target stops between inspection
            // and AudioDeviceStart. An empty bounded capture fails validation.
            kAudioAggregateDeviceTapAutoStartKey: false, kAudioAggregateDeviceSubDeviceListKey: [],
            kAudioAggregateDeviceTapListKey: [[kAudioSubTapUIDKey: description.uuid.uuidString,
                                              kAudioSubTapDriftCompensationKey: tapDriftCompensation]]]
        try checked(AudioHardwareCreateAggregateDevice(aggregateDescription as CFDictionary, &aggregate), "create private tap-only aggregate")
        let aggregateFormat = try scalar(aggregate, kAudioDevicePropertyStreamFormat, AudioStreamBasicDescription(), kAudioObjectPropertyScopeInput)
        guard aggregateFormat.mFormatID == format.mFormatID, aggregateFormat.mSampleRate == format.mSampleRate,
              aggregateFormat.mChannelsPerFrame == format.mChannelsPerFrame,
              aggregateFormat.mBitsPerChannel == format.mBitsPerChannel,
              aggregateFormat.mFormatFlags & kAudioFormatFlagIsFloat != 0,
              aggregateFormat.mFormatFlags & kAudioFormatFlagIsPacked != 0,
              aggregateFormat.mFormatFlags & kAudioFormatFlagIsBigEndian == 0,
              aggregateFormat.mFramesPerPacket == 1,
              aggregateFormat.mBytesPerFrame == 4 * (aggregateFormat.mFormatFlags & kAudioFormatFlagIsNonInterleaved != 0 ? 1 : aggregateFormat.mChannelsPerFrame),
              aggregateFormat.mBytesPerPacket == aggregateFormat.mBytesPerFrame else {
            throw Failure("Aggregate input format differs from the verified Float32 tap; refusing PCM reinterpretation")
        }
        metadata["aggregateInputFormatFlags"] = aggregateFormat.mFormatFlags
        let ring = Ring(channels: Int(format.mChannelsPerFrame))
        var base = mach_timebase_info_data_t(); mach_timebase_info(&base)
        metadata["machTimebase"] = ["numer": base.numer, "denom": base.denom, "clock": "mach_absolute_time"]
        metadata["bufferSlots"] = ring.slots; metadata["maximumFramesPerCallback"] = ring.frameCapacity
        try checked(AudioDeviceCreateIOProcIDWithBlock(&io, aggregate, nil, { _, input, inputTime, _, _ in
            ring.push(input, time: inputTime.pointee, now: mach_absolute_time())
        }), "create tap IO callback")
        try target.revalidate()
        try timeline.write(contentsOf: Data("sequence\tbyteOffset\tframes\tcallbackMachTime\tsourceHostTime\tsourceSampleTime\tsourceTimeFlags\tsourceRateScalar\n".utf8))
        var bytesWritten: UInt64 = 0, writtenChunks: UInt64 = 0, writtenFrames: UInt64 = 0
        var missingHostTime: UInt64 = 0, nonFiniteSamples: UInt64 = 0, nonZeroSamples: UInt64 = 0
        var peak = 0.0, firstSourceHostTime: UInt64?, lastSourceHostTime: UInt64?, lastSourceFrames = 0
        var maximumTimestampGapFrames = 0.0, previousTime: UInt64?, previousFrames = 0
        func drain() throws {
            var read = ring.consumed.load(ordering: .relaxed)
            let end = ring.produced.load(ordering: .acquiring)
            while read < end {
                let slot = Int(read % UInt64(ring.slots)), chunk = ring.chunks[slot]
                let sampleCount = chunk.frames * ring.channels, byteCount = sampleCount * MemoryLayout<Float>.size
                let pointer = ring.samples.advanced(by: slot * ring.frameCapacity * ring.channels)
                for index in 0..<sampleCount {
                    let sample = pointer[index]
                    if !sample.isFinite { nonFiniteSamples += 1 }
                    else { peak = max(peak, abs(Double(sample))); if sample != 0 { nonZeroSamples += 1 } }
                }
                let hostValid = chunk.sourceFlags & AudioTimeStampFlags.hostTimeValid.rawValue != 0
                if !hostValid { missingHostTime += 1 }
                if hostValid {
                    if firstSourceHostTime == nil { firstSourceHostTime = chunk.sourceHostTime }
                    lastSourceHostTime = chunk.sourceHostTime; lastSourceFrames = chunk.frames
                }
                if hostValid, let previousTime {
                    if chunk.sourceHostTime <= previousTime { maximumTimestampGapFrames = .infinity }
                    else {
                        let deltaSeconds = Double(chunk.sourceHostTime - previousTime) * Double(base.numer) / Double(base.denom) / 1e9
                        maximumTimestampGapFrames = max(maximumTimestampGapFrames, abs(deltaSeconds * format.mSampleRate - Double(previousFrames)))
                    }
                }
                previousTime = hostValid ? chunk.sourceHostTime : nil; previousFrames = chunk.frames
                try pcm.write(contentsOf: Data(bytesNoCopy: pointer, count: byteCount, deallocator: .none))
                let line = "\(chunk.sequence)\t\(bytesWritten)\t\(chunk.frames)\t\(chunk.callbackHostTime)\t\(chunk.sourceHostTime)\t\(chunk.sourceSampleTime)\t\(chunk.sourceFlags)\t\(chunk.sourceRateScalar)\n"
                try timeline.write(contentsOf: Data(line.utf8))
                bytesWritten += UInt64(byteCount); writtenFrames += UInt64(chunk.frames); writtenChunks += 1
                read += 1; ring.consumed.store(read, ordering: .releasing)
            }
        }
        try checked(AudioDeviceStart(aggregate, io), "start tap-only aggregate")
        started = true
        let readinessStarted = mach_absolute_time()
        var attempts: [[String: Any]] = [], ready = false, discardedPrerollChunks: UInt64 = 0
        func discardPreroll() {
            let read = ring.consumed.load(ordering: .relaxed), end = ring.produced.load(ordering: .acquiring)
            discardedPrerollChunks += end - read
            ring.consumed.store(end, ordering: .releasing)
        }
        for attempt in 1...40 {
            var diagnostics: [String: Any] = ["attempt": attempt, "machTime": mach_absolute_time()]
            do {
                let enabled = try verifiedAggregateReadback(aggregate, tapUID: description.uuid.uuidString,
                    requested: tapDriftCompensation, allowCompositionOnly: allowCompositionOnlyDriftReadback, diagnostics: &diagnostics)
                guard ring.produced.load(ordering: .acquiring) > 0 else { throw Failure("Waiting for the first valid native input callback") }
                metadata["aggregateReadbackAtStart"] = diagnostics
                metadata["tapDriftCompensationActualAtStart"] = enabled.map { $0 as Any } ?? NSNull()
                metadata["tapDriftReadbackLevelAtStart"] = diagnostics["driftReadbackLevel"]
                metadata["tapDriftReadbackLevel"] = diagnostics["driftReadbackLevel"]
                metadata["tapDriftCompositionAtStart"] = diagnostics["compositionDriftCompensationEnabled"] ?? NSNull()
                ready = true
            } catch { diagnostics["error"] = String(describing: error) }
            attempts.append(diagnostics)
            discardPreroll()
            if ready { break }
            let waited = Double(mach_absolute_time() - readinessStarted) * Double(base.numer) / Double(base.denom) / 1e9
            if waited >= 2 || attempt == 40 { break }
            Thread.sleep(forTimeInterval: 0.05)
        }
        metadata["aggregateReadbackAttemptsAtStart"] = attempts
        metadata["preReadyDiscardedChunks"] = discardedPrerollChunks
        guard ready else { throw Failure("Actual active sub-tap drift state did not become readable and match the requested control within the bounded readiness retries") }
        try target.revalidate()
        // The measurement and external trigger window starts only after actual
        // state is known. Warmup inputs never shorten the requested N seconds.
        discardPreroll()
        metadata["preReadyDiscardedChunks"] = discardedPrerollChunks
        let start = mach_absolute_time()
        metadata["startMachTime"] = start; metadata["startUnixSeconds"] = Date().timeIntervalSince1970
        metadata["captureReady"] = true
        try save()
        var lastIdentityCheck = start, pendingError: Error?
        do {
            while true {
                try drain()
                let now = mach_absolute_time(), elapsed = Double(now - start) * Double(base.numer) / Double(base.denom) / 1e9
                if elapsed >= seconds { break }
                if Double(now - lastIdentityCheck) * Double(base.numer) / Double(base.denom) >= 250_000_000 {
                    try target.revalidate(); lastIdentityCheck = now
                }
                Thread.sleep(forTimeInterval: 0.005)
            }
        } catch { pendingError = error }
        var finalAggregate: [String: Any] = [:]
        do {
            let enabled = try verifiedAggregateReadback(aggregate, tapUID: description.uuid.uuidString,
                requested: tapDriftCompensation, allowCompositionOnly: allowCompositionOnlyDriftReadback, diagnostics: &finalAggregate)
            metadata["tapDriftCompensationActualAtEnd"] = enabled.map { $0 as Any } ?? NSNull()
            metadata["tapDriftReadbackLevelAtEnd"] = finalAggregate["driftReadbackLevel"]
            metadata["tapDriftCompositionAtEnd"] = finalAggregate["compositionDriftCompensationEnabled"] ?? NSNull()
            metadata["tapDriftImplementationVerification"] = metadata["tapDriftReadbackLevelAtStart"] as? String == "native-property" && enabled != nil
            metadata["tapDriftReadbackLevel"] = metadata["tapDriftImplementationVerification"] as? Bool == true ? "native-property" : "composition-only"
        } catch { metadata["aggregateReadbackAtEndError"] = String(describing: error); if pendingError == nil { pendingError = error } }
        metadata["aggregateReadbackAtEnd"] = finalAggregate
        try checked(AudioDeviceStop(aggregate, io), "stop capture")
        started = false
        let end = mach_absolute_time()
        if pendingError == nil { do { try target.revalidate() } catch { pendingError = error } }
        try drain(); try pcm.synchronize(); try timeline.synchronize()
        var coverageValid = false
        metadata["endMachTime"] = end; metadata["writtenChunks"] = writtenChunks
        metadata["writtenFrames"] = writtenFrames; metadata["writtenBytes"] = bytesWritten
        metadata["callbackCount"] = ring.callbackCount.load(ordering: .relaxed)
        metadata["droppedCallbacks"] = ring.droppedCallbacks.load(ordering: .relaxed)
        metadata["droppedFrames"] = ring.droppedFrames.load(ordering: .relaxed)
        metadata["invalidCallbacks"] = ring.invalidCallbacks.load(ordering: .relaxed)
        metadata["missingHostTimeChunks"] = missingHostTime; metadata["nonFiniteSamples"] = nonFiniteSamples
        metadata["nonZeroSamples"] = nonZeroSamples; metadata["peakAbsoluteSample"] = peak
        metadata["signalPresent"] = nonZeroSamples > 0
        if let firstSourceHostTime, let lastSourceHostTime, lastSourceHostTime >= firstSourceHostTime {
            metadata["firstSourceHostTime"] = firstSourceHostTime; metadata["lastSourceHostTime"] = lastSourceHostTime
            let covered = Double(lastSourceHostTime - firstSourceHostTime) * Double(base.numer) / Double(base.denom) / 1e9
                + Double(lastSourceFrames) / format.mSampleRate
            let framesDuration = Double(writtenFrames) / format.mSampleRate
            let firstDelta = (Double(firstSourceHostTime) - Double(start)) * Double(base.numer) / Double(base.denom) / 1e9
            let endDelta = (Double(end) - Double(lastSourceHostTime)) * Double(base.numer) / Double(base.denom) / 1e9
                - Double(lastSourceFrames) / format.mSampleRate
            metadata["sourceCoveredSeconds"] = covered; metadata["writtenFrameSeconds"] = framesDuration
            metadata["firstSourceMinusCaptureStartSeconds"] = firstDelta
            metadata["captureStopMinusLastSourceEndSeconds"] = endDelta
            metadata["coverageEdgeToleranceSeconds"] = 0.25
            coverageValid = framesDuration >= seconds - 0.25 && covered >= seconds - 0.25
                && abs(firstDelta) <= 0.25 && abs(endDelta) <= 0.25 && abs(framesDuration - covered) <= 0.05
        }
        metadata["coverageValid"] = coverageValid
        metadata["maximumTimestampGapFrames"] = maximumTimestampGapFrames.isFinite ? maximumTimestampGapFrames as Any : "non-monotonic"
        metadata["completed"] = pendingError == nil
        metadata["validEvidence"] = pendingError == nil && writtenChunks > 1 && missingHostTime == 0 && nonFiniteSamples == 0 && nonZeroSamples > 0
            && ring.droppedCallbacks.load(ordering: .relaxed) == 0 && ring.invalidCallbacks.load(ordering: .relaxed) == 0
            && maximumTimestampGapFrames < 2 && coverageValid
        metadata["note"] = "Valid capture transport does not prove glitch-free App audio; compare PCM with the source and GC timeline. Composition-only drift readback verifies the reported configuration, not the resampler implementation or source quality."
        if let pendingError { metadata["error"] = String(describing: pendingError) }
        try save()
        if let pendingError { throw pendingError }
        guard metadata["validEvidence"] as? Bool == true else { throw Failure("Capture transport validation failed; inspect metadata before using PCM") }
    } catch {
        metadata["error"] = String(describing: error); metadata["validEvidence"] = false
        try? save(); throw error
    }
}

func selfTest() throws {
    let uid = "9C2889A1-15CA-4F40-9856-EFF8A32C4AA9"
    let composition: [String: Any] = [kAudioAggregateDeviceIsPrivateKey: true,
        kAudioAggregateDeviceSubDeviceListKey: [],
        kAudioAggregateDeviceTapListKey: [[kAudioSubTapUIDKey: uid, kAudioSubTapDriftCompensationKey: false]]]
    guard try validateTapOnlyComposition(composition, tapUID: uid, requested: false) == false else { throw Failure("Composition-only off validation failed") }
    var on = composition
    on[kAudioAggregateDeviceTapListKey] = [[kAudioSubTapUIDKey: uid, kAudioSubTapDriftCompensationKey: true]]
    guard try validateTapOnlyComposition(on, tapUID: uid, requested: true) else { throw Failure("Composition-only on validation failed") }
    var invalid: [[String: Any]] = []
    var bad = composition; bad[kAudioAggregateDeviceIsPrivateKey] = false; invalid.append(bad)
    bad = composition; bad[kAudioAggregateDeviceSubDeviceListKey] = [["uid": "hardware"]]; invalid.append(bad)
    bad = composition; bad[kAudioAggregateDeviceTapListKey] = []; invalid.append(bad)
    bad = composition; bad[kAudioAggregateDeviceTapListKey] = [[kAudioSubTapUIDKey: UUID().uuidString, kAudioSubTapDriftCompensationKey: false]]; invalid.append(bad)
    bad = composition; bad[kAudioAggregateDeviceTapListKey] = [[kAudioSubTapUIDKey: uid, kAudioSubTapDriftCompensationKey: true]]; invalid.append(bad)
    bad = composition; bad[kAudioAggregateDeviceTapListKey] = [[kAudioSubTapUIDKey: uid, kAudioSubTapDriftCompensationKey: NSNumber(value: 0)]]; invalid.append(bad)
    bad = composition; bad[kAudioAggregateDeviceTapListKey] = [[kAudioSubTapUIDKey: uid, kAudioSubTapDriftCompensationKey: false], [kAudioSubTapUIDKey: uid, kAudioSubTapDriftCompensationKey: false]]; invalid.append(bad)
    bad = composition; bad[kAudioAggregateDeviceTapListKey] = [[kAudioSubTapUIDKey: uid]]; invalid.append(bad)
    for value in invalid {
        var rejected = false
        do { _ = try validateTapOnlyComposition(value, tapUID: uid, requested: false) } catch { rejected = true }
        guard rejected else { throw Failure("Unsafe/mismatched aggregate composition was accepted") }
    }
    let ring = Ring(channels: 2, slots: 2, frameCapacity: 8)
    var values: [Float] = [1, 2, 3, 4, 5, 6]
    var stamp = AudioTimeStamp(); stamp.mHostTime = 123; stamp.mSampleTime = 456.5
    stamp.mRateScalar = 1.000125; stamp.mFlags = [.hostTimeValid, .sampleTimeValid, .rateScalarValid]
    values.withUnsafeMutableBytes { bytes in
        var list = AudioBufferList(mNumberBuffers: 1, mBuffers: AudioBuffer(mNumberChannels: 2, mDataByteSize: UInt32(bytes.count), mData: bytes.baseAddress))
        withUnsafePointer(to: &list) { pointer in
            ring.push(pointer, time: stamp, now: 456); ring.push(pointer, time: stamp, now: 457)
            ring.push(pointer, time: stamp, now: 458)
        }
    }
    guard ring.produced.load(ordering: .relaxed) == 2, ring.droppedFrames.load(ordering: .relaxed) == 3,
          ring.chunks[0].sourceHostTime == 123, ring.chunks[0].frames == 3,
          ring.chunks[0].sourceSampleTime == 456.5, ring.chunks[0].sourceRateScalar == 1.000125,
          ring.chunks[0].sourceFlags == stamp.mFlags.rawValue,
          Array(UnsafeBufferPointer(start: ring.samples, count: 6)) == values else { throw Failure("Ring interleaved/overflow self-test failed") }
    let list = AudioBufferList.allocate(maximumBuffers: 2)
    defer { free(list.unsafeMutablePointer) }
    var left: [Float] = [1, 3, 5], right: [Float] = [2, 4, 6]
    ring.consumed.store(2, ordering: .releasing)
    left.withUnsafeMutableBytes { l in right.withUnsafeMutableBytes { r in
        list[0] = AudioBuffer(mNumberChannels: 1, mDataByteSize: UInt32(l.count), mData: l.baseAddress)
        list[1] = AudioBuffer(mNumberChannels: 1, mDataByteSize: UInt32(r.count), mData: r.baseAddress)
        ring.push(UnsafePointer(list.unsafeMutablePointer), time: stamp, now: 459)
    } }
    guard ring.produced.load(ordering: .relaxed) == 3,
          ring.chunks[0].sourceRateScalar == 1.000125, ring.chunks[0].sourceFlags == stamp.mFlags.rawValue,
          Array(UnsafeBufferPointer(start: ring.samples, count: 6)) == values else { throw Failure("Ring planar self-test failed") }
    print("PASS synthetic Float32 interleaving, timestamp/rate-scalar preservation, bounded overflow and composition-only fail-closed checks; no HAL tap created")
}

func main() throws {
    var arguments = Array(CommandLine.arguments.dropFirst())
    if arguments == ["--self-test"] { try selfTest(); return }
    if arguments.isEmpty || arguments == ["--help"] {
        print("Usage: --inspect --pid PID --app-bundle PATH [--expected-sha256 HEX]\n       --capture --pid PID --app-bundle PATH --expected-sha256 HEX --seconds N --output NEW_DIRECTORY [--tap-drift-compensation on|off] [--allow-composition-only-drift-readback]\n       --self-test\n--inspect only reads identity/HAL properties. --capture creates an exact-process tap and may request System Audio Recording permission. Tap drift compensation defaults to on. Native drift readback is required unless composition-only readback is explicitly allowed; that weaker level never verifies the actual resampler implementation.")
        return
    }
    guard let mode = arguments.first, ["--inspect", "--capture"].contains(mode) else { throw Failure("Choose --inspect or --capture explicitly") }
    arguments.removeFirst()
    var options: [String: String] = [:]
    while !arguments.isEmpty {
        if arguments.first == "--allow-composition-only-drift-readback" {
            let flag = arguments.removeFirst()
            guard options[flag] == nil else { throw Failure("Duplicate composition-only option") }
            options[flag] = "true"; continue
        }
        guard arguments.count >= 2 else { throw Failure("Missing option value") }
        let key = arguments.removeFirst(), value = arguments.removeFirst()
        guard ["--pid", "--app-bundle", "--expected-sha256", "--seconds", "--output", "--tap-drift-compensation"].contains(key), options[key] == nil else { throw Failure("Unknown or duplicate option") }
        options[key] = value
    }
    guard let rawPID = options["--pid"], let pid = pid_t(rawPID), let app = options["--app-bundle"] else { throw Failure("--pid and --app-bundle are required") }
    if let hash = options["--expected-sha256"], hash.count != 64 || !hash.allSatisfy({ $0.isHexDigit }) { throw Failure("Invalid SHA256") }
    if mode == "--inspect", options["--seconds"] != nil || options["--output"] != nil || options["--tap-drift-compensation"] != nil || options["--allow-composition-only-drift-readback"] != nil { throw Failure("Inspection does not accept capture options") }
    let drift = options["--tap-drift-compensation"] ?? "on"
    guard drift == "on" || drift == "off" else { throw Failure("--tap-drift-compensation requires on or off exactly") }
    let target = try inspect(pid: pid, app: app, expectedHash: options["--expected-sha256"])
    if mode == "--inspect" { FileHandle.standardOutput.write(try json(target.metadata)); return }
    guard options["--expected-sha256"] != nil, let rawSeconds = options["--seconds"], let seconds = Double(rawSeconds),
          seconds.isFinite, seconds >= 1, seconds <= 600, let output = options["--output"] else {
        throw Failure("Capture requires a pinned SHA256, --seconds 1...600 and a new --output directory")
    }
    try capture(target, seconds: seconds, directory: output, tapDriftCompensation: drift == "on",
                allowCompositionOnlyDriftReadback: options["--allow-composition-only-drift-readback"] == "true")
}
do { try main() } catch {
    FileHandle.standardError.write(Data(("AppAudioTap: \(error)\n").utf8)); exit(1)
}
