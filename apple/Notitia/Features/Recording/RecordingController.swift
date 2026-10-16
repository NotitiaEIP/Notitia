import Foundation
import NotitiaKit
import Observation
import SwiftData

/// Pilote un enregistrement : micro → fichier chiffré + transcription + annotations.
///
/// Un seul flux micro alimente toutes les analyses (transcription, sons, volume), horodatées sur la même
/// horloge. Un seul enregistrement à la fois pour toute l'application (il n'y a qu'un micro).
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
    /// Annotations (sons, volume) de la session en cours.
    private(set) var annotations: [TranscriptAnnotation] = []
    /// Erreur du moteur de transcription. Elle est affichée à part : elle n'entre jamais dans la transcription.
    private(set) var transcriptionError: String?
    private(set) var recordingID: UUID?
    private(set) var noteID: UUID?
    private(set) var engineName = ""
    /// Enregistrements dont le ton est en cours d'analyse.
    private(set) var toneAnalysisInProgress: Set<UUID> = []

    var isActive: Bool { state == .recording || state == .paused || state == .starting }

    @ObservationIgnored private let vault: AudioVault
    @ObservationIgnored private let paths: AppPaths
    @ObservationIgnored private var recorder: AudioRecorder?
    @ObservationIgnored private var temporaryFile: URL?
    @ObservationIgnored private var pumpTask: Task<Void, Never>?
    @ObservationIgnored private var transcriptionTask: Task<Void, Never>?
    @ObservationIgnored private var detectionTasks: [Task<Void, Never>] = []
    /// Mesures de la voix pour distinguer les locuteurs : en mémoire seulement, jetées à l'arrêt.
    @ObservationIgnored private var voiceTask: Task<[VoiceBlock], Never>?
    @ObservationIgnored private var expectedSpeakers: Int?
    /// Base où l'enregistrement a été créé. On y relit le `Recording` à l'arrêt, par son identifiant : une référence
    /// faible à un modèle SwiftData peut tomber à `nil` après la sauvegarde, et la transcription était alors perdue.
    @ObservationIgnored private var context: ModelContext?

    init(vault: AudioVault, paths: AppPaths) {
        self.vault = vault
        self.paths = paths
    }

    /// Démarre l'enregistrement dans `note`. Le consentement a été confirmé par l'appelant.
    func start(note: Note, context: ModelContext, consentConfirmedAt: Date, expectedSpeakers: Int? = nil) async throws -> Recording {
        guard state == .idle else { throw AudioRecorderError.alreadyRecording }
        state = .starting
        do {
            guard await AudioRecorder.requestPermission() else { throw AudioRecorderError.permissionDenied }

            // Les tags de la note aident le moteur à reconnaître les noms propres et le jargon.
            let engine = EngineCatalog.transcriptionEngine(id: Preferences.transcriptionEngineID, contextualStrings: note.tags.map(\.name))
            let localeID = Preferences.transcriptionLocale
            let recording = Recording(localeIdentifier: localeID, engineName: engine.descriptor.displayName, consentConfirmedAt: consentConfirmedAt, note: note)
            context.insert(recording)

            // Fichier en clair temporaire, dans le conteneur ; chiffré puis supprimé à l'arrêt.
            let file = FileManager.default.temporaryDirectory.appending(path: "notitia-capture-\(recording.uid.uuidString).m4a")
            let recorder = AudioRecorder()
            let audio = try recorder.start(writingTo: file)

            self.recorder = recorder
            self.temporaryFile = file
            self.context = context
            self.expectedSpeakers = expectedSpeakers
            self.recordingID = recording.uid
            self.noteID = note.uid
            self.engineName = engine.descriptor.displayName
            self.segments = []
            self.annotations = []
            self.transcriptionError = nil
            self.elapsed = 0
            self.state = .recording

            // Un flux par analyse : chacune reçoit tous les blocs audio.
            let (engineAudio, engineInput) = AsyncStream.makeStream(of: AudioChunk.self, bufferingPolicy: .bufferingNewest(256))
            var inputs = [engineInput]
            let detectors: [any AnnotationDetector] = Preferences.annotateSounds ? [SoundEventDetector(), LevelAnnotationDetector()] : []
            detectionTasks = detectors.map { detector in
                let (detectorAudio, detectorInput) = AsyncStream.makeStream(of: AudioChunk.self, bufferingPolicy: .bufferingNewest(256))
                inputs.append(detectorInput)
                return Task { [weak self] in
                    // Une analyse des sons qui échoue ne doit pas interrompre l'enregistrement.
                    do {
                        for try await annotation in detector.detect(audio: detectorAudio) {
                            self?.annotations.append(annotation)
                        }
                    } catch {}
                }
            }

            if Preferences.separateSpeakers {
                let (voiceAudio, voiceInput) = AsyncStream.makeStream(of: AudioChunk.self, bufferingPolicy: .bufferingNewest(256))
                inputs.append(voiceInput)
                voiceTask = Task { await VoiceFeatureCollector.collect(voiceAudio) }
            }

            let allInputs = inputs
            pumpTask = Task { [weak self] in
                for await chunk in audio {
                    self?.level = chunk.level
                    self?.elapsed += chunk.duration
                    allInputs.forEach { $0.yield(chunk) }
                }
                allInputs.forEach { $0.finish() }
                self?.level = 0
            }
            transcriptionTask = Task { [weak self] in
                do {
                    for try await segment in engine.transcribe(audio: engineAudio, locale: Locale(identifier: localeID)) {
                        self?.upsert(segment)
                    }
                } catch {
                    self?.transcriptionError = error.localizedDescription
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

    /// Arrête, chiffre l'audio, enregistre la transcription et ses annotations, puis fige l'original.
    func stop() async {
        guard isActive, let recorder else { return }
        state = .finishing
        let duration = recorder.stop()
        await pumpTask?.value
        await transcriptionTask?.value
        for task in detectionTasks { await task.value }
        let voice = await voiceTask?.value ?? []

        if let file = temporaryFile, let recording = currentRecording() {
            recording.duration = duration
            let finals = segments.filter { !$0.text.isEmpty }.map { segment in
                var final = segment
                final.isFinal = true
                return final
            }
            // Les locuteurs sont attribués avant de sceller : ils font partie de la transcription d'origine.
            let options = SpeakerDiarization.Options(expectedSpeakers: expectedSpeakers)
            recording.segments = voice.isEmpty ? finals : await Task.detached {
                SpeakerDiarization.label(finals, voice: voice, options: options)
            }.value
            recording.annotations = annotations.mergingOverlaps()
            // Empreinte de la transcription d'origine (texte + sons captés).
            recording.seal()
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
            try? context?.save()

            if Preferences.analyzeTone { analyzeTone(of: recording) }
        } else if let file = temporaryFile {
            // Note supprimée pendant l'enregistrement : rien à rattacher, et l'audio en clair ne doit pas rester.
            try? FileManager.default.removeItem(at: file)
        }

        self.recorder = nil
        self.temporaryFile = nil
        self.context = nil
        self.pumpTask = nil
        self.transcriptionTask = nil
        self.detectionTasks = []
        self.voiceTask = nil
        self.expectedSpeakers = nil
        self.recordingID = nil
        self.noteID = nil
        self.annotations = []
        self.transcriptionError = nil
        self.state = .idle
    }

    /// Analyse le ton des passages (texte seulement) et remplace les annotations de ton précédentes.
    /// Ne modifie pas l'empreinte : le ton est une interprétation, pas un élément capté.
    func analyzeTone(of recording: Recording) {
        let id = recording.uid
        guard !toneAnalysisInProgress.contains(id) else { return }
        toneAnalysisInProgress.insert(id)
        let segments = recording.segments
        let locale = Locale(identifier: recording.localeIdentifier)
        Task { [weak self] in
            let tones = await ToneAnalyzer().analyze(segments, locale: locale)
            if recording.modelContext != nil {
                recording.annotations = recording.annotations.filter { $0.kind != .tone } + tones
                try? recording.modelContext?.save()
            }
            self?.toneAnalysisInProgress.remove(id)
        }
    }

    private func currentRecording() -> Recording? {
        guard let context, let id = recordingID else { return nil }
        return try? context.fetch(FetchDescriptor<Recording>(predicate: #Predicate { $0.uid == id })).first
    }

    private func upsert(_ segment: TranscriptSegment) {
        if let index = segments.firstIndex(where: { $0.id == segment.id }) {
            segments[index] = segment
        } else {
            segments.append(segment)
        }
    }
}
