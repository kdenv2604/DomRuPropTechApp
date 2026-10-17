import Foundation

enum DemoCatalog {
    static let accounts = [AccountProfile(id: "demo-main", title: "Домоуправление №7", login: "техническая служба", isDemo: true),
                           AccountProfile(id: "demo-east", title: "Аварийная служба", login: "дежурный аккаунт", isDemo: true)]

    static func make() -> Catalog {
        let definitions = [("demo-main", "г. Казань", [("Адоратского", [7, 9, 11]), ("Чистопольская", [3, 5]), ("Сибгата Хакима", [15])]),
                           ("demo-east", "г. Зеленодольск", [("Фатыха Амирхана", [21, 23]), ("Ямашева", [44])])]
        let catalogs = definitions.map { account, city, streets -> (String, Catalog) in
            var catalog = Catalog()
            for (name, numbers) in streets {
                let street = Street(id: account + name, name: name, locality: Locality(id: city, name: city))
                catalog.streets.append(street)
                let houses = numbers.map { House(id: street.id + String($0), streetID: street.id, label: "Дом \($0)") }
                catalog.houses[street.id] = houses
                for house in houses {
                    catalog.entrances[house.id] = (1...3).map {
                        Entrance(id: house.id + "-\($0)", houseID: house.id, label: "Подъезд \($0)",
                                 deviceIdentity: house.id + "-\($0)", cameraAvailable: $0 != 2)
                    }
                }
            }
            return (account, catalog)
        }
        return AddressHierarchyMerger.combine(catalogs)
    }
}
