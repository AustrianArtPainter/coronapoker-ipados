import AVFAudio
import CryptoKit
import Darwin
import Network
import Security
import SQLite3
import UIKit

// Platform evidence only: no poker protocol, payout or game state is simulated here.
final class HostProbe: NSObject, AVAudioPlayerDelegate {
    static let shared = HostProbe()
    private let queue = DispatchQueue(label: "com.coronapoker.validation.host", qos: .userInitiated)
    private let runID = UUID().uuidString
    private var timer: DispatchSourceTimer?
    private var listener: NWListener?
    private var connections: [UUID: NWConnection] = [:]
    private var audio: AVAudioPlayer?
    private var database: OpaquePointer?
    private var directory: URL?
    private var signingKey: Curve25519.Signing.PrivateKey?
    private var ticks = 0
    private var backgroundTicks = 0
    private var commits = 0
    private var checks = 0
    private var accepted = 0
    private var interrupted = 0
    private var background = false
    private var musicPlaying = false
    private var lastTick = ProcessInfo.processInfo.systemUptime
    private var maximumGap = 0.0
    private var failure = ""
    private let port: UInt16 = 7235

    func start() {
        NotificationCenter.default.addObserver(self, selector: #selector(audioInterruption(_:)),
                                               name: AVAudioSession.interruptionNotification, object: nil)
        queue.async {
            do {
                try self.openDatabase()
                self.signingKey = try self.loadSigningKey()
                try self.startListener()
                self.startTimer()
            } catch { self.failure = String(describing: error); self.writeEvidence() }
        }
        playMusic()
    }

    func setBackground(_ value: Bool) {
        queue.async { self.background = value; self.writeEvidence() }
    }

    func status(_ completion: @escaping ([String: Any]) -> Void) {
        queue.async {
            let snapshot = self.snapshot()
            DispatchQueue.main.async { completion(snapshot) }
        }
    }

    func toggleMusic() {
        if audio?.isPlaying == true {
            audio?.pause()
            queue.async { self.musicPlaying = false; self.writeEvidence() }
        } else { playMusic() }
    }

    private func playMusic() {
        do {
            let session = AVAudioSession.sharedInstance()
            try session.setCategory(.playback, mode: .default)
            try session.setActive(true)
            if audio == nil {
                guard let file = Bundle.main.url(forResource: "background_music", withExtension: "mp3") else {
                    throw ProbeError.failure("Bundled game music is missing")
                }
                audio = try AVAudioPlayer(contentsOf: file)
                audio?.numberOfLoops = -1
                audio?.volume = 0.20
                audio?.delegate = self
            }
            guard audio?.play() == true else { throw ProbeError.failure("Audio playback did not start") }
            queue.async { self.musicPlaying = true; self.writeEvidence() }
        } catch {
            queue.async { self.musicPlaying = false; self.failure = "Audio: \(error)"; self.writeEvidence() }
        }
    }

    @objc private func audioInterruption(_ notification: Notification) {
        guard let value = notification.userInfo?[AVAudioSessionInterruptionTypeKey] as? UInt,
              let type = AVAudioSession.InterruptionType(rawValue: value) else { return }
        if type == .began {
            queue.async { self.interrupted += 1; self.musicPlaying = false; self.writeEvidence() }
        } else {
            let options = (notification.userInfo?[AVAudioSessionInterruptionOptionKey] as? UInt) ?? 0
            if AVAudioSession.InterruptionOptions(rawValue: options).contains(.shouldResume) { playMusic() }
        }
    }

