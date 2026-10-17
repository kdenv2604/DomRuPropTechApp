import Foundation

enum AddressSearch {
    static func canonical(_ value: String) -> String {
        value.lowercased(with: Locale(identifier: "ru"))
            .replacingOccurrences(of: "ё", with: "е")
            .replacingOccurrences(of: "[^а-яa-z0-9]+", with: " ", options: .regularExpression)
            .trimmingCharacters(in: .whitespacesAndNewlines)
    }

    static func localityKey(_ value: String) -> String {
        canonical(value).replacingOccurrences(
            of: "^(г|город|с|село|п|пос|поселок|пгт|дер|деревня|станица|аул)\\s+",
            with: "", options: .regularExpression)
    }

    static func matches(_ values: [String], query: String) -> Bool {
        let words = values.flatMap { canonical($0).split(separator: " ") }
        return canonical(query).split(separator: " ").allSatisfy { token in
            words.contains { $0.hasPrefix(token) }
        }
    }

    static func numericOrder(_ left: String, _ right: String) -> Bool {
        func number(_ text: String) -> Int {
            guard let range = text.range(of: "\\d+", options: .regularExpression) else { return .max }
            return Int(text[range]) ?? .max
        }
        let a = number(left), b = number(right)
        return a == b ? canonical(left) < canonical(right) : a < b
    }
}

enum RussianAddressParser {
    struct Parts: Equatable { let locality: String?; let street: String; let house: String }
    private static let streetPrefix = "(?i)^(ул\\.?|улица|проспект|пр\\.?-?т|переулок|пер\\.?|бульвар|бул\\.?|шоссе|набережная|наб\\.?|площадь|пл\\.?|проезд|тракт|аллея|микрорайон|мкр\\.?)\\s+"
    private static let localityPrefix = "(?i)^(г\\.?|город|с\\.?|село|п\\.?|пос\\.?|пос[её]лок|пгт\\.?|дер\\.?|деревня|станица|аул)\\s+"

    static func parse(_ address: String) -> Parts {
        let trimmed = address.trimmingCharacters(in: .whitespacesAndNewlines)
        let clean = trimmed.isEmpty ? "Адрес не указан" : trimmed
        let chunks = clean.split(separator: ",").map { $0.trimmingCharacters(in: .whitespaces) }.filter { !$0.isEmpty }
        if chunks.count >= 2 {
            var houseStart = chunks.firstIndex { $0.range(of: "(?i)^(д\\.?|дом|владение|корпус)\\s*\\d", options: .regularExpression) != nil } ?? chunks.count - 1
            if houseStart == 0 { houseStart = chunks.count - 1 }
            if houseStart > 1, chunks[houseStart].lowercased().hasPrefix("корпус"),
               chunks[houseStart - 1].range(of: "(?i)^\\d+[а-яa-z]?(?:[/-]\\d+[а-яa-z]?)?$", options: .regularExpression) != nil {
                houseStart -= 1
            }
            let before = Array(chunks.prefix(houseStart))
            let streetStart = before.firstIndex { $0.range(of: streetPrefix, options: .regularExpression) != nil } ?? max(0, before.count - 1)
            let localityChunks = before.prefix(streetStart)
            let locality = localityChunks.last { $0.range(of: localityPrefix, options: .regularExpression) != nil } ?? localityChunks.last
            return Parts(locality: locality, street: before.dropFirst(streetStart).joined(separator: ", "),
                         house: houseLabel(chunks.dropFirst(houseStart).joined(separator: ", ")))
        }
        if let range = clean.range(of: "(?i)д\\.?\\s*\\d+[а-яa-z0-9/\\-]*(?:\\s*,?.*)?$", options: .regularExpression), range.lowerBound > clean.startIndex {
            return Parts(locality: nil, street: String(clean[..<range.lowerBound]).trimmingCharacters(in: CharacterSet(charactersIn: " ,")),
                         house: houseLabel(String(clean[range])))
        }
        return Parts(locality: nil, street: clean, house: "Дом")
    }

    private static func houseLabel(_ value: String) -> String {
        "Дом " + value.replacingOccurrences(of: "(?i)^(д\\.|дом)\\s*", with: "", options: .regularExpression)
    }
}

struct HouseRoute: Equatable {
    let houseID: String
    let porch: String?

