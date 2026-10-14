@preconcurrency import AVFoundation
import Foundation
import Speech

/// Transcription réelle avec **SpeechAnalyzer** (macOS 26) : sur l'appareil, sans Internet.
///
/// Le modèle de chaque langue est fourni par le système (`AssetInventory`). Il doit être installé
/// avant l'enregistrement, depuis Réglages › Transcription.
public struct AppleSpeechTranscriptionEngine: TranscriptionEngine {
    public static let descriptorValue = EngineDescriptor(
        id: "apple.speechanalyzer",
        displayName: "Apple — sur l'appareil",
        detail: "SpeechAnalyzer (macOS 26). Reconnaissance réelle de la parole, hors ligne et privée.",
        requiresNetwork: false,
        isAvailable: true
    )

    public var descriptor: EngineDescriptor { Self.descriptorValue }

    /// Mots à privilégier (noms propres, jargon) : par exemple les mots-clés de l'utilisateur.
    public var contextualStrings: [String]

    public init(contextualStrings: [String] = []) {
        self.contextualStrings = contextualStrings
    }

    // MARK: Modèles de langue

    public enum ModelStatus: Sendable, Equatable {
        /// L'appareil ne permet pas la transcription sur l'appareil.
        case unavailableOnDevice
        case unsupportedLocale
        case notInstalled
        case downloading
        case installed
    }

    public static func modelStatus(for locale: Locale) async -> ModelStatus {
        guard SpeechTranscriber.isAvailable else { return .unavailableOnDevice }
        guard let supported = await SpeechTranscriber.supportedLocale(equivalentTo: locale) else { return .unsupportedLocale }
        switch await AssetInventory.status(forModules: [makeTranscriber(locale: supported)]) {
        case .installed: return .installed
        case .downloading: return .downloading
        case .supported: return .notInstalled
        default: return .unsupportedLocale
        }
    }

    /// Demande au système d'installer le modèle de la langue. `onProgress` reçoit une valeur entre 0 et 1.
    public static func installModel(for locale: Locale, onProgress: @escaping @Sendable (Double) -> Void = { _ in }) async throws {
        guard SpeechTranscriber.isAvailable else { throw AppleSpeechError.unavailableOnDevice }
        guard let supported = await SpeechTranscriber.supportedLocale(equivalentTo: locale) else {
            throw AppleSpeechError.unsupportedLocale(locale.identifier)
        }
        guard let request = try await AssetInventory.assetInstallationRequest(supporting: [makeTranscriber(locale: supported)]) else {
            return // déjà installé
        }
        let progress = request.progress
        let monitor = Task {
            while !Task.isCancelled {
                onProgress(progress.fractionCompleted)
                try? await Task.sleep(for: .milliseconds(300))
            }
        }
        defer { monitor.cancel() }
        try await request.downloadAndInstall()
        onProgress(1)
    }

    static func makeTranscriber(locale: Locale) -> SpeechTranscriber {
        SpeechTranscriber(
            locale: locale,
            transcriptionOptions: [],
            reportingOptions: [.volatileResults],
            attributeOptions: [.audioTimeRange]
        )
    }

    // MARK: Transcription