    private func openDatabase() throws {
        let files = FileManager.default
        let support = try files.url(for: .applicationSupportDirectory, in: .userDomainMask,
                                    appropriateFor: nil, create: true)
        let dir = support.appendingPathComponent("HostValidation", isDirectory: true)
        try files.createDirectory(at: dir, withIntermediateDirectories: true,
                                  attributes: [.protectionKey: FileProtectionType.completeUntilFirstUserAuthentication])
        directory = dir
        let db = dir.appendingPathComponent("evidence.sqlite")
        guard sqlite3_open(db.path, &database) == SQLITE_OK else { throw ProbeError.failure("SQLite open failed") }
        try files.setAttributes([.protectionKey: FileProtectionType.completeUntilFirstUserAuthentication], ofItemAtPath: db.path)
        try sql("PRAGMA journal_mode=DELETE; PRAGMA synchronous=FULL; PRAGMA foreign_keys=ON; PRAGMA busy_timeout=5000;")
        try sql("CREATE TABLE IF NOT EXISTS ticks (run_id TEXT NOT NULL, sequence INTEGER NOT NULL, uptime REAL NOT NULL, background INTEGER NOT NULL, signature_verified INTEGER NOT NULL, PRIMARY KEY(run_id, sequence));")
    }

    private func sql(_ statement: String) throws {
        guard sqlite3_exec(database, statement, nil, nil, nil) == SQLITE_OK else {
            throw ProbeError.failure("SQLite: \(String(cString: sqlite3_errmsg(database)))")
        }
    }

    private func loadSigningKey() throws -> Curve25519.Signing.PrivateKey {
        let query: [String: Any] = [kSecClass as String: kSecClassGenericPassword,
                                   kSecAttrService as String: "CoronaPokerHostValidation",
                                   kSecAttrAccount as String: "Ed25519",
                                   kSecReturnData as String: true,
                                   kSecMatchLimit as String: kSecMatchLimitOne]
        var result: CFTypeRef?
        let status = SecItemCopyMatching(query as CFDictionary, &result)
        if status == errSecSuccess, let bytes = result as? Data {
            return try Curve25519.Signing.PrivateKey(rawRepresentation: bytes)
        }
        guard status == errSecItemNotFound else { throw ProbeError.failure("Keychain read: \(status)") }
        let key = Curve25519.Signing.PrivateKey()
        let insert: [String: Any] = [kSecClass as String: kSecClassGenericPassword,
                                    kSecAttrService as String: "CoronaPokerHostValidation",
                                    kSecAttrAccount as String: "Ed25519",
                                    kSecAttrAccessible as String: kSecAttrAccessibleAfterFirstUnlockThisDeviceOnly,
                                    kSecValueData as String: key.rawRepresentation]
        let stored = SecItemAdd(insert as CFDictionary, nil)
        guard stored == errSecSuccess else { throw ProbeError.failure("Keychain write: \(stored)") }
        return key
    }

    private func startTimer() {
        let source = DispatchSource.makeTimerSource(queue: queue)
        source.schedule(deadline: .now() + 1, repeating: 1, leeway: .milliseconds(50))
        source.setEventHandler { [weak self] in self?.tick() }
        timer = source
        source.resume()
    }

    private func tick() {
        let now = ProcessInfo.processInfo.systemUptime
        maximumGap = max(maximumGap, now - lastTick)
        lastTick = now
        ticks += 1
        if background { backgroundTicks += 1 }
        do {
            guard let key = signingKey else { throw ProbeError.failure("Signing key unavailable") }
            let message = Data("\(runID):\(ticks)".utf8)
            let signature = try key.signature(for: message)
            guard key.publicKey.isValidSignature(signature, for: message) else { throw ProbeError.failure("Ed25519 verification failed") }
            checks += 1
            try sql("BEGIN IMMEDIATE;")
            do {
                try sql("INSERT INTO ticks VALUES ('\(runID)', \(ticks), \(now), \(background ? 1 : 0), 1);")
                try sql("COMMIT;")
                commits += 1
            } catch { try? sql("ROLLBACK;"); throw error }
        } catch { failure = String(describing: error) }
        writeEvidence()
    }