    static func decode(_ value: String) -> HouseRoute? {
        var text = value
        while text.hasSuffix(".") { text.removeLast() }
        let parts = text.components(separatedBy: ".")
        // Пустой сегмент нельзя удалять: иначе дверь может привязаться к другому дому.
        guard parts.count >= 3, ["1", "2"].contains(parts[0]), !parts.contains(where: { $0.trimmingCharacters(in: .whitespaces).isEmpty }) else { return nil }
        return HouseRoute(houseID: parts[2], porch: parts.count > 3 ? parts[3] : nil)
    }
}

enum AddressHierarchyMerger {
    private static func uniqueSources(_ items: [AccessSource]) -> [AccessSource] {
        var seen = Set<String>()
        return items.filter { seen.insert($0.accountID + "|" + $0.remoteID).inserted }
    }

    static func streets(_ items: [Street]) -> [Street] {
        let groups = Dictionary(grouping: items) { AddressSearch.localityKey($0.locality.name) + "|" + AddressSearch.canonical($0.name) }
        return groups.map { key, group in
            let first = group[0]
            let locality = first.locality.name == Locality.unknown.name ? Locality.unknown :
                Locality(id: "locality:" + AddressSearch.localityKey(first.locality.name), name: first.locality.name)
            return Street(id: "street:" + key, name: first.name, locality: locality,
                          sources: uniqueSources(group.flatMap(\.sources)))
        }.sorted {
            (AddressSearch.canonical($0.locality.name), AddressSearch.canonical($0.name)) <
            (AddressSearch.canonical($1.locality.name), AddressSearch.canonical($1.name))
        }
    }

    static func houses(_ items: [House], parent: Street) -> [House] {
        Dictionary(grouping: items) { AddressSearch.canonical($0.label) }.map { key, group in
            House(id: "house:\(parent.id):\(key)", streetID: parent.id, label: group[0].label,
                  sources: uniqueSources(group.flatMap(\.sources)))
        }.sorted { AddressSearch.numericOrder($0.label, $1.label) }
    }

    static func entrances(_ items: [Entrance], parent: House) -> [Entrance] {
        Dictionary(grouping: items) { item in
            item.deviceIdentity.map { "device:" + $0 } ?? "label:" + AddressSearch.canonical(item.label)
        }.map { key, group in
            Entrance(id: "entrance:\(parent.id):\(key)", houseID: parent.id, label: group[0].label,
                     deviceIdentity: group[0].deviceIdentity, cameraAvailable: group.contains(where: \.cameraAvailable),
                     sources: uniqueSources(group.flatMap(\.sources)))
        }.sorted { AddressSearch.numericOrder($0.label, $1.label) }
    }

    static func combine(_ catalogs: [(String, Catalog)]) -> Catalog {
        var result = Catalog()
        let rawStreets = catalogs.flatMap { account, catalog in
            catalog.streets.map { Street(id: $0.id, name: $0.name, locality: $0.locality, sources: [AccessSource(accountID: account, remoteID: $0.id)]) }
        }
        result.streets = streets(rawStreets)
        for street in result.streets {
            let rawHouses = street.sources.flatMap { source -> [House] in
                let catalog = catalogs.first { $0.0 == source.accountID }?.1
                return (catalog?.houses[source.remoteID] ?? []).map {
                    House(id: $0.id, streetID: $0.streetID, label: $0.label, sources: [AccessSource(accountID: source.accountID, remoteID: $0.id)])
                }
            }
            let mergedHouses = houses(rawHouses, parent: street)
            result.houses[street.id] = mergedHouses
            for house in mergedHouses {
                let rawEntrances = house.sources.flatMap { source -> [Entrance] in
                    let catalog = catalogs.first { $0.0 == source.accountID }?.1
                    return (catalog?.entrances[source.remoteID] ?? []).map {
                        Entrance(id: $0.id, houseID: $0.houseID, label: $0.label, deviceIdentity: $0.deviceIdentity,
                                 cameraAvailable: $0.cameraAvailable,
                                 sources: [AccessSource(accountID: source.accountID, remoteID: $0.id, canVideo: $0.cameraAvailable)])
                    }
                }
                result.entrances[house.id] = entrances(rawEntrances, parent: house)
            }
        }
        result.complete = catalogs.allSatisfy { $0.1.complete }
        return result
    }
}
