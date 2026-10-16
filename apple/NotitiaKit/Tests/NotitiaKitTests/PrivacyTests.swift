import Foundation
import SwiftData
import Testing
@testable import NotitiaKit

/// Répertoire temporaire propre à chaque test.
struct TemporaryDirectory {
    let url: URL

    init() throws {
        url = FileManager.default.temporaryDirectory.appending(path: "notitia-tests-\(UUID().uuidString)", directoryHint: .isDirectory)
        try FileManager.default.createDirectory(at: url, withIntermediateDirectories: true)
    }

    func remove() { try? FileManager.default.removeItem(at: url) }
}

@Suite("Chiffrement de l'audio")
struct AudioVaultTests {
    @Test func allerRetourChiffrement() throws {
        let tmp = try TemporaryDirectory(); defer { tmp.remove() }
        let keys = InMemoryKeyStore()
        let vault = AudioVault(directory: tmp.url.appending(path: "Recordings"), keyStore: keys)
        let id = UUID()
        let plaintext = Data((0..<10_000).map { UInt8($0 % 251) })
        let source = tmp.url.appending(path: "clair.m4a")
        try plaintext.write(to: source)

        try vault.seal(plaintextFile: source, id: id)

        #expect(!FileManager.default.fileExists(atPath: source.path), "le fichier en clair est supprimé")
        let stored = try Data(contentsOf: vault.fileURL(for: id))
        #expect(stored.starts(with: Data("NTA1".utf8)))
        #expect(stored.range(of: plaintext.prefix(64)) == nil, "le contenu stocké n'est pas en clair")
        #expect(try vault.open(id) == plaintext)
    }

    @Test func fichierAltereRejete() throws {
        let tmp = try TemporaryDirectory(); defer { tmp.remove() }
        let vault = AudioVault(directory: tmp.url, keyStore: InMemoryKeyStore())
        let id = UUID()
        let source = tmp.url.appending(path: "a.m4a")
        try Data("audio".utf8).write(to: source)
        try vault.seal(plaintextFile: source, id: id)

        var data = try Data(contentsOf: vault.fileURL(for: id))
        data[data.count - 1] ^= 0xFF
        try data.write(to: vault.fileURL(for: id))
        #expect(throws: (any Error).self) { try vault.open(id) }
    }

    @Test func destructionTotaleRendLAudioIllisible() throws {
        let tmp = try TemporaryDirectory(); defer { tmp.remove() }
        let keys = InMemoryKeyStore()
        let vault = AudioVault(directory: tmp.url.appending(path: "Recordings"), keyStore: keys)
        let source = tmp.url.appending(path: "a.m4a")
        try Data("audio".utf8).write(to: source)
        let id = UUID()
        try vault.seal(plaintextFile: source, id: id)

        try vault.destroyEverything()

        #expect(keys.isEmpty, "la clé maître est détruite")
        #expect(!vault.exists(id))
        #expect(throws: AudioVaultError.self) { try vault.open(id) }
    }
}

@Suite("Conservation des données")
struct RetentionTests {
    let now = Date(timeIntervalSince1970: 1_800_000_000)

    @Test func audioConserveParDefaut() {
        let policy = RetentionPolicy()
        #expect(!policy.isAudioExpired(recordedAt: now.addingTimeInterval(-86_400 * 3650), now: now))
    }

    @Test func audioExpire() {
        let policy = RetentionPolicy(audioRetentionDays: 30)
        #expect(!policy.isAudioExpired(recordedAt: now.addingTimeInterval(-86_400 * 29), now: now))
        #expect(policy.isAudioExpired(recordedAt: now.addingTimeInterval(-86_400 * 30), now: now))
    }

    @Test func corbeille() {
        let policy = RetentionPolicy()
        #expect(policy.isTrashExpired(trashedAt: now.addingTimeInterval(-86_400 * 31), now: now))
        #expect(!policy.isTrashExpired(trashedAt: now.addingTimeInterval(-86_400 * 2), now: now))
    }
}

