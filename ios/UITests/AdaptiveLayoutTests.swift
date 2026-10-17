import XCTest

final class AdaptiveLayoutTests: XCTestCase {
    func testCompactLargeTextStillAllowsCameraAndDoorActions() {
        continueAfterFailure = false
        let app = XCUIApplication()
        app.launchArguments = ["--ui-testing", "--demo"]
        app.launch()
        XCTAssertTrue(app.buttons["settings"].waitForExistence(timeout: 10))
        let title = app.staticTexts["Служебный доступ"]
        XCTAssertLessThan(title.frame.height, 80, "The header uses a fixed size even with accessibility text")
        reveal(app.buttons["city-г. Казань"], in: app)
        app.buttons["city-г. Казань"].tap()
        let house = app.buttons["house-Адоратского-Дом 9"]
        reveal(house, in: app)
        house.tap()
        let expand = app.buttons.matching(NSPredicate(format: "identifier BEGINSWITH %@", "expand-")).firstMatch
        reveal(expand, in: app)
        expand.tap()
        // На маленьком экране при максимальном шрифте до кнопок по-прежнему можно прокрутить.
        let code = app.buttons["КОД"]
        for _ in 0..<8 where !code.isHittable { app.swipeUp() }
        XCTAssertTrue(code.isHittable)
        let open = app.buttons.matching(NSPredicate(format: "identifier BEGINSWITH %@", "open-")).firstMatch
        XCTAssertTrue(open.exists)
        let attachment = XCTAttachment(screenshot: app.screenshot())
        attachment.name = "compact-dark-large-text"
        attachment.lifetime = .keepAlways
        add(attachment)
        code.tap()
        XCTAssertTrue(app.staticTexts["door-code"].waitForExistence(timeout: 5))
        XCTAssertEqual(app.staticTexts["door-code"].label, "1234")
    }

    private func reveal(_ element: XCUIElement, in app: XCUIApplication) {
        for _ in 0..<12 where !element.exists || !element.isHittable {
            app.scrollViews.firstMatch.swipeUp(velocity: .slow)
        }
        if !element.exists || !element.isHittable {
            let attachment = XCTAttachment(screenshot: app.screenshot())
            attachment.name = "unreachable-control"
            attachment.lifetime = .keepAlways
            add(attachment)
        }
        XCTAssertTrue(element.exists)
        XCTAssertTrue(element.isHittable)
    }
}
