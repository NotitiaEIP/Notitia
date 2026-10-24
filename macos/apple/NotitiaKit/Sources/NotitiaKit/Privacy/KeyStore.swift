import Foundation
import Security

/// Stockage de secrets (clé de chiffrement de l'audio, futures clés d'API utilisateur).
public protocol KeyStore: Sendable {
    func data(for account: String) throws -> Data?
    func set(_ data: Data, for account: String) throws
    func delete(_ account: String) throws
}

public struct KeyStoreError: LocalizedError {
    public var status: OSStatus
    public var errorDescription: String? {
        "Erreur du trousseau (\(status)) : \(SecCopyErrorMessageString(status, nil) as String? ?? "inconnue")."
    }
}

/// Trousseau macOS/iOS. La clé ne quitte jamais l'appareil
/// (`kSecAttrAccessibleWhenUnlockedThisDeviceOnly` : ni synchronisation iCloud, ni sauvegarde).
public struct KeychainKeyStore: KeyStore {
    public let service: String

    public init(service: String = "com.notitia.Notitia") {
        self.service = service
    }

    private func query(_ account: String, dataProtection: Bool) -> [String: Any] {
        var query: [String: Any] = [
            kSecClass as String: kSecClassGenericPassword,
            kSecAttrService as String: service,
            kSecAttrAccount as String: account,
        ]
        if dataProtection { query[kSecUseDataProtectionKeychain as String] = true }
        return query
    }

    /// Le trousseau « data protection » exige une signature avec équipe de développement.
    /// En développement (signature locale), on se replie sur le trousseau classique.
    private func perform<T>(_ body: (_ dataProtection: Bool) throws -> T) throws -> T {
        do {
            return try body(true)
        } catch let error as KeyStoreError where error.status == errSecMissingEntitlement {
            return try body(false)
        }
    }

    public func data(for account: String) throws -> Data? {
        try perform { dataProtection in
            var query = query(account, dataProtection: dataProtection)
            query[kSecReturnData as String] = true
            query[kSecMatchLimit as String] = kSecMatchLimitOne
            var result: CFTypeRef?
            let status = SecItemCopyMatching(query as CFDictionary, &result)
            if status == errSecItemNotFound { return nil }
            guard status == errSecSuccess else { throw KeyStoreError(status: status) }
            return result as? Data
        }
    }

    public func set(_ data: Data, for account: String) throws {
        try perform { dataProtection in
            let base = query(account, dataProtection: dataProtection)
            let update: [String: Any] = [kSecValueData as String: data]
            var status = SecItemUpdate(base as CFDictionary, update as CFDictionary)
            if status == errSecItemNotFound {
                var add = base
                add[kSecValueData as String] = data
                add[kSecAttrAccessible as String] = kSecAttrAccessibleWhenUnlockedThisDeviceOnly
                status = SecItemAdd(add as CFDictionary, nil)
            }
            guard status == errSecSuccess else { throw KeyStoreError(status: status) }
        }
    }

    public func delete(_ account: String) throws {
        try perform { dataProtection in
            let status = SecItemDelete(query(account, dataProtection: dataProtection) as CFDictionary)
            guard status == errSecSuccess || status == errSecItemNotFound else { throw KeyStoreError(status: status) }
        }
    }
}

/// Stockage en mémoire, pour les tests.
public final class InMemoryKeyStore: KeyStore, @unchecked Sendable {
    private var storage: [String: Data] = [:]
    private let lock = NSLock()

    public init() {}

    public func data(for account: String) throws -> Data? {
        lock.withLock { storage[account] }
    }

    public func set(_ data: Data, for account: String) throws {
        lock.withLock { storage[account] = data }
    }

    public func delete(_ account: String) throws {
        _ = lock.withLock { storage.removeValue(forKey: account) }
    }

    public var isEmpty: Bool { lock.withLock { storage.isEmpty } }
}
