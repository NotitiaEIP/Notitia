import Foundation
import Shared

// The core as the iPhone reaches it: Swift calls the Rust library through the code UniFFI generates, and the
// shared Kotlin code receives this object. Bridge, BridgeListener and BridgeError are UniFFI's; what Kotlin
// declares is written Shared.
final class RustCore: NSObject, Shared.NotitiaCore {
    // Set once, by open, before any other call: Kotlin makes one call at a time.
    private var bridge: Bridge?

    func open(directory: String, listener: Shared.CoreListener) -> Shared.CoreFailure? {
        do {
            bridge = try Bridge.open(directory: directory, listener: ListenerAdapter(listener))
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

    func apply(change: String) -> Shared.CoreFailure? {
        guard let bridge = bridge else {
            return .broken
        }
        do {
            try bridge.apply(change: change)
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

// Rust calls it on the thread Kotlin asked from. Unchecked: the Kotlin object behind it may be called from any
// thread.
private final class ListenerAdapter: BridgeListener, @unchecked Sendable {
    private let listener: Shared.CoreListener

    init(_ listener: Shared.CoreListener) {
        self.listener = listener
    }

    func onEvent(event: String) {
        listener.onEvent(event: event)
    }
}
