import Foundation
import Shared

// The core as the iPhone reaches it: Swift calls the Rust library through the code UniFFI generates, and the
// shared Kotlin screens receive this object. Both sides have a MeetingSummary and a HostProbe: a bare name is
// the one of UniFFI, the one of Kotlin is written Shared.
final class RustCore: NSObject, Shared.NotitiaCore {
    func meetingSummary(path: String) -> Shared.CoreResult<Shared.MeetingSummary> {
        do {
            let summary = try readMeetingSummary(path: path)
            return Shared.CoreOk<Shared.MeetingSummary>(
                value: Shared.MeetingSummary(id: summary.id, title: summary.title, startedAtMs: summary.startedAtMs)
            )
        } catch let error as CoreError {
            return Shared.CoreFailed<Shared.MeetingSummary>(failure: failure(of: error))
        } catch {
            return Shared.CoreFailed<Shared.MeetingSummary>(failure: .broken)
        }
    }

    func ask(host: Shared.HostProbe, question: String) -> String {
        askHost(probe: HostProbeAdapter(host), question: question)
    }

    private func failure(of error: CoreError) -> Shared.CoreFailure {
        switch error {
        case .Unreadable: return .unreadable
        case .NotAMeeting: return .notameeting
        case .NewerFormat: return .newerformat
        }
    }
}

// Rust calls it from a thread of its own. Unchecked: the Kotlin object behind it may be called from any thread.
private final class HostProbeAdapter: HostProbe, @unchecked Sendable {
    private let host: Shared.HostProbe

    init(_ host: Shared.HostProbe) {
        self.host = host
    }

    func answer(question: String) -> String {
        host.answer(question: question)
    }
}
