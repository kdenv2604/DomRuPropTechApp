import XCTest
@testable import DomruTechnics

final class AddressTests: XCTestCase {
    func testAddressPartsAndBuildingSuffix() {
        let parts = RussianAddressParser.parse("Республика Татарстан, г. Казань, ул. 8 Марта, 12, корпус 1")
        XCTAssertEqual(parts.locality, "г. Казань")
        XCTAssertEqual(parts.street, "ул. 8 Марта")
        XCTAssertEqual(parts.house, "Дом 12, корпус 1")
    }

    func testHouseRouteDoesNotShiftEmptySegments() {
        XCTAssertEqual(HouseRoute.decode("1.company.123.7."), HouseRoute(houseID: "123", porch: "7"))
        XCTAssertNil(HouseRoute.decode("1..123.7"))
        XCTAssertNil(HouseRoute.decode("9.company.123.7"))
    }

    func testPartialAddressSearchAndYoNormalization() {
        XCTAssertTrue(AddressSearch.matches(["Казань", "Адоратского", "Дом 9"], query: "Адо 9"))
        XCTAssertTrue(AddressSearch.matches(["Орёл", "Берёзовая", "Дом 12"], query: "орел бере 12"))
        XCTAssertFalse(AddressSearch.matches(["Казань", "Адоратского", "Дом 9"], query: "Москва 9"))
    }

    func testCitiesDoNotMergeAndAccountOrderIsStable() {
        let a = Street(id: "a", name: "Центральная", locality: Locality(id: "a", name: "г. Казань"), sources: [AccessSource(accountID: "a", remoteID: "a")])
        let b = Street(id: "b", name: "Центральная", locality: Locality(id: "b", name: "Казань"), sources: [AccessSource(accountID: "b", remoteID: "b")])
        let c = Street(id: "c", name: "Центральная", locality: Locality(id: "c", name: "Москва"))
        let merged = AddressHierarchyMerger.streets([a,b,c])
        XCTAssertEqual(merged.count, 2)
        XCTAssertEqual(merged.first { $0.locality.name == "г. Казань" }?.sources.map(\.accountID), ["a", "b"])
    }

    func testDifferentCamerasWithSameNameNeverMerge() {
        let house = House(id: "h", streetID: "s", label: "Дом 9")
        let a = Entrance(id: "a", houseID: "h", label: "Подъезд 1", deviceIdentity: "device-a", cameraAvailable: true,
                         sources: [AccessSource(accountID: "a", remoteID: "a", canVideo: true)])
        let b = Entrance(id: "b", houseID: "h", label: "Подъезд 1", deviceIdentity: "device-b", cameraAvailable: false,
                         sources: [AccessSource(accountID: "b", remoteID: "b", canVideo: false)])
        XCTAssertEqual(AddressHierarchyMerger.entrances([a,b], parent: house).count, 2)
        let duplicate = Entrance(id: "c", houseID: "h", label: "Подъезд 1", deviceIdentity: "device-a", cameraAvailable: false,
                                 sources: [AccessSource(accountID: "c", remoteID: "c", canVideo: false)])
        let same = AddressHierarchyMerger.entrances([a,duplicate], parent: house)
        XCTAssertEqual(same.count, 1)
        XCTAssertEqual(same[0].sources.map(\.canVideo), [true, false])
    }

    func testHouse12DoesNotStealHouse123Camera() {
        var builder = RemoteCatalogBuilder()
        let specification = PortalSpecification(id: "s", companyID: "c", locality: "Казань")
        builder.add(specification: specification, locality: "Казань",
                    houses: [PortalHouse(id: "12", address: "Тестовая, д. 12", route: "legacy.12"),
                             PortalHouse(id: "123", address: "Тестовая, д. 123", route: "legacy.123")], porches: [],
                    devices: [PortalDevice(id: "device", accessControlID: "control", name: "Калитка", route: "legacy.123.1", cameraAvailable: true)])
        let catalog = builder.build(complete: true)
        XCTAssertEqual(catalog.entrances["s::house::12"]?.count, 0)
        XCTAssertEqual(catalog.entrances["s::house::123"]?.first?.deviceIdentity, "device")
    }

    func testUnassignedDeviceIsRetained() {
        var builder = RemoteCatalogBuilder()
        builder.add(specification: PortalSpecification(id: "s", companyID: "c", locality: nil), locality: nil, houses: [], porches: [],
                    devices: [PortalDevice(id: "door", accessControlID: "control", name: "Ворота", route: "unknown", cameraAvailable: true)])
        let catalog = builder.build(complete: false)
        XCTAssertFalse(catalog.complete)
        XCTAssertEqual(catalog.streets.first?.name, "Другие объекты")
        XCTAssertEqual(catalog.doors.count, 1)
    }
}
