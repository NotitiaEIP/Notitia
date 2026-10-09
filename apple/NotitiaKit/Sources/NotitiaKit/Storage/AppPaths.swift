import Foundation

/// Emplacements des données locales.
///
/// Dans l'app sandboxée, `Application Support` pointe vers le conteneur privé
/// (`~/Library/Containers/<bundle>/Data/Library/Application Support/Notitia`).
/// Rien n'est écrit ailleurs : c'est ce qui rend l'export et l'effacement complets fiables.
public struct AppPaths: Sendable {
    public let root: URL

    public init(root: URL) {
        self.root = root
    }

    public static var `default`: AppPaths {
        let base = FileManager.default.urls(for: .applicationSupportDirectory, in: .userDomainMask)[0]
        return AppPaths(root: base.appending(path: "Notitia", directoryHint: .isDirectory))
    }

    public var storeURL: URL { root.appending(path: "Notitia.store") }
    public var recordingsDirectory: URL { root.appending(path: "Recordings", directoryHint: .isDirectory) }
    public var attachmentsDirectory: URL { root.appending(path: "Attachments", directoryHint: .isDirectory) }

    public func prepare() throws {
        let fm = FileManager.default
        for url in [root, recordingsDirectory, attachmentsDirectory] {
            try fm.createDirectory(at: url, withIntermediateDirectories: true)
        }
    }

    public func attachmentURL(_ fileName: String) -> URL {
        attachmentsDirectory.appending(path: fileName)
    }

    /// Copie un fichier dans les pièces jointes et renvoie son nouveau nom.
    public func importAttachment(from source: URL) throws -> String {
        try prepare()
        let ext = source.pathExtension.isEmpty ? "bin" : source.pathExtension.lowercased()
        let name = "\(UUID().uuidString).\(ext)"
        try FileManager.default.copyItem(at: source, to: attachmentURL(name))
        return name
    }

    /// Taille totale occupée sur disque, en octets.
    public func diskUsage() -> Int64 {
        guard let enumerator = FileManager.default.enumerator(
            at: root, includingPropertiesForKeys: [.fileSizeKey], options: []
        ) else { return 0 }
        var total: Int64 = 0
        for case let url as URL in enumerator {
            total += Int64((try? url.resourceValues(forKeys: [.fileSizeKey]).fileSize) ?? 0)
        }
        return total
    }
}
