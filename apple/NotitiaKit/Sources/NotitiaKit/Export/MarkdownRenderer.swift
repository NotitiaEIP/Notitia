import Foundation

/// Conversion des notes en Markdown (export et portabilité RGPD).
public enum MarkdownRenderer {
    public static func render(
        title: String,
        document: NoteDocument,
        segmentsByRecording: [UUID: [TranscriptSegment]] = [:],
        annotationsByRecording: [UUID: [TranscriptAnnotation]] = [:]
    ) -> String {
        var lines: [String] = []
        if !title.isEmpty { lines.append("# \(title)"); lines.append("") }
        lines.append(render(blocks: document.blocks, segmentsByRecording: segmentsByRecording, annotationsByRecording: annotationsByRecording))
        return lines.joined(separator: "\n")
    }

    public static func render(
        blocks: [Block],
        segmentsByRecording: [UUID: [TranscriptSegment]] = [:],
        annotationsByRecording: [UUID: [TranscriptAnnotation]] = [:]
    ) -> String {
        let numbers = BlockOperations.listNumbers(blocks)
        var output: [String] = []
        var previousWasList = false

        for block in blocks {
            let pad = String(repeating: "    ", count: block.indent)
            let text = inline(block.text)
            let isList = block.kind.isList
            // Une ligne vide sépare les blocs, sauf entre éléments d'une même liste.
            if !output.isEmpty, !(isList && previousWasList) { output.append("") }
            previousWasList = isList

            switch block.kind {
            case .paragraph: output.append(pad + text)
            case .heading1: output.append("## " + text)
            case .heading2: output.append("### " + text)
            case .heading3: output.append("#### " + text)
            case .bulleted: output.append(pad + "- " + text)
            case .numbered: output.append(pad + "\(numbers[block.id] ?? 1). " + text)
            case .todo: output.append(pad + (block.isChecked ? "- [x] " : "- [ ] ") + text)
            case .quote: output.append(contentsOf: block.text.plainText.split(separator: "\n", omittingEmptySubsequences: false).map { "> " + $0 })
            case .callout: output.append("> **Note :** " + text)
            case .code: output.append("```\n" + block.text.plainText + "\n```")
            case .divider: output.append("---")
            case .image:
                if let name = block.attachment { output.append("![Image](Attachments/\(name))") }
            case .transcript:
                output.append("### Transcription")
                let segments = block.recordingID.flatMap { segmentsByRecording[$0] } ?? []
                let annotations = block.recordingID.flatMap { annotationsByRecording[$0] } ?? []
                for item in TranscriptTimeline.merge(segments: segments.filter(\.isFinal), annotations: annotations) {
                    switch item {
                    case .segment(let segment):
                        let speaker = segment.speaker.map { "**\($0)** " } ?? ""
                        output.append("`[\(TranscriptSegment.formatTimestamp(segment.start))]` \(speaker)\(segment.text)  ")
                    case .annotation(let annotation):
                        // Toujours signalé comme automatique : une erreur ne doit pas passer pour un fait.
                        output.append("`[\(TranscriptSegment.formatTimestamp(annotation.start))]` *(\(annotation.exportText), automatique)*  ")
                    }
                }
            case .mindMap:
                if let map = block.mindMap { output.append(render(mindMap: map)) }
            }
        }
        return output.joined(separator: "\n")
    }

    public static func render(mindMap: MindMapDocument) -> String {
        var lines = ["### Carte mentale — \(mindMap.title)"]
        func visit(_ node: MindMapNode, depth: Int) {
            lines.append(String(repeating: "    ", count: depth) + "- " + node.label)
            node.children.forEach { visit($0, depth: depth + 1) }
        }
        visit(mindMap.root, depth: 0)
        return lines.joined(separator: "\n")
    }

    /// Styles inline en Markdown (gras, italique, barré, code, liens).
    public static func inline(_ text: RichText) -> String {
        text.runs.map { run in
            var value = run.text
            guard !value.trimmingCharacters(in: .whitespaces).isEmpty else { return value }
            if run.style.code { value = "`\(value)`" }
            if run.style.bold { value = "**\(value)**" }
            if run.style.italic { value = "*\(value)*" }
            if run.style.strikethrough { value = "~~\(value)~~" }
            if let link = run.style.link { value = "[\(value)](\(link))" }
            return value
        }
        .joined()
    }
}
