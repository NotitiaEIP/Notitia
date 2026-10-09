import AppKit
import NotitiaKit
import SwiftUI

/// Menus de l'application. Les commandes de mise en forme passent par la chaîne
/// des répondeurs (`NSApp.sendAction`) : elles s'appliquent au bloc qui a le focus.
struct AppCommands: Commands {
    @FocusedValue(\.workspace) private var workspace
    @FocusedValue(\.searchFocus) private var searchFocus

    var body: some Commands {
        CommandGroup(replacing: .newItem) {
            Button("Nouvelle note") { workspace?.createNote() }
                .keyboardShortcut("n")
                .disabled(workspace == nil)
            Button("Nouveau dossier") { workspace?.requestNewFolder(parent: nil) }
                .keyboardShortcut("n", modifiers: [.command, .shift])
                .disabled(workspace == nil)
            Divider()
            Button("Ouverture rapide…") { workspace?.isQuickOpenPresented = true }
                .keyboardShortcut("k")
                .disabled(workspace == nil)
        }

        CommandGroup(after: .textEditing) {
            Button("Rechercher dans les notes") { searchFocus?.wrappedValue = true }
                .keyboardShortcut("f", modifiers: [.command, .option])
        }

        CommandMenu("Format") {
            Group {
                Section {
                    Button("Gras") { TextCommand.send(.bold) }.keyboardShortcut("b")
                    Button("Italique") { TextCommand.send(.italic) }.keyboardShortcut("i")
                    Button("Souligné") { TextCommand.send(.underline) }.keyboardShortcut("u")
                    Button("Barré") { TextCommand.send(.strikethrough) }.keyboardShortcut("x", modifiers: [.command, .shift])
                    Button("Code") { TextCommand.send(.code) }.keyboardShortcut("e")
                    Button("Lien…") { TextCommand.sendLink() }.keyboardShortcut("l", modifiers: [.command, .shift])
                }
                Section {
                    Button("Texte") { workspace?.editor?.convertActiveBlock(to: .paragraph) }.keyboardShortcut("0", modifiers: [.command, .option])
                    Button("Titre 1") { workspace?.editor?.convertActiveBlock(to: .heading1) }.keyboardShortcut("1", modifiers: [.command, .option])
                    Button("Titre 2") { workspace?.editor?.convertActiveBlock(to: .heading2) }.keyboardShortcut("2", modifiers: [.command, .option])
                    Button("Titre 3") { workspace?.editor?.convertActiveBlock(to: .heading3) }.keyboardShortcut("3", modifiers: [.command, .option])
                    Button("Liste à puces") { workspace?.editor?.convertActiveBlock(to: .bulleted) }.keyboardShortcut("8", modifiers: [.command, .shift])
                    Button("Liste numérotée") { workspace?.editor?.convertActiveBlock(to: .numbered) }.keyboardShortcut("7", modifiers: [.command, .shift])
                    Button("Liste de tâches") { workspace?.editor?.convertActiveBlock(to: .todo) }.keyboardShortcut("t", modifiers: [.command, .shift])
                }
                Section {
                    Button("Cocher / décocher") { workspace?.editor?.toggleActiveTodo() }.keyboardShortcut(.return, modifiers: .command)
                    Button("Monter le bloc") { workspace?.editor?.shiftActiveBlock(by: -1) }.keyboardShortcut(.upArrow, modifiers: [.command, .option])
                    Button("Descendre le bloc") { workspace?.editor?.shiftActiveBlock(by: 1) }.keyboardShortcut(.downArrow, modifiers: [.command, .option])
                }
            }
            .disabled(workspace?.editor == nil)
        }

        CommandMenu("Enregistrement") {
            RecordingCommands(workspace: workspace)
        }

        CommandGroup(replacing: .help) {
            Button("Aide de Notitia") { workspace?.openHelp() }
                .keyboardShortcut("?", modifiers: .command)
                .disabled(workspace == nil)
            Button("Revoir le tutoriel") { workspace?.isTutorialPresented = true }
                .disabled(workspace == nil)
        }

        CommandGroup(after: .sidebar) {
            Button("Afficher l'inspecteur") { workspace?.isInspectorPresented.toggle() }
                .keyboardShortcut("i", modifiers: [.command, .option])
                .disabled(workspace == nil)
        }
    }
}

private struct RecordingCommands: View {
    let workspace: Workspace?

    var body: some View {
        if let recorder = workspace?.services.recorder, recorder.isActive {
            Button(recorder.state == .paused ? "Reprendre" : "Pause") { recorder.togglePause() }
                .keyboardShortcut("p", modifiers: [.command, .shift])
            Button("Arrêter l'enregistrement") { Task { await recorder.stop() } }
                .keyboardShortcut("r", modifiers: [.command, .shift])
        } else {
            Button("Démarrer un enregistrement") { workspace?.beginRecording() }
                .keyboardShortcut("r", modifiers: [.command, .shift])
                .disabled(workspace == nil)
        }
    }
}

/// Actions de mise en forme envoyées au bloc de texte actif.
enum TextCommand {
    static func send(_ attribute: ToggleableTextAttribute) {
        let selector: Selector = switch attribute {
        case .bold: #selector(BlockTextView.notitiaToggleBold(_:))
        case .italic: #selector(BlockTextView.notitiaToggleItalic(_:))
        case .underline: #selector(BlockTextView.notitiaToggleUnderline(_:))
        case .strikethrough: #selector(BlockTextView.notitiaToggleStrikethrough(_:))
        case .code: #selector(BlockTextView.notitiaToggleCode(_:))
        }
        NSApp.sendAction(selector, to: nil, from: nil)
    }

    static func sendColor(_ color: NoteColor?) {
        NSApp.sendAction(#selector(BlockTextView.notitiaSetColor(_:)), to: nil, from: ColorCommandSender(color: color))
    }

    static func sendHighlight(_ color: NoteColor?) {
        NSApp.sendAction(#selector(BlockTextView.notitiaSetHighlight(_:)), to: nil, from: ColorCommandSender(color: color))
    }

    static func sendLink() {
        NSApp.sendAction(#selector(BlockTextView.notitiaEditLink(_:)), to: nil, from: nil)
    }
}

/// Transporte une couleur dans un `sendAction`.
final class ColorCommandSender: NSObject {
    let color: NoteColor?
    init(color: NoteColor?) { self.color = color }
}
