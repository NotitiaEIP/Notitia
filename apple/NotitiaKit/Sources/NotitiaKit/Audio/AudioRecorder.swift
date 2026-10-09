@preconcurrency import AVFoundation
import Foundation

public enum AudioRecorderError: LocalizedError {
    case permissionDenied
    case noInputDevice
    case alreadyRecording

    public var errorDescription: String? {
        switch self {
        case .permissionDenied: "L'accès au microphone a été refusé. Autorisez Notitia dans Réglages Système › Confidentialité et sécurité › Microphone."
        case .noInputDevice: "Aucun microphone n'est disponible."
        case .alreadyRecording: "Un enregistrement est déjà en cours."
        }
    }
}

/// Capture le micro avec AVAudioEngine.
///
/// - écrit un fichier AAC (`.m4a`) **temporaire**, chiffré ensuite par `AudioVault` ;
/// - publie un flux d'`AudioChunk` mono pour le moteur de transcription et l'indicateur de niveau.
///
/// Indépendant de tout moteur de transcription.
public final class AudioRecorder: @unchecked Sendable {
    private let engine = AVAudioEngine()
    private let lock = NSLock()
    private var file: AVAudioFile?
    private var continuation: AsyncStream<AudioChunk>.Continuation?
    private var isPaused = false
    private var framesWritten: AVAudioFramePosition = 0
    private var sampleRate: Double = 0
    private var isRunning = false

    public init() {}

    public static var permissionStatus: AVAudioApplication.recordPermission {
        AVAudioApplication.shared.recordPermission
    }

    public static func requestPermission() async -> Bool {
        await AVAudioApplication.requestRecordPermission()
    }

    /// Démarre la capture et renvoie le flux audio.
    public func start(writingTo url: URL) throws -> AsyncStream<AudioChunk> {
        lock.lock()
        defer { lock.unlock() }
        guard !isRunning else { throw AudioRecorderError.alreadyRecording }

        let input = engine.inputNode
        let format = input.outputFormat(forBus: 0)
        guard format.channelCount > 0, format.sampleRate > 0 else { throw AudioRecorderError.noInputDevice }

        let settings: [String: Any] = [
            AVFormatIDKey: kAudioFormatMPEG4AAC,
            AVSampleRateKey: format.sampleRate,
            AVNumberOfChannelsKey: format.channelCount,
            AVEncoderBitRateKey: 64_000 * Int(format.channelCount),
        ]
        file = try AVAudioFile(forWriting: url, settings: settings, commonFormat: .pcmFormatFloat32, interleaved: false)
        sampleRate = format.sampleRate
        framesWritten = 0
        isPaused = false

        let (stream, continuation) = AsyncStream.makeStream(of: AudioChunk.self, bufferingPolicy: .bufferingNewest(64))
        self.continuation = continuation

        input.installTap(onBus: 0, bufferSize: 4096, format: format) { [weak self] buffer, _ in
            self?.handle(buffer)
        }
        engine.prepare()
        do {
            try engine.start()
        } catch {
            input.removeTap(onBus: 0)
            file = nil
            continuation.finish()
            throw error
        }
        isRunning = true
        return stream
    }

    public func pause() {
        lock.lock(); isPaused = true; lock.unlock()
    }

    public func resume() {
        lock.lock(); isPaused = false; lock.unlock()
    }

    /// Arrête la capture, ferme le fichier et renvoie la durée enregistrée (pauses exclues).
    @discardableResult
    public func stop() -> TimeInterval {
        engine.inputNode.removeTap(onBus: 0)
        engine.stop()
        lock.lock()
        defer { lock.unlock() }
        isRunning = false
        continuation?.finish()
        continuation = nil
        file = nil // la fermeture du fichier finalise l'en-tête AAC
        return sampleRate > 0 ? Double(framesWritten) / sampleRate : 0
    }

    private func handle(_ buffer: AVAudioPCMBuffer) {
        lock.lock()
        defer { lock.unlock() }
        guard !isPaused, let file else { return }
        do {
            try file.write(from: buffer)
            framesWritten += AVAudioFramePosition(buffer.frameLength)
        } catch {
            return
        }
        // Copie mono (premier canal) pour la transcription : on ne partage jamais le tampon audio.
        guard let channel = buffer.floatChannelData?[0] else { return }
        let samples = Array(UnsafeBufferPointer(start: channel, count: Int(buffer.frameLength)))
        continuation?.yield(AudioChunk(samples: samples, sampleRate: buffer.format.sampleRate))
    }
}
