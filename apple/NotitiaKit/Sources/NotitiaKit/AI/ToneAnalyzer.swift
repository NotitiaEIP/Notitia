import Foundation
import FoundationModels
import NaturalLanguage

/// Ton des passages d'une transcription, déduit **du texte seulement**, après l'enregistrement.
///
/// Garde-fous : on qualifie un **échange** (« échange tendu sur le budget »), jamais une personne ;
/// aucune donnée de la voix n'est utilisée ; chaque résultat est marqué « automatique », avec sa source,
/// et couvre le passage exact dont il parle (même horloge que la transcription).
///
/// Avec Foundation Models (sur l'appareil) : un libellé court et factuel. Sinon : NaturalLanguage, qui ne donne
/// qu'une polarité (positif / négatif) ; seuls les passages nettement marqués sont retenus.
public struct ToneAnalyzer: Sendable {
    public enum Engine: Sendable, Equatable {
        case automatic
        case naturalLanguage
        case foundationModels
    }

    public var engine: Engine
    /// Polarité minimale (en valeur absolue) retenue avec NaturalLanguage : les phrases neutres en français
    /// sortent souvent autour de ±0,4.
    public var minimumPolarity: Double

    public init(engine: Engine = .automatic, minimumPolarity: Double = 0.6) {
        self.engine = engine
        self.minimumPolarity = minimumPolarity
    }

    /// Foundation Models est utilisable pour cette langue sur ce Mac.
    public static func isFoundationModelAvailable(for locale: Locale) -> Bool {
        let model = SystemLanguageModel.default
        return model.isAvailable && model.supportsLocale(locale)
    }

    public func analyze(_ segments: [TranscriptSegment], locale: Locale) async -> [TranscriptAnnotation] {
        let passages = TonePassages.make(from: segments)
        guard !passages.isEmpty else { return [] }
        let useModel = switch engine {
        case .automatic: Self.isFoundationModelAvailable(for: locale)
        case .foundationModels: true
        case .naturalLanguage: false
        }
        if useModel, let labelled = await labelWithFoundationModels(passages) {
            return labelled
        }
        return scoreWithNaturalLanguage(passages, locale: locale)
    }

    // MARK: NaturalLanguage

    func scoreWithNaturalLanguage(_ passages: [TonePassages.Passage], locale: Locale) -> [TranscriptAnnotation] {
        let language = NLLanguage(rawValue: locale.language.languageCode?.identifier ?? "fr")
        return passages.compactMap { passage in
            let tagger = NLTagger(tagSchemes: [.sentimentScore])
            tagger.string = passage.text
            tagger.setLanguage(language, range: passage.text.startIndex..<passage.text.endIndex)
            let (tag, _) = tagger.tag(at: passage.text.startIndex, unit: .paragraph, scheme: .sentimentScore)
            guard let score = tag.flatMap({ Double($0.rawValue) }), abs(score) >= minimumPolarity else { return nil }
            return TranscriptAnnotation(kind: .tone, source: .naturalLanguage, start: passage.start, end: passage.end,
                                        confidence: abs(score), polarity: score)
        }
    }

    // MARK: Foundation Models

    /// Le modèle ne rédige pas librement : il choisit une catégorie et nomme le sujet ; le libellé est
    /// construit par Notitia (« échange tendu sur le budget »). Cela évite les jugements et les mots inventés.
    @Generable
    struct ToneReading {
        @Guide(description: "Ton de l'échange dans l'extrait, d'après le texte seulement.")
        var tone: ToneCategory
        @Guide(description: "Sujet principal de l'extrait, 1 à 5 mots en français courant, avec son article, sans nom de personne. Exemples : « le budget », « le calendrier de livraison ».")
        var subject: String
    }

    @Generable
    enum ToneCategory {
        case neutre
        case positif
        case enthousiaste
        case tendu
        case desaccord
        case hesitant
    }