@MainActor
@Suite("Base de données et RGPD", .serialized)
struct StoreTests {
    struct Fixture {
        let container: ModelContainer
        let context: ModelContext
        let store: NotitiaStore
        let keys: InMemoryKeyStore
        let tmp: TemporaryDirectory
    }

    func makeFixture() throws -> Fixture {
        let tmp = try TemporaryDirectory()
        let paths = AppPaths(root: tmp.url.appending(path: "Notitia"))
        try paths.prepare()
        let keys = InMemoryKeyStore()
        let vault = AudioVault(directory: paths.recordingsDirectory, keyStore: keys)
        let container = try NotitiaSchema.makeContainer(paths: paths, inMemory: true)
        let context = container.mainContext
        return Fixture(container: container, context: context, store: NotitiaStore(context: context, paths: paths, vault: vault), keys: keys, tmp: tmp)
    }

    func addRecording(to note: Note, fixture: Fixture, recordedAt: Date = .now) throws -> Recording {
        let recording = Recording(localeIdentifier: "fr_FR", engineName: "test", consentConfirmedAt: recordedAt, note: note)
        recording.createdAt = recordedAt
        fixture.context.insert(recording)
        let source = fixture.tmp.url.appending(path: "\(recording.uid).m4a")
        try Data("audio".utf8).write(to: source)
        try fixture.store.vault.seal(plaintextFile: source, id: recording.uid)
        recording.hasAudio = true
        return recording
    }

    @Test func dossierNePeutPasEntrerDansSonDescendant() throws {
        let f = try makeFixture(); defer { f.tmp.remove() }
        let cours = Folder(name: "Cours")
        let chinois = Folder(name: "Chinois", parent: cours)
        let grammaire = Folder(name: "Grammaire", parent: chinois)
        [cours, chinois, grammaire].forEach(f.context.insert)

        #expect(!cours.canMove(into: grammaire))
        #expect(!cours.canMove(into: cours))
        #expect(grammaire.canMove(into: nil))
        #expect(f.store.move(grammaire, into: cours))
        #expect(grammaire.pathDescription == "Cours › Grammaire")
        #expect(!f.store.move(cours, into: chinois))
    }

    @Test func suppressionDefinitiveEffaceLAudio() throws {
        let f = try makeFixture(); defer { f.tmp.remove() }
        let note = f.store.createNote(in: nil, title: "Réunion")
        let recording = try addRecording(to: note, fixture: f)
        let id = recording.uid
        try f.context.save()
        #expect(f.store.vault.exists(id))

        f.store.moveToTrash(note)
        #expect(note.isTrashed)
        try f.store.emptyTrash()
        try f.context.save()

        #expect(!f.store.vault.exists(id))
        #expect(try f.context.fetchCount(FetchDescriptor<Recording>()) == 0)
    }

    @Test func maintenanceAppliqueLaPolitique() throws {
        let f = try makeFixture(); defer { f.tmp.remove() }
        let now = Date.now
        let old = f.store.createNote(in: nil, title: "Ancienne")
        old.trashedAt = now.addingTimeInterval(-86_400 * 40)
        let recent = f.store.createNote(in: nil, title: "Récente")
        recent.trashedAt = now.addingTimeInterval(-86_400)
        let meeting = f.store.createNote(in: nil, title: "Réunion")
        let recording = try addRecording(to: meeting, fixture: f, recordedAt: now.addingTimeInterval(-86_400 * 10))
        recording.segments = [TranscriptSegment(text: "Bonjour", start: 0, end: 1)]
        try f.context.save()

        let report = try f.store.runMaintenance(policy: RetentionPolicy(audioRetentionDays: 7), now: now)

        #expect(report == NotitiaStore.MaintenanceReport(expiredAudio: 1, purgedNotes: 1))
        #expect(try f.context.fetchCount(FetchDescriptor<Note>()) == 2)
        #expect(recording.hasAudio == false)
        #expect(recording.audioDeletedAt != nil)
        #expect(recording.segments.map(\.text) == ["Bonjour"], "la transcription est conservée")
        #expect(!f.store.vault.exists(recording.uid))
    }

