import CoreGraphics
import ImageIO
import UniformTypeIdentifiers
import Foundation

// Геометрия исходной Android-иконки: android/app/src/main/res/drawable/ic_launcher.xml.
let root = URL(fileURLWithPath: CommandLine.arguments.count > 1 ? CommandLine.arguments[1] : ".")
let directory = root.appendingPathComponent("Resources/Assets.xcassets/AppIcon.appiconset")
try FileManager.default.createDirectory(at: directory, withIntermediateDirectories: true)
var images: [[String: String]] = []
let sizes: [(String, Double, [Int])] = [("iphone", 20, [2,3]), ("iphone", 29, [2,3]), ("iphone", 40, [2,3]), ("iphone", 60, [2,3]),
                                      ("ipad", 20, [1,2]), ("ipad", 29, [1,2]), ("ipad", 40, [1,2]), ("ipad", 76, [1,2]), ("ipad", 83.5, [2]),
                                      ("ios-marketing", 1024, [1])]
for (idiom, points, scales) in sizes {
    for scale in scales {
        let pixels = Int(points * Double(scale))
        let name = "icon-\(pixels).png"
        let context = CGContext(data: nil, width: pixels, height: pixels, bitsPerComponent: 8, bytesPerRow: pixels * 4,
                                space: CGColorSpaceCreateDeviceRGB(), bitmapInfo: CGImageAlphaInfo.noneSkipLast.rawValue)!
        let factor = CGFloat(pixels) / 108
        func point(_ x: CGFloat, _ y: CGFloat) -> CGPoint { CGPoint(x: x * factor, y: (108-y) * factor) }
        let navy = CGColor(red: 22/255, green: 38/255, blue: 59/255, alpha: 1)
        context.setFillColor(navy); context.fill(CGRect(x: 0, y: 0, width: pixels, height: pixels))
        context.setFillColor(CGColor(red: 240/255, green: 74/255, blue: 74/255, alpha: 1))
        context.move(to: point(54,18))
        for (x,y): (CGFloat,CGFloat) in [(88,42),(88,90),(20,90),(20,42)] { context.addLine(to: point(x,y)) }
        context.closePath(); context.fillPath()
        context.setFillColor(CGColor(gray: 1, alpha: 1)); context.fill(CGRect(x: 43*factor, y: 18*factor, width: 22*factor, height: 40*factor))
        context.setFillColor(navy); context.fillEllipse(in: CGRect(x: 52*factor, y: 36*factor, width: 6*factor, height: 6*factor))
        let destination = CGImageDestinationCreateWithURL(directory.appendingPathComponent(name) as CFURL, UTType.png.identifier as CFString, 1, nil)!
        CGImageDestinationAddImage(destination, context.makeImage()!, nil)
        precondition(CGImageDestinationFinalize(destination))
        let size = points == floor(points) ? String(Int(points)) : String(points)
        images.append(["idiom": idiom, "size": "\(size)x\(size)", "scale": "\(scale)x", "filename": name])
    }
}
try JSONSerialization.data(withJSONObject: ["images": images, "info": ["author": "xcode", "version": 1]], options: [.prettyPrinted, .sortedKeys])
    .write(to: directory.appendingPathComponent("Contents.json"))
try Data(#"{"info":{"author":"xcode","version":1}}"#.utf8)
    .write(to: root.appendingPathComponent("Resources/Assets.xcassets/Contents.json"))
