import Foundation
import Shared

// The core as the iPhone reaches it: Swift calls the Rust library through the code UniFFI generates, and the
// shared Kotlin code receives this object. Bridge, BridgeListener and BridgeError are UniFFI's; what Kotlin
// declares is written Shared. Bytes cross as Data, copied whole by CoreBytesKt.
final class RustCore: NSObject, Shared.NotitiaCore {
    // Set once, by open, then read by every call: the microphone's comes from its own thread.
    private let lock = NSLock()
    private var opened: Bridge?

    private var bridge: Bridge? {
        lock.lock()
        defer { lock.unlock() }
        return opened
    }

    func open(directory: String, key: KotlinByteArray?, listener: Shared.CoreListener) -> Shared.CoreFailure? {
        do {
            let bridge = try Bridge.open(
                directory: directory,
                key: key.map { CoreBytesKt.data(bytes: $0) },
                listener: ListenerAdapter(listener)
            )
            lock.lock()
            opened = bridge
            lock.unlock()
            return nil
        } catch {
            return failure(of: error)
        }
    }

    func meeting(id: String) -> Shared.CoreReply {
        guard let bridge = bridge else {
            return Shared.CoreReply(text: nil, failure: .broken)
        }
        do {
            return Shared.CoreReply(text: try bridge.meeting(id: id), failure: nil)
        } catch {
            return Shared.CoreReply(text: nil, failure: failure(of: error))
        }
    }

    func apply(request: String) -> Shared.CoreFailure? {
        guard let bridge = bridge else {
            return .broken
        }
        do {
            try bridge.apply(request: request)
            return nil
        } catch {
            return failure(of: error)
        }
    }

    // Returns at once and never throws: the microphone's thread is never late.
    func hear(track: Int32, pcm: KotlinByteArray) {
        bridge?.hear(track: UInt8(clamping: track), pcm: CoreBytesKt.data(bytes: pcm))
    }

    func sound(id: String, fromMs: Int64, lengthMs: Int64) -> Shared.CoreSound {
        guard let bridge = bridge else {
            return Shared.CoreSound(pcm: nil, failure: .broken)
        }
        do {
            let pcm = try bridge.sound(id: id, fromMs: UInt64(clamping: fromMs), lengthMs: UInt64(clamping: lengthMs))
            return Shared.CoreSound(pcm: CoreBytesKt.bytes(data: pcm), failure: nil)
        } catch {
            return Shared.CoreSound(pcm: nil, failure: failure(of: error))
        }
    }

    func offer(offered: String, engines: Shared.CoreEngines) -> Shared.CoreFailure? {
        guard let bridge = bridge else {
            return .broken
        }
        do {
            try bridge.offer(offered: offered, engines: EnginesAdapter(NotitiaCoreKt.neverThrowing(engines: engines)))
            return nil
        } catch {
            return failure(of: error)
        }
    }

    // Anything but a BridgeError is a fault of the bridge itself: a panic of the core, say.
    private func failure(of error: Error) -> Shared.CoreFailure {
        guard let error = error as? BridgeError else {
            return .broken
        }
        switch error {
        case .NotFound: return .notfound
        case .Unreadable: return .unreadable
        case .Invalid: return .invalid
        }
    }
}

// Rust calls it on the thread Kotlin asked from, or on the recorder's. Unchecked: the Kotlin object behind it may be
// called from any thread.
private final class ListenerAdapter: BridgeListener, @unchecked Sendable {
    private let listener: Shared.CoreListener

    init(_ listener: Shared.CoreListener) {
        self.listener = listener
    }

    func onEvent(event: String) {
        listener.onEvent(event: event)
    }
}

// Rust calls it on a thread of the core, and waits for the reply. Unchecked: the Kotlin object behind it may be called
// from any thread, and neverThrowing keeps any exception on the Kotlin side.
private final class EnginesAdapter: BridgeEngines, @unchecked Sendable {
    private let engines: Shared.CoreEngines

    init(_ engines: Shared.CoreEngines) {
        self.engines = engines
    }

    func run(engine: String, request: String, sound: Data) -> String {
        engines.run(engine: engine, request: request, sound: CoreBytesKt.bytes(data: sound))
    }
}
