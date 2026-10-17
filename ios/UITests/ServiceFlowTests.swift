import XCTest

final class ServiceFlowTests: XCTestCase {
    private var app: XCUIApplication!

    override func setUpWithError() throws {
        continueAfterFailure = false
        app = XCUIApplication()
        app.launchArguments = ["--ui-testing", "--demo"]
        app.launch()
        XCTAssertTrue(app.buttons["city-г. Казань"].waitForExistence(timeout: 10))
    }

    private func openDemoHouse() {
        app.buttons["city-г. Казань"].tap()
        let house = app.buttons["house-Адоратского-Дом 9"]
        XCTAssertTrue(house.waitForExistence(timeout: 3))
        house.tap()
    }

    private func capture(_ name: String) {
        let attachment = XCTAttachment(screenshot: app.screenshot())
        attachment.name = name
        attachment.lifetime = .keepAlways
        add(attachment)
    }

    func testTreeCameraCodeAndForeground() {
        capture("home")
        openDemoHouse()
        app.buttons["Показать камеру"].firstMatch.tap()
        let video = app.otherElements["Предпросмотр камеры"]
        XCTAssertTrue(video.waitForExistence(timeout: 10))
        let playing = NSPredicate(format: "value == %@", "Видео воспроизводится")
        expectation(for: playing, evaluatedWith: video)
        waitForExpectations(timeout: 20)
        capture("camera-preview")
        app.buttons["КОД"].tap()
        XCTAssertTrue(app.staticTexts["door-code"].waitForExistence(timeout: 3))
        XCTAssertEqual(app.staticTexts["door-code"].label, "1234")
        app.buttons["Готово"].tap()
        XCUIDevice.shared.press(.home)
        app.activate()
        XCTAssertTrue(video.waitForExistence(timeout: 5))
        expectation(for: playing, evaluatedWith: video)
        waitForExpectations(timeout: 20)
        capture("camera-after-foreground")
    }

    func testHandednessAndSettingsAreSeparateFromAccounts() {
        openDemoHouse()
        let open = app.buttons.matching(NSPredicate(format: "identifier BEGINSWITH %@", "open-")).firstMatch
        XCTAssertTrue(open.waitForExistence(timeout: 3))
        let originalX = open.frame.midX
        app.buttons["settings"].tap()
        app.buttons["Левая"].tap()
        capture("settings-left-hand")
        app.buttons["Готово"].tap()
        XCTAssertGreaterThan(open.frame.midX, originalX + 50)
        capture("left-hand-entrances")
        app.buttons["accounts"].tap()
        XCTAssertTrue(app.staticTexts["Домоуправление №7"].waitForExistence(timeout: 3))
        XCTAssertFalse(app.staticTexts["Управление одной рукой"].exists)
    }

    func testSearchFindsAddressWithPartialWords() {
        let search = app.textFields["address-search"]
        search.tap(); search.typeText("Адо 9")
        XCTAssertTrue(app.buttons.containing(.staticText, identifier: "Адоратского · Дом 9").firstMatch.waitForExistence(timeout: 3))
        XCTAssertFalse(app.buttons.containing(.staticText, identifier: "Адоратского · Дом 7").firstMatch.exists)
        capture("address-search")
    }
}