    @Test func toutEffacer() throws {
        let f = try makeFixture(); defer { f.tmp.remove() }
        SampleData.populate(f.context)
        let note = f.store.createNote(in: nil, title: "Avec audio")
        _ = try addRecording(to: note, fixture: f)
        let attachment = f.store.paths.attachmentURL("image.png")
        try Data([1, 2, 3]).write(to: attachment)
        try f.context.save()

        try f.store.eraseEverything()

        #expect(try f.context.fetchCount(FetchDescriptor<Note>()) == 0)
        #expect(try f.context.fetchCount(FetchDescriptor<Folder>()) == 0)
        #expect(try f.context.fetchCount(FetchDescriptor<Recording>()) == 0)
        #expect(try f.context.fetchCount(FetchDescriptor<NotitiaKit.Tag>()) == 0)
        #expect(f.keys.isEmpty)
        #expect(!FileManager.default.fileExists(atPath: attachment.path))
        #expect(try FileManager.default.contentsOfDirectory(atPath: f.store.paths.recordingsDirectory.path).isEmpty)
    }

    @Test func exportComplet() throws {
        let f = try makeFixture(); defer { f.tmp.remove() }
        SampleData.populate(f.context)
        let note = f.store.createNote(in: nil, title: "Avec audio")
        let recording = try addRecording(to: note, fixture: f)
        try f.context.save()

        let snapshot = try ExportSnapshot.make(context: f.context)
        let destination = f.tmp.url.appending(path: "Export", directoryHint: .isDirectory)
        try FileManager.default.createDirectory(at: destination, withIntermediateDirectories: true)
        let root = try DataExporter(paths: f.store.paths, vault: f.store.vault).export(snapshot, into: destination)

        let noteCount = try f.context.fetchCount(FetchDescriptor<Note>())
        #expect(snapshot.notes.count == noteCount)

        let json = try JSONDecoder.iso8601.decode(ExportSnapshot.self, from: Data(contentsOf: root.appending(path: "notitia-export.json")))
        #expect(json.notes.count == noteCount)

        let markdown = FileManager.default.enumerator(at: root.appending(path: "Notes"), includingPropertiesForKeys: nil)!
            .compactMap { $0 as? URL }
            .filter { $0.pathExtension == "md" }
        #expect(markdown.count == noteCount)
        #expect(markdown.contains { $0.path.hasSuffix("Notes/Cours/Chinois/Les quatre tons du mandarin.md") })

        let audio = root.appending(path: "Audio/\(recording.uid.uuidString).m4a")
        #expect(try Data(contentsOf: audio) == Data("audio".utf8), "l'audio exporté est déchiffré")
        #expect(FileManager.default.fileExists(atPath: root.appending(path: "LISEZMOI.txt").path))
    }

    @Test func markdownDeLaReunionContientLaTranscription() throws {
        let f = try makeFixture(); defer { f.tmp.remove() }
        SampleData.populate(f.context)
        let meeting = try #require(try f.context.fetch(FetchDescriptor<Note>()).first { $0.hasRecording })
        let segments = Dictionary(uniqueKeysWithValues: meeting.recordings.map { ($0.uid, $0.namedSegments) })
        let markdown = MarkdownRenderer.render(title: meeting.title, document: meeting.document, segmentsByRecording: segments)
        #expect(markdown.contains("### Transcription"))
        #expect(markdown.contains("`[00:00]` **Lucas** Bonjour à tous"))
    }
}

extension JSONDecoder {
    static var iso8601: JSONDecoder {
        let decoder = JSONDecoder()
        decoder.dateDecodingStrategy = .iso8601
        return decoder
    }
}