    private func startListener() throws {
        let parameters = NWParameters.tcp
        parameters.allowLocalEndpointReuse = true
        let listener = try NWListener(using: parameters, on: NWEndpoint.Port(rawValue: port)!)
        listener.stateUpdateHandler = { [weak self] state in
            if case .failed(let error) = state { self?.failure = "TCP listener: \(error)"; self?.writeEvidence() }
        }
        listener.newConnectionHandler = { [weak self] connection in
            guard let self = self else { connection.cancel(); return }
            guard self.connections.count < 16 else { connection.cancel(); return }
            let id = UUID()
            self.accepted += 1
            self.connections[id] = connection
            connection.stateUpdateHandler = { [weak self] state in
                if case .failed(_) = state { self?.remove(id) }
                if case .cancelled = state { self?.connections.removeValue(forKey: id) }
            }
            connection.start(queue: self.queue)
            self.receive(connection, id: id, pending: Data())
        }
        self.listener = listener
        listener.start(queue: queue)
    }

    private func remove(_ id: UUID) { connections.removeValue(forKey: id)?.cancel() }

    private func receive(_ connection: NWConnection, id: UUID, pending: Data) {
        connection.receive(minimumIncompleteLength: 1, maximumLength: 1024) { [weak self] data, _, complete, error in
            guard let self = self else { return }
            var buffer = pending
            if let data = data { buffer.append(data) }
            guard buffer.count <= 1024, error == nil else { self.remove(id); return }
            if let end = buffer.firstIndex(of: 10) {
                let command = String(decoding: buffer[..<end], as: UTF8.self).trimmingCharacters(in: .whitespacesAndNewlines)
                let response: [String: Any] = command == "STATUS" ? self.snapshot() : ["error": "Expected STATUS"]
                guard var bytes = try? JSONSerialization.data(withJSONObject: response, options: [.sortedKeys]) else { self.remove(id); return }
                bytes.append(10)
                connection.send(content: bytes, completion: .contentProcessed { _ in self.remove(id) })
            } else if complete { self.remove(id) }
            else { self.receive(connection, id: id, pending: buffer) }
        }
    }

    private func snapshot() -> [String: Any] {
        return ["kind": "native-platform-validation", "pokerProtocolImplemented": false,
                "runID": runID, "utc": ISO8601DateFormatter().string(from: Date()),
                "uptime": ProcessInfo.processInfo.systemUptime, "port": port,
                "addresses": Self.addresses().joined(separator: ", "), "ticks": ticks,
                "backgroundTicks": backgroundTicks, "databaseCommits": commits,
                "signatureChecks": checks, "connections": accepted, "background": background,
                "musicPlaying": musicPlaying, "audioInterruptions": interrupted,
                "maximumTickGapSeconds": maximumGap, "error": failure]
    }

    private func writeEvidence() {
        guard let directory = directory else { return }
        do {
            let bytes = try JSONSerialization.data(withJSONObject: snapshot(), options: [.sortedKeys, .prettyPrinted])
            try bytes.write(to: directory.appendingPathComponent("status.json"), options: [.atomic, .completeFileProtectionUntilFirstUserAuthentication])
        } catch { failure = "Evidence write: \(error)" }
    }

    private static func addresses() -> [String] {
        var head: UnsafeMutablePointer<ifaddrs>?
        guard getifaddrs(&head) == 0 else { return [] }
        defer { freeifaddrs(head) }
        var result: [String] = []
        var cursor = head
        while let current = cursor {
            defer { cursor = current.pointee.ifa_next }
            guard let address = current.pointee.ifa_addr, address.pointee.sa_family == UInt8(AF_INET) else { continue }
            let name = String(cString: current.pointee.ifa_name)
            guard name != "lo0" else { continue }
            var buffer = [CChar](repeating: 0, count: Int(NI_MAXHOST))
            if getnameinfo(address, socklen_t(address.pointee.sa_len), &buffer, socklen_t(buffer.count), nil, 0, NI_NUMERICHOST) == 0 {
                result.append("\(name)=\(String(cString: buffer))")
            }
        }
        return result.sorted()
    }
}

private enum ProbeError: Error { case failure(String) }
