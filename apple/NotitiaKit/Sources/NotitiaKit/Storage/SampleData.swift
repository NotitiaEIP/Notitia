import Foundation
import SwiftData

/// Jeu de données d'exemple, proposé à la fin du tutoriel (et utile pour la démo EIP).
/// La note d'aide est installée séparément (`HelpContent`).
@MainActor
public enum SampleData {
    public static func populate(_ context: ModelContext) {
        let courses = Folder(name: "Cours", symbolName: "graduationcap", color: .indigo, sortIndex: 0)
        let chinese = Folder(name: "Chinois", symbolName: "character.book.closed", color: .red, parent: courses, sortIndex: 0)
        let english = Folder(name: "Anglais", symbolName: "globe.europe.africa", color: .blue, parent: courses, sortIndex: 1)
        let maths = Folder(name: "Maths", symbolName: "function", color: .green, parent: courses, sortIndex: 2)
        let meetings = Folder(name: "Réunions", symbolName: "person.2", color: .orange, sortIndex: 1)
        [courses, chinese, english, maths, meetings].forEach(context.insert)

        let important = Tag(name: "Important", color: .red)
        let revision = Tag(name: "Révisions", color: .purple)
        [important, revision].forEach(context.insert)

        // Chinois
        let tones = Note(title: "Les quatre tons du mandarin", document: NoteDocument(blocks: [
            Block(kind: .paragraph, text: RichText(runs: [
                TextRun("Le mandarin est une langue "),
                TextRun("tonale", style: TextStyle(bold: true)),
                TextRun(" : une même syllabe change de sens selon la mélodie."),
            ])),
            Block(kind: .heading2, text: "Les tons"),
            Block(kind: .numbered, text: RichText(runs: [TextRun("Premier ton", style: TextStyle(bold: true)), TextRun(" — haut et plat : mā (妈, mère)")])),
            Block(kind: .numbered, text: RichText(runs: [TextRun("Deuxième ton", style: TextStyle(bold: true)), TextRun(" — montant : má (麻, chanvre)")])),
            Block(kind: .numbered, text: RichText(runs: [TextRun("Troisième ton", style: TextStyle(bold: true)), TextRun(" — descendant puis montant : mǎ (马, cheval)")])),
            Block(kind: .numbered, text: RichText(runs: [TextRun("Quatrième ton", style: TextStyle(bold: true)), TextRun(" — descendant : mà (骂, gronder)")])),
            Block(kind: .callout, text: RichText(runs: [
                TextRun("Astuce : ", style: TextStyle(bold: true)),
                TextRun("deux troisièmes tons à la suite → le premier devient un deuxième ton (nǐ hǎo se prononce ní hǎo)."),
            ])),
            Block(kind: .heading2, text: "À réviser"),
            Block(kind: .todo, text: "Écouter l'enregistrement de la leçon 4", isChecked: true),
            Block(kind: .todo, text: "Recopier 10 caractères avec leur pinyin"),
            Block(kind: .todo, text: RichText(runs: [TextRun("Préparer le "), TextRun("contrôle oral", style: TextStyle(highlight: .yellow)), TextRun(" de jeudi")])),
        ]), folder: chinese)
        tones.tags = [revision]
        tones.isPinned = true

        // Anglais
        let tenses = Note(title: "Present perfect vs. preterit", document: NoteDocument(blocks: [
            Block(kind: .heading2, text: "Present perfect"),
            Block(kind: .bulleted, text: "Action passée avec un lien au présent"),
            Block(kind: .bulleted, text: RichText(runs: [TextRun("I "), TextRun("have lived", style: TextStyle(bold: true, color: .blue)), TextRun(" here for ten years.")])),
            Block(kind: .heading2, text: "Preterit"),
            Block(kind: .bulleted, text: "Action terminée, datée dans le passé"),
            Block(kind: .bulleted, text: RichText(runs: [TextRun("I "), TextRun("lived", style: TextStyle(bold: true, color: .orange)), TextRun(" in London in 2019.")])),
            Block(kind: .quote, text: "« Since » et « for » appellent presque toujours le present perfect."),
        ]), folder: english)

        // Maths
        let derivatives = Note(title: "Dérivées usuelles", document: NoteDocument(blocks: [
            Block(kind: .paragraph, text: "Rappel des dérivées à connaître par cœur."),
            Block(kind: .code, text: "(xⁿ)' = n·xⁿ⁻¹\n(eˣ)' = eˣ\n(ln x)' = 1/x\n(sin x)' = cos x\n(cos x)' = −sin x"),
            Block(kind: .heading3, text: "Règles de calcul"),
            Block(kind: .bulleted, text: RichText(runs: [TextRun("Produit : ", style: TextStyle(bold: true)), TextRun("(uv)' = u'v + uv'", style: TextStyle(code: true))])),
            Block(kind: .bulleted, text: RichText(runs: [TextRun("Quotient : ", style: TextStyle(bold: true)), TextRun("(u/v)' = (u'v − uv') / v²", style: TextStyle(code: true))])),
            Block(kind: .bulleted, text: RichText(runs: [TextRun("Composée : ", style: TextStyle(bold: true)), TextRun("(f∘g)' = g'·(f'∘g)", style: TextStyle(code: true))])),
        ]), folder: maths)
        derivatives.tags = [important, revision]

        // Réunion avec transcription (sans audio : données d'exemple)
        let recording = Recording(localeIdentifier: "fr_FR", engineName: "Exemple", consentConfirmedAt: .now)
        var time: TimeInterval = 0
        recording.segments = DemoTranscriptionEngine.defaultScript.enumerated().map { index, sentence in
            let duration = Double(sentence.split(separator: " ").count) / 2.6
            defer { time += duration + 0.6 }
            return TranscriptSegment(text: sentence, start: time, end: time + duration, speaker: index.isMultiple(of: 3) ? "Lucas" : (index % 3 == 1 ? "Clément" : "Nathan"))
        }
        recording.duration = time
        // Annotations d'exemple : rires après « sans mascotte », applaudissements après la décision,
        // et le ton du passage sur le thème (déduit du texte).
        let segments = recording.segments
        recording.annotations = [
            TranscriptAnnotation(kind: .laughter, source: .soundAnalysis, start: segments[2].end - 0.4, end: segments[2].end + 0.5, confidence: 0.86),
            TranscriptAnnotation(kind: .applause, source: .soundAnalysis, start: segments[4].end, end: segments[4].end + 0.6, confidence: 0.78),
            TranscriptAnnotation(kind: .tone, source: .foundationModels, start: segments[3].start, end: segments[4].end,
                                 confidence: 0.7, label: "accord sur l'abandon de l'ancien thème", polarity: 0.8),
        ]
        recording.seal()
        let meeting = Note(title: "Point d'avancement Notitia", document: NoteDocument(blocks: [
            Block(kind: .paragraph, text: RichText("Réunion d'équipe — transcription d'exemple. Essayez « Résumer » ou « Carte mentale » dans l'inspecteur.", style: TextStyle(italic: true, color: .gray))),
            Block(kind: .transcript, recordingID: recording.uid),
        ]), folder: meetings)
        meeting.tags = [important]
        context.insert(meeting)
        recording.note = meeting
        context.insert(recording)

        [tones, tenses, derivatives].forEach(context.insert)
        try? context.save()
    }
}
