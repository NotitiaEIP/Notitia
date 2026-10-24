import Foundation

/// Moteur de transcription de démonstration.
///
/// Il ne reconnaît pas la parole : il « dicte » un texte d'exemple au rythme de l'audio
/// réellement capté (le temps de l'audio fait avancer le texte). Cela permet de développer
/// et de tester toute l'interface sans réseau ni modèle, en attendant le choix du moteur réel.
public struct DemoTranscriptionEngine: TranscriptionEngine {
    public static let descriptorValue = EngineDescriptor(
        id: "demo",
        displayName: "Démonstration — hors ligne",
        detail: "Rejoue un texte d'exemple au rythme de l'enregistrement. Aucune reconnaissance réelle.",
        requiresNetwork: false,
        isAvailable: true
    )

    public var descriptor: EngineDescriptor { Self.descriptorValue }

    public var script: [String]
    public var wordsPerSecond: Double

    public init(script: [String]? = nil, wordsPerSecond: Double = 2.6) {
        self.script = script ?? Self.defaultScript
        self.wordsPerSecond = wordsPerSecond
    }

    public func transcribe(audio: AsyncStream<AudioChunk>, locale: Locale) -> AsyncThrowingStream<TranscriptSegment, any Error> {
        let sentences = script.filter { !$0.isEmpty }
        let wordsPerSecond = wordsPerSecond
        return AsyncThrowingStream { continuation in
            let task = Task {
                var dictation = Dictation(sentences: sentences, wordsPerSecond: wordsPerSecond)
                for await chunk in audio {
                    if Task.isCancelled { break }
                    for segment in dictation.advance(by: chunk.duration) {
                        continuation.yield(segment)
                    }
                }
                if let last = dictation.finish() { continuation.yield(last) }
                continuation.finish()
            }
            continuation.onTermination = { _ in task.cancel() }
        }
    }

    /// Logique de dictée, séparée pour être testable sans audio.
    struct Dictation {
        let sentences: [[Substring]]
        let wordsPerSecond: Double
        var elapsed: TimeInterval = 0
        var sentenceIndex = 0
        var revealedWords = 0
        var sentenceStart: TimeInterval = 0
        var currentID = UUID()

        init(sentences: [String], wordsPerSecond: Double) {
            self.sentences = sentences.map { $0.split(separator: " ") }.filter { !$0.isEmpty }
            self.wordsPerSecond = wordsPerSecond
        }

        mutating func advance(by duration: TimeInterval) -> [TranscriptSegment] {
            guard !sentences.isEmpty else { return [] }
            elapsed += duration
            var emitted: [TranscriptSegment] = []
            var budget = Int((elapsed - sentenceStart) * wordsPerSecond) - revealedWords
            while budget > 0 {
                let words = sentences[sentenceIndex % sentences.count]
                let take = min(budget, words.count - revealedWords)
                revealedWords += take
                budget -= take
                if revealedWords >= words.count {
                    let end = sentenceStart + Double(words.count) / wordsPerSecond
                    emitted.append(segment(words, count: words.count, end: end, final: true))
                    sentenceStart = end
                    sentenceIndex += 1
                    revealedWords = 0
                    currentID = UUID()
                } else {
                    emitted.append(segment(words, count: revealedWords, end: elapsed, final: false))
                }
            }
            return emitted
        }

        /// À l'arrêt, le segment en cours devient définitif.
        mutating func finish() -> TranscriptSegment? {
            guard !sentences.isEmpty, revealedWords > 0 else { return nil }
            let words = sentences[sentenceIndex % sentences.count]
            let result = segment(words, count: revealedWords, end: elapsed, final: true)
            revealedWords = 0
            return result
        }

        private func segment(_ words: [Substring], count: Int, end: TimeInterval, final: Bool) -> TranscriptSegment {
            TranscriptSegment(
                id: currentID,
                text: words.prefix(count).joined(separator: " "),
                start: sentenceStart,
                end: end,
                isFinal: final
            )
        }
    }

    public static let defaultScript: [String] = [
        "Bonjour à tous, merci d'être là pour ce point d'avancement du projet Notitia.",
        "L'objectif de la réunion est de valider la nouvelle identité visuelle et le planning de la version macOS.",
        "Côté design, on part sur une interface sobre qui suit les conventions d'Apple, sans mascotte.",
        "Est-ce que tout le monde est d'accord pour abandonner l'ancien thème néon ?",
        "Oui, c'est décidé : on garde uniquement la couleur d'accent du système.",
        "Pour l'éditeur, il faut finir les listes de tâches et le menu des blocs avant vendredi.",
        "Clément doit préparer la démonstration de la transcription pour le jury.",
        "La confidentialité est un point clé : toutes les données restent sur l'appareil et l'audio est chiffré.",
        "Il faudra rédiger le registre des traitements RGPD avant la prochaine soutenance.",
        "Quelle date retient-on pour la démonstration à Epitech ?",
        "On vise le quinze novembre, avec une répétition générale la semaine précédente.",
        "Prochaine étape : choisir le moteur de transcription définitif, idéalement sur l'appareil.",
    ]
}