    static let instructions = """
    Tu décris le ton d'extraits de réunion, à partir du texte seul.
    Règles strictes :
    - Tu qualifies l'échange ou le sujet, jamais une personne : pas de nom, pas de « il est énervé ».
    - Tu ne devines ni les émotions, ni les intentions, ni le caractère des participants.
    - Si l'extrait est factuel ou ordinaire, le ton est « neutre ».
    - La description reste courte, factuelle et vérifiable dans l'extrait.
    """

    /// `nil` si le modèle n'a pas pu être utilisé du tout (on se rabat alors sur NaturalLanguage).
    func labelWithFoundationModels(_ passages: [TonePassages.Passage]) async -> [TranscriptAnnotation]? {
        guard SystemLanguageModel.default.isAvailable else { return nil }
        var results: [TranscriptAnnotation] = []
        var answered = 0
        for passage in passages {
            if Task.isCancelled { break }
            // Une session par passage : le contexte reste petit et les passages ne s'influencent pas.
            let session = LanguageModelSession(instructions: Self.instructions)
            do {
                let response = try await session.respond(
                    to: "Extrait :\n\(passage.text)",
                    generating: ToneReading.self,
                    options: GenerationOptions(temperature: 0)
                )
                answered += 1
                let reading = response.content
                guard reading.tone != .neutre else { continue }
                results.append(TranscriptAnnotation(
                    kind: .tone, source: .foundationModels, start: passage.start, end: passage.end,
                    confidence: 0.7, label: reading.tone.label(subject: reading.subject), polarity: reading.tone.polarity
                ))
            } catch {
                // Passage refusé par les garde-fous ou trop long : on l'ignore.
                continue
            }
        }
        return answered == 0 ? nil : results
    }
}

extension ToneAnalyzer.ToneCategory {
    /// Libellé construit par Notitia : il qualifie l'échange, jamais une personne.
    func label(subject: String) -> String {
        let phrase = switch self {
        case .neutre: "échange"
        case .positif: "échange positif"
        case .enthousiaste: "échange enthousiaste"
        case .tendu: "échange tendu"
        case .desaccord: "désaccord"
        case .hesitant: "hésitation"
        }
        let cleaned = subject
            .trimmingCharacters(in: .whitespacesAndNewlines.union(.init(charactersIn: "«»\".,;:")))
        guard !cleaned.isEmpty, cleaned.split(separator: " ").count <= 6 else { return phrase }
        return "\(phrase) sur \(cleaned.prefix(1).lowercased() + cleaned.dropFirst())"
    }

    var polarity: Double {
        switch self {
        case .positif, .enthousiaste: 0.8
        case .neutre: 0
        case .hesitant: -0.3
        case .tendu, .desaccord: -0.8
        }
    }
}

/// Découpe une transcription en passages de quelques phrases, sans franchir un long silence.
/// Logique pure, testée.
public enum TonePassages {
    public struct Passage: Equatable, Sendable {
        public var start: TimeInterval
        public var end: TimeInterval
        public var text: String
    }

    public static func make(
        from segments: [TranscriptSegment],
        maxDuration: TimeInterval = 45,
        maxCharacters: Int = 1_200,
        breakOnPause: TimeInterval = 5,
        minimumCharacters: Int = 40
    ) -> [Passage] {
        var passages: [Passage] = []
        var current: Passage?
        for segment in segments where segment.isFinal && !segment.text.isEmpty {
            if var open = current,
               segment.start - open.end < breakOnPause,
               segment.end - open.start <= maxDuration,
               open.text.count + segment.text.count < maxCharacters {
                open.text += " " + segment.text
                open.end = segment.end
                current = open
            } else {
                if let open = current { passages.append(open) }
                current = Passage(start: segment.start, end: segment.end, text: segment.text)
            }
        }
        if let open = current { passages.append(open) }
        return passages.filter { $0.text.count >= minimumCharacters }
    }
}