    public func transcribe(audio: AsyncStream<AudioChunk>, locale: Locale) -> AsyncThrowingStream<TranscriptSegment, any Error> {
        let contextualStrings = contextualStrings
        return AsyncThrowingStream { continuation in
            let task = Task {
                do {
                    guard SpeechTranscriber.isAvailable else { throw AppleSpeechError.unavailableOnDevice }
                    guard let supported = await SpeechTranscriber.supportedLocale(equivalentTo: locale) else {
                        throw AppleSpeechError.unsupportedLocale(locale.identifier)
                    }
                    let transcriber = Self.makeTranscriber(locale: supported)
                    let modules: [any SpeechModule] = [transcriber]
                    if await AssetInventory.status(forModules: modules) != .installed {
                        // Modèle déjà présent sur le disque mais pas encore attribué à l'app : la demande le réserve
                        // sans rien télécharger. Un vrai téléchargement passe par les Réglages, jamais en pleine réunion.
                        if try await AssetInventory.assetInstallationRequest(supporting: modules) != nil {
                            throw AppleSpeechError.modelNotInstalled(Locale.current.localizedString(forIdentifier: supported.identifier) ?? supported.identifier)
                        }
                    }
                    guard let analyzerFormat = await SpeechAnalyzer.bestAvailableAudioFormat(compatibleWith: modules) else {
                        throw AppleSpeechError.noAudioFormat
                    }

                    let analyzer = SpeechAnalyzer(modules: modules, options: .init(priority: .userInitiated, modelRetention: .whileInUse))
                    if !contextualStrings.isEmpty {
                        let context = AnalysisContext()
                        context.contextualStrings[.general] = contextualStrings
                        try await analyzer.setContext(context)
                    }
                    try await analyzer.prepareToAnalyze(in: analyzerFormat)
                    let (inputs, inputContinuation) = AsyncStream.makeStream(of: AnalyzerInput.self)
                    try await analyzer.start(inputSequence: inputs)

                    let reader = Task {
                        var tracker = SpeechResultTracker()
                        for try await result in transcriber.results {
                            let start = result.range.start.seconds
                            let end = result.range.end.seconds
                            if let segment = tracker.apply(text: String(result.text.characters), start: start, end: end, isFinal: result.isFinal) {
                                continuation.yield(segment)
                            }
                        }
                    }

                    var converter: PCMConverter?
                    var isFirst = true
                    for await chunk in audio {
                        if Task.isCancelled { break }
                        guard let source = AVAudioFormat(standardFormatWithSampleRate: chunk.sampleRate, channels: 1),
                              let buffer = chunk.pcmBuffer(format: source) else { continue }
                        if converter == nil { converter = try PCMConverter(from: source, to: analyzerFormat) }
                        guard let converted = try converter?.convert(buffer) else { continue }
                        // Le premier bloc fixe l'origine des temps ; les suivants sont contigus.
                        let startTime = isFirst ? CMTime(seconds: chunk.time, preferredTimescale: CMTimeScale(analyzerFormat.sampleRate)) : nil
                        inputContinuation.yield(AnalyzerInput(buffer: converted, bufferStartTime: startTime))
                        isFirst = false
                    }
                    inputContinuation.finish()
                    if Task.isCancelled {
                        await analyzer.cancelAndFinishNow()
                        reader.cancel()
                    } else {
                        try await analyzer.finalizeAndFinishThroughEndOfInput()
                        try await reader.value
                    }
                    continuation.finish()
                } catch {
                    continuation.finish(throwing: error)
                }
            }
            continuation.onTermination = { _ in task.cancel() }
        }
    }
}

public enum AppleSpeechError: LocalizedError {
    case unavailableOnDevice
    case unsupportedLocale(String)
    case modelNotInstalled(String)
    case noAudioFormat

    public var errorDescription: String? {
        switch self {
        case .unavailableOnDevice:
            "La transcription sur l'appareil n'est pas disponible sur ce Mac."
        case .unsupportedLocale(let identifier):
            "La langue « \(identifier) » n'est pas prise en charge par la transcription d'Apple."
        case .modelNotInstalled(let language):
            "Le modèle de transcription « \(language) » n'est pas installé. Installez-le dans Réglages › Transcription."
        case .noAudioFormat:
            "Aucun format audio compatible avec la transcription."
        }
    }
}

/// Transforme la suite des résultats de SpeechTranscriber en segments.
///
/// Un résultat **provisoire** remplace le précédent (même `id`) tant que le passage n'est pas figé ;
/// un résultat **définitif** fige le segment, et le suivant en ouvre un nouveau.
public struct SpeechResultTracker: Sendable {
    private var currentID: UUID?

    public init() {}

    public mutating func apply(text: String, start: TimeInterval, end: TimeInterval, isFinal: Bool) -> TranscriptSegment? {
        let cleaned = text.trimmingCharacters(in: .whitespacesAndNewlines)
        // Rien à afficher et rien à remplacer.
        if cleaned.isEmpty && currentID == nil { return nil }
        let id = currentID ?? UUID()
        currentID = isFinal ? nil : id
        return TranscriptSegment(id: id, text: cleaned, start: start, end: end, isFinal: isFinal)
    }
}

/// Convertit les blocs du micro (Float32 mono, fréquence du matériel) vers le format du modèle.
private final class PCMConverter {
    private let converter: AVAudioConverter
    private let output: AVAudioFormat

    init(from input: AVAudioFormat, to output: AVAudioFormat) throws {
        guard let converter = AVAudioConverter(from: input, to: output) else { throw AppleSpeechError.noAudioFormat }
        self.converter = converter
        self.output = output
    }

    func convert(_ buffer: AVAudioPCMBuffer) throws -> AVAudioPCMBuffer? {
        let ratio = output.sampleRate / buffer.format.sampleRate
        let capacity = AVAudioFrameCount((Double(buffer.frameLength) * ratio).rounded(.up)) + 64
        guard let converted = AVAudioPCMBuffer(pcmFormat: output, frameCapacity: capacity) else { return nil }
        let supplied = SuppliedFlag()
        var error: NSError?
        let status = converter.convert(to: converted, error: &error) { _, inputStatus in
            if supplied.value {
                inputStatus.pointee = .noDataNow
                return nil
            }
            supplied.value = true
            inputStatus.pointee = .haveData
            return buffer
        }
        if status == .error, let error { throw error }
        return converted.frameLength > 0 ? converted : nil
    }

    private final class SuppliedFlag: @unchecked Sendable {
        var value = false
    }
}
