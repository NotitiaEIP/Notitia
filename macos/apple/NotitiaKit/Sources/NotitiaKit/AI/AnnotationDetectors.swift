@preconcurrency import AVFoundation
import Foundation
@preconcurrency import SoundAnalysis

/// Rires, applaudissements et cris, repérés par le classifieur de sons intégré d'Apple (SoundAnalysis).
/// Tout se fait sur l'appareil.
public struct SoundEventDetector: AnnotationDetector {
    /// Durée d'une fenêtre d'analyse (le classifieur accepte de 0,5 à 15 s ; 3 s par défaut).
    public var windowDuration: TimeInterval
    public var thresholds: SoundEventAggregator.Thresholds

    public init(windowDuration: TimeInterval = 1.5, thresholds: SoundEventAggregator.Thresholds = .init()) {
        self.windowDuration = windowDuration
        self.thresholds = thresholds
    }

    public func detect(audio: AsyncStream<AudioChunk>) -> AsyncThrowingStream<TranscriptAnnotation, any Error> {
        let windowDuration = windowDuration
        let thresholds = thresholds
        return AsyncThrowingStream { continuation in
            // L'analyse peut bloquer (contre-pression) : on la garde hors de l'acteur principal.
            let task = Task.detached(priority: .utility) {
                let observer = SoundResultsObserver(aggregator: SoundEventAggregator(thresholds: thresholds), continuation: continuation)
                var analyzer: SNAudioStreamAnalyzer?
                var format: AVAudioFormat?
                do {
                    for await chunk in audio {
                        if Task.isCancelled { break }
                        if analyzer == nil {
                            guard let mono = AVAudioFormat(standardFormatWithSampleRate: chunk.sampleRate, channels: 1) else { continue }
                            let request = try SNClassifySoundRequest(classifierIdentifier: .version1)
                            request.windowDuration = CMTime(seconds: windowDuration, preferredTimescale: 1000)
                            request.overlapFactor = 0.5
                            let streamAnalyzer = SNAudioStreamAnalyzer(format: mono)
                            try streamAnalyzer.add(request, withObserver: observer)
                            analyzer = streamAnalyzer
                            format = mono
                        }
                        guard let analyzer, let format, let buffer = chunk.pcmBuffer(format: format) else { continue }
                        analyzer.analyze(buffer, atAudioFramePosition: AVAudioFramePosition((chunk.time * chunk.sampleRate).rounded()))
                    }
                    analyzer?.completeAnalysis()
                    observer.finish()
                    continuation.finish(throwing: observer.failure)
                } catch {
                    continuation.finish(throwing: error)
                }
            }
            continuation.onTermination = { _ in task.cancel() }
        }
    }
}

/// Reçoit les résultats de SoundAnalysis (sur sa propre file) et les passe à l'agrégateur.
private final class SoundResultsObserver: NSObject, SNResultsObserving, @unchecked Sendable {
    private let lock = NSLock()
    private var aggregator: SoundEventAggregator
    private let continuation: AsyncThrowingStream<TranscriptAnnotation, any Error>.Continuation
    private(set) var failure: (any Error)?

    init(aggregator: SoundEventAggregator, continuation: AsyncThrowingStream<TranscriptAnnotation, any Error>.Continuation) {
        self.aggregator = aggregator
        self.continuation = continuation
    }

    func request(_ request: any SNRequest, didProduce result: any SNResult) {
        guard let result = result as? SNClassificationResult else { return }
        let start = result.timeRange.start.seconds
        let end = start + result.timeRange.duration.seconds
        let confidences = SoundEventAggregator.confidences(result.classifications.map { ($0.identifier, $0.confidence) })
        lock.lock()
        let closed = aggregator.add(start: start, end: end, confidences: confidences)
        lock.unlock()
        closed.forEach { continuation.yield($0) }
    }

    func request(_ request: any SNRequest, didFailWithError error: any Error) {
        lock.lock(); failure = error; lock.unlock()
    }

    func requestDidComplete(_ request: any SNRequest) {}

    func finish() {
        lock.lock()
        let closed = aggregator.finish()
        lock.unlock()
        closed.forEach { continuation.yield($0) }
    }
}

/// Longs silences et volume élevé, mesurés sur le niveau audio (voir `LevelAnalyzer`).
public struct LevelAnnotationDetector: AnnotationDetector {
    public var thresholds: LevelAnalyzer.Thresholds

    public init(thresholds: LevelAnalyzer.Thresholds = .init()) {
        self.thresholds = thresholds
    }

    public func detect(audio: AsyncStream<AudioChunk>) -> AsyncThrowingStream<TranscriptAnnotation, any Error> {
        let thresholds = thresholds
        return AsyncThrowingStream { continuation in
            let task = Task {
                var analyzer = LevelAnalyzer(thresholds: thresholds)
                for await chunk in audio {
                    if Task.isCancelled { break }
                    analyzer.add(level: chunk.level, time: chunk.time, duration: chunk.duration).forEach { continuation.yield($0) }
                }
                analyzer.finish().forEach { continuation.yield($0) }
                continuation.finish()
            }
            continuation.onTermination = { _ in task.cancel() }
        }
    }
}

extension AudioChunk {
    /// Copie les échantillons dans un tampon PCM mono Float32 au format donné (même fréquence).
    func pcmBuffer(format: AVAudioFormat) -> AVAudioPCMBuffer? {
        guard !samples.isEmpty,
              let buffer = AVAudioPCMBuffer(pcmFormat: format, frameCapacity: AVAudioFrameCount(samples.count)),
              let channel = buffer.floatChannelData?[0] else { return nil }
        samples.withUnsafeBufferPointer { channel.update(from: $0.baseAddress!, count: samples.count) }
        buffer.frameLength = AVAudioFrameCount(samples.count)
        return buffer
    }
}
