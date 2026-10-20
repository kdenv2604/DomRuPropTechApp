import Foundation
import CryptoKit

/// Снимок адресов не содержит сессий, кодов открытия или временных ссылок камер.
/// Храним его в Application Support: очистка системного cache не должна убирать быстрый старт.
struct CatalogDiskStore {
    private struct Record: Codable {
        let version: Int
        let binding: String
        let catalog: Catalog
    }
    let directory: URL

    init(directory: URL = FileManager.default.urls(for: .applicationSupportDirectory, in: .userDomainMask)[0]
        .appendingPathComponent("AccessCatalogs", isDirectory: true)) {
        self.directory = directory
    }

    func read(accountID: String, binding: String) -> Catalog? {
        guard let data = try? Data(contentsOf: file(accountID)),
              let record = try? JSONDecoder().decode(Record.self, from: data),
              record.version == 1, record.binding == binding else { return nil }
        return record.catalog
    }

    func write(accountID: String, binding: String, catalog: Catalog) throws {
        try FileManager.default.createDirectory(at: directory, withIntermediateDirectories: true)
        var excludedDirectory = directory
        var values = URLResourceValues()
        values.isExcludedFromBackup = true
        try excludedDirectory.setResourceValues(values)
        let data = try JSONEncoder().encode(Record(version: 1, binding: binding, catalog: catalog))
        try data.write(to: file(accountID), options: [.atomic, .completeFileProtection])
    }

    func remove(_ accountID: String) { try? FileManager.default.removeItem(at: file(accountID)) }

    private func file(_ accountID: String) -> URL {
        let digest = SHA256.hash(data: Data(accountID.utf8)).map { String(format: "%02x", $0) }.joined()
        return directory.appendingPathComponent(digest + ".json")
    }
}

extension StoredSession {
    var catalogBinding: String { "\(accountID)|\(login)|\(createdAt.timeIntervalSince1970)" }
}

extension Catalog {
    /// Только временно недоступная компания сохраняет свои прежние объекты.
    /// Исчезнувшие из полного ответа компании и подтверждённые запреты не переносим.
    func forSpecification(_ id: String) -> Catalog {
        Catalog(streets: streets.filter { $0.id.hasPrefix(id + "::") },
                houses: houses.filter { $0.key.hasPrefix(id + "::") },
                entrances: entrances.filter { $0.key.hasPrefix(id + "::") },
                doors: doors.filter { $0.value.specificationID == id }, complete: false)
    }

    mutating func retain(_ previous: Catalog) {
        streets += previous.streets
        houses.merge(previous.houses) { fresh, _ in fresh }
        entrances.merge(previous.entrances) { fresh, _ in fresh }
        doors.merge(previous.doors) { fresh, _ in fresh }
        complete = false
    }
}
