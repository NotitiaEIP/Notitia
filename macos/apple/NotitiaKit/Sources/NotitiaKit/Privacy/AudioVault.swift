import CryptoKit
import Foundation

public enum AudioVaultError: LocalizedError {
    case invalidFile
    case missingAudio

    public var errorDescription: String? {
        switch self {
        case .invalidFile: "Le fichier audio chiffré est illisible."
        case .missingAudio: "L'audio de cet enregistrement a été supprimé."
        }
    }
}

/// Coffre des enregistrements audio, chiffrés au repos en AES-GCM 256 bits.
///
/// La clé maître est générée au premier usage et stockée dans le trousseau, sur l'appareil
/// uniquement. Supprimer la clé (`destroyEverything`) rend tout fichier résiduel illisible.
///
/// Format d'un fichier `.naudio` : en-tête ASCII `NTA1` suivi de la boîte AES-GCM combinée
/// (nonce, texte chiffré, tag d'authentification).
public struct AudioVault: Sendable {
    static let header = Data("NTA1".utf8)
    static let keyAccount = "audio-master-key"

    public let directory: URL
    private let keyStore: any KeyStore

    public init(directory: URL, keyStore: any KeyStore) {
        self.directory = directory
        self.keyStore = keyStore
    }

    public func fileURL(for id: UUID) -> URL {
        directory.appending(path: "\(id.uuidString).naudio")
    }

    public func exists(_ id: UUID) -> Bool {
        FileManager.default.fileExists(atPath: fileURL(for: id).path)
    }

    /// Chiffre un fichier audio en clair puis supprime l'original.
    public func seal(plaintextFile source: URL, id: UUID) throws {
        try FileManager.default.createDirectory(at: directory, withIntermediateDirectories: true)
        let plaintext = try Data(contentsOf: source)
        let sealed = try AES.GCM.seal(plaintext, using: try key())
        guard let combined = sealed.combined else { throw AudioVaultError.invalidFile }
        try (Self.header + combined).write(to: fileURL(for: id), options: [.atomic, .completeFileProtection])
        try? FileManager.default.removeItem(at: source)
    }

    /// Déchiffre en mémoire.
    public func open(_ id: UUID) throws -> Data {
        let url = fileURL(for: id)
        guard FileManager.default.fileExists(atPath: url.path) else { throw AudioVaultError.missingAudio }
        let data = try Data(contentsOf: url)
        guard data.starts(with: Self.header) else { throw AudioVaultError.invalidFile }
        let box = try AES.GCM.SealedBox(combined: data.dropFirst(Self.header.count))
        return try AES.GCM.open(box, using: try key())
    }

    /// Déchiffre vers un fichier temporaire pour la lecture. L'appelant le supprime après usage.
    public func decryptToTemporaryFile(_ id: UUID) throws -> URL {
        let url = FileManager.default.temporaryDirectory
            .appending(path: "notitia-playback-\(UUID().uuidString).m4a")
        try open(id).write(to: url, options: [.atomic, .completeFileProtection])
        return url
    }

    public func delete(_ id: UUID) throws {
        let url = fileURL(for: id)
        if FileManager.default.fileExists(atPath: url.path) {
            try FileManager.default.removeItem(at: url)
        }
    }

    /// Efface tous les fichiers audio **et** la clé maître.
    public func destroyEverything() throws {
        if FileManager.default.fileExists(atPath: directory.path) {
            try FileManager.default.removeItem(at: directory)
        }
        try keyStore.delete(Self.keyAccount)
        try FileManager.default.createDirectory(at: directory, withIntermediateDirectories: true)
    }

    private func key() throws -> SymmetricKey {
        if let data = try keyStore.data(for: Self.keyAccount), data.count == 32 {
            return SymmetricKey(data: data)
        }
        let key = SymmetricKey(size: .bits256)
        try keyStore.set(key.withUnsafeBytes { Data($0) }, for: Self.keyAccount)
        return key
    }
}
