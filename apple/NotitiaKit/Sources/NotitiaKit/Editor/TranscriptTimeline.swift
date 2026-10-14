import Foundation

/// Élément de la chronologie d'une transcription : un segment de texte ou une annotation.
public enum TranscriptTimelineItem: Identifiable, Hashable, Sendable {
    case segment(TranscriptSegment)
    case annotation(TranscriptAnnotation)

    public var id: UUID {
        switch self {
        case .segment(let segment): segment.id
        case .annotation(let annotation): annotation.id
        }
    }

    public var start: TimeInterval {
        switch self {
        case .segment(let segment): segment.start
        case .annotation(let annotation): annotation.start
        }
    }

    /// Ordre à temps égal : le ton (qui introduit un passage), puis le texte, puis les sons.
    var rank: Int {
        switch self {
        case .annotation(let annotation) where annotation.kind == .tone: 0
        case .segment: 1
        case .annotation: 2
        }
    }
}

/// Fusionne segments et annotations sur une même horloge (secondes depuis le début de l'enregistrement).
/// Sert à l'affichage, à l'export Markdown et aux citations.
public enum TranscriptTimeline {
    public static func merge(
        segments: [TranscriptSegment],
        annotations: [TranscriptAnnotation],
        includeHidden: Bool = false
    ) -> [TranscriptTimelineItem] {
        let items = segments.filter { !$0.text.isEmpty }.map(TranscriptTimelineItem.segment)
            + annotations.filter { includeHidden || !$0.isHidden }.map(TranscriptTimelineItem.annotation)
        // Tri stable : à rang et temps égaux, l'ordre d'origine est conservé.
        return items.enumerated()
            .sorted { ($0.element.start, $0.element.rank, $0.offset) < ($1.element.start, $1.element.rank, $1.offset) }
            .map(\.element)
    }

    /// Le segment cité, avec `radius` segments avant et après, et les annotations visibles de ce moment.
    public struct Context: Equatable, Sendable {
        public var cited: TranscriptSegment
        public var segments: [TranscriptSegment]
        public var annotations: [TranscriptAnnotation]
        public var start: TimeInterval { segments.first?.start ?? cited.start }
        public var end: TimeInterval { segments.last?.end ?? cited.end }
    }

    public static func context(
        of segmentID: UUID,
        segments: [TranscriptSegment],
        annotations: [TranscriptAnnotation],
        radius: Int = 1
    ) -> Context? {
        let finals = segments.filter { $0.isFinal && !$0.text.isEmpty }
        guard let index = finals.firstIndex(where: { $0.id == segmentID }) else { return nil }
        let window = Array(finals[max(0, index - radius)...min(finals.count - 1, index + radius)])
        let start = window.first?.start ?? 0
        let end = window.last?.end ?? 0
        let overlapping = annotations.filter { !$0.isHidden && $0.start <= end && $0.end >= start }
        return Context(cited: finals[index], segments: window, annotations: overlapping)
    }

    /// Blocs « Citer avec le contexte » : une citation contenant la phrase citée (en gras), la phrase d'avant
    /// et celle d'après, les annotations du moment, et des horodatages cliquables qui rejouent l'audio.
    public static func quoteBlocks(_ context: Context, recordingID: UUID, recordedAt: Date) -> [Block] {
        var runs: [TextRun] = []
        let items = merge(segments: context.segments, annotations: context.annotations)
        for (index, item) in items.enumerated() {
            if index > 0 { runs.append(TextRun("\n")) }
            let link = TranscriptLink(recordingID: recordingID, time: item.start).url.absoluteString
            runs.append(TextRun("[\(TranscriptSegment.formatTimestamp(item.start))]", style: TextStyle(link: link)))
            runs.append(TextRun(" "))
            switch item {
            case .segment(let segment):
                if let speaker = segment.speaker { runs.append(TextRun(speaker + " : ", style: TextStyle(bold: true))) }
                runs.append(TextRun(segment.text, style: TextStyle(bold: segment.id == context.cited.id)))
            case .annotation(let annotation):
                runs.append(TextRun("(\(annotation.exportText), automatique)", style: TextStyle(italic: true)))
            }
        }
        let caption = "Extrait de la transcription d'origine du \(recordedAt.formatted(date: .abbreviated, time: .shortened)), "
            + "de \(TranscriptSegment.formatTimestamp(context.start)) à \(TranscriptSegment.formatTimestamp(context.end)), "
            + "avec la phrase précédente et la suivante."
        runs.append(TextRun("\n"))
        runs.append(TextRun(caption, style: TextStyle(italic: true, color: .gray)))
        return [Block(kind: .quote, text: RichText(runs: runs))]
    }
}

/// Lien interne vers un instant d'un enregistrement : `notitia://recording/<uuid>?t=<millisecondes>`.
public struct TranscriptLink: Equatable, Sendable {
    public static let scheme = "notitia"

    public var recordingID: UUID
    public var time: TimeInterval

    public init(recordingID: UUID, time: TimeInterval) {
        self.recordingID = recordingID
        self.time = max(0, time)
    }

    public init?(url: URL) {
        guard url.scheme == Self.scheme, url.host() == "recording",
              let id = UUID(uuidString: url.lastPathComponent) else { return nil }
        let millis = URLComponents(url: url, resolvingAgainstBaseURL: false)?
            .queryItems?.first { $0.name == "t" }?.value.flatMap(Int.init) ?? 0
        self.init(recordingID: id, time: Double(millis) / 1000)
    }

    public var url: URL {
        URL(string: "\(Self.scheme)://recording/\(recordingID.uuidString)?t=\(Int((time * 1000).rounded()))")!
    }
}
