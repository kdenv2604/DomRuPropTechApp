import Foundation

struct RemoteCatalogBuilder {
    private(set) var catalog = Catalog()

    mutating func add(specification: PortalSpecification, locality: String?, houses: [PortalHouse],
                      porches: [PortalPorch], devices: [PortalDevice]) {
        let devicesByHouse = Dictionary(grouping: devices) { device -> String in
            let matches = Set(houses.filter { Self.matches($0, route: device.route) }.map(\.id))
            // Неоднозначный маршрут оставляем без адресной привязки, чтобы не спутать камеры домов.
            return matches.count == 1 ? matches.first! : ""
        }
        for house in houses {
            let parts = RussianAddressParser.parse(house.address)
            let localityName = parts.locality ?? locality ?? Locality.unknown.name
            let localityKey = AddressSearch.localityKey(localityName)
            let streetID = remoteID(specification.id, "street", localityKey + "|" + AddressSearch.canonical(parts.street))
            let houseID = remoteID(specification.id, "house", house.id)
            let street = Street(id: streetID, name: parts.street,
                                locality: Locality(id: remoteID(specification.id, "locality", localityKey), name: localityName))
            if !catalog.streets.contains(where: { $0.id == streetID }) { catalog.streets.append(street) }
            catalog.houses[streetID, default: []].append(House(id: houseID, streetID: streetID, label: parts.house))
            let houseIDs = Set([house.id, HouseRoute.decode(house.route)?.houseID].compactMap { $0 })
            let housePorches = porches.filter {
                (!house.route.isEmpty && $0.parentRoute == house.route) ||
                HouseRoute.decode($0.route).map { houseIDs.contains($0.houseID) } == true
            }
            catalog.entrances[houseID] = (devicesByHouse[house.id] ?? []).map { device in
                let route = HouseRoute.decode(device.route)
                // Номер подъезда ищем только среди подъездов уже выбранного дома.
                let matched = housePorches.first { candidate in
                    candidate.route == device.route || route?.porch.map { [candidate.id, candidate.number].contains($0) } == true
                }
                return entrance(device, specificationID: specification.id, houseID: houseID, label: matched.map { "Подъезд " + $0.number } ?? device.name)
            }
        }
        if let unassigned = devicesByHouse[""], !unassigned.isEmpty {
            let streetID = remoteID(specification.id, "street", "other")
            let houseID = remoteID(specification.id, "house", "other")
            catalog.streets.append(Street(id: streetID, name: "Другие объекты", locality: .unknown))
            catalog.houses[streetID] = [House(id: houseID, streetID: streetID, label: "Без адресной привязки")]
            catalog.entrances[houseID] = unassigned.map {
                entrance($0, specificationID: specification.id, houseID: houseID, label: $0.name)
            }
        }
    }

    mutating func build(complete: Bool) -> Catalog {
        catalog.complete = complete
        catalog.streets.sort { (AddressSearch.canonical($0.locality.name), AddressSearch.canonical($0.name)) <
                              (AddressSearch.canonical($1.locality.name), AddressSearch.canonical($1.name)) }
        return catalog
    }

    private mutating func entrance(_ device: PortalDevice, specificationID: String, houseID: String, label: String) -> Entrance {
        let id = remoteID(specificationID, "door", device.id)
        catalog.doors[id] = RemoteDoor(specificationID: specificationID, deviceID: device.id,
                                      accessControlID: device.accessControlID, cameraAvailable: device.cameraAvailable)
        return Entrance(id: id, houseID: houseID, label: label, deviceIdentity: device.id, cameraAvailable: device.cameraAvailable)
    }

    private static func matches(_ house: PortalHouse, route: String) -> Bool {
        if let id = HouseRoute.decode(route)?.houseID,
           [house.id, HouseRoute.decode(house.route)?.houseID].contains(id) { return true }
        var path = house.route
        while path.hasSuffix(".") { path.removeLast() }
        return !path.isEmpty && (route == path || route.hasPrefix(path + "."))
    }
    private func remoteID(_ specification: String, _ kind: String, _ value: String) -> String { "\(specification)::\(kind)::\(value)" }
}
