import Foundation
import NotitiaKit
import Observation
import SwiftData

/// Pilote un enregistrement : micro → fichier chiffré + moteur de transcription.
///
/// Un seul enregistrement à la fois pour toute l'application (il n'y a qu'un micro).
@Observable
final class RecordingController {
    enum State: Equatable {
        case idle, starting, recording, paused, finishing
    }

    private(set) var state: State = .idle
    private(set) var elapsed: TimeInterval = 0
    private(set) var level: Float = 0
    /// Segments de la session en cours, dans l'ordre.
    private(set) var segments: [TranscriptSegment] = []
    private(set) var recordingID: UUID?
    private(set) var noteID: UUID?
    private(set) var engineName = ""

    var isActive: Bool { state == .recording || state == .paused || state == .starting }

    @ObservationIgnored private let vault: AudioVault
    @ObservationIgnored private let paths: AppPaths
    @ObservationIgnored private var recorder: AudioRecorder?
    @ObservationIgnored private var temporaryFile: URL?
    @ObservationIgnored private var pumpTask: Task<Void, Never>?
    @ObservationIgnored private var transcriptionTask: Task<Void, Never>?
    @ObservationIgnored private weak var recording: Recording?

    init(vault: AudioVault, paths: AppPaths) {
        self.vault = vault
        self.paths = paths
    }

    /// Démarre l'enregistrement dans `note`. Le consentement a été confirmé par l'appelant.
    func start(note: Note, context: ModelContext, consentConfirmedAt: Date) async throws -> Recording {
        guard state == .idle else { throw AudioRecorderError.alreadyRecording }
        state = .starting
        do {
            guard await AudioRecorder.requestPermission() else { throw AudioRecorderError.permissionDenied }

            let engine = EngineCatalog.transcriptionEngine(id: UserDefaults.standard.string(forKey: Preferences.Keys.transcriptionEngine) ?? "")
            let localeID = Preferences.transcriptionLocale
            let recording = Recording(localeIdentifier: localeID, engineName: engine.descriptor.displayName, consentConfirmedAt: consentConfirmedAt, note: note)
            context.insert(recording)

            // Fichier en clair temporaire, dans le conteneur ; chiffré puis supprimé à l'arrêt.
            let file = FileManager.default.temporaryDirectory.appending(path: "notitia-capture-\(recording.uid.uuidString).m4a")
            let recorder = AudioRecorder()
            let audio = try recorder.start(writingTo: file)

            self.recorder = recorder
            self.temporaryFile = file
            self.recording = recording
            self.recordingID = recording.uid
            self.noteID = note.uid
            self.engineName = engine.descriptor.displayName
            self.segments = []
            self.elapsed = 0
            self.state = .recording

            // Le flux micro alimente l'indicateur de niveau et le moteur de transcription.
            let (engineAudio, engineInput) = AsyncStream.makeStream(of: AudioChunk.self, bufferingPolicy: .bufferingNewest(256))
            pumpTask = Task { [weak self] in
                for await chunk in audio {
                    self?.level = chunk.level
                    self?.elapsed += chunk.duration
                    engineInput.yield(chunk)
                }
                engineInput.finish()
                self?.level = 0
            }
            transcriptionTask = Task { [weak self] in
                do {
                    for try await segment in engine.transcribe(audio: engineAudio, locale: Locale(identifier: localeID)) {
                        self?.upsert(segment)
                    }
                } catch {
                    self?.upsert(TranscriptSegment(text: "[Erreur de transcription : \(error.localizedDescription)]", start: self?.elapsed ?? 0, end: self?.elapsed ?? 0))
                }
            }
            try? context.save()
            return recording
        } catch {
            state = .idle
            throw error
        }
    }

    func togglePause() {
        switch state {
        case .recording:
            recorder?.pause()
            state = .paused
            level = 0
        case .paused:
            recorder?.resume()
            state = .recording
        default:
            break
        }
    }

    /// Arrête, chiffre l'audio et enregistre la transcription.
    func stop() async {
        guard isActive, let recorder else { return }
        state = .finishing
        let duration = recorder.stop()
        await pumpTask?.value
        await transcriptionTask?.value

        if let recording, let file = temporaryFile {
            recording.duration = duration
            recording.segments = segments.filter { !$0.text.isEmpty }.map { segment in
                var final = segment
                final.isFinal = true
                return final
            }
            let vault = vault
            let id = recording.uid
            let sealed: Bool = await Task.detached {
                do {
                    try vault.seal(plaintextFile: file, id: id)
                    return true
                } catch {
                    try? FileManager.default.removeItem(at: file)
                    return false
                }
            }.value
            recording.hasAudio = sealed
            recording.note?.updatedAt = .now
            try? recording.modelContext?.save()
        }

        self.recorder = nil
        self.temporaryFile = nil
        self.recording = nil
        self.pumpTask = nil
        self.transcriptionTask = nil
        self.recordingID = nil
        self.noteID = nil
        self.state = .idle
    }

    private func upsert(_ segment: TranscriptSegment) {
        if let index = segments.firstIndex(where: { $0.id == segment.id }) {
            segments[index] = segment
        } else {
            segments.append(segment)
        }
    }
}
