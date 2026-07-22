import AppKit
import Foundation

@main
struct OCRSmoke {
    static func main() async {
        guard CommandLine.arguments.count == 2 else {
            fputs("usage: OCRSmoke <image>\n", stderr)
            exit(2)
        }
        let url = URL(fileURLWithPath: CommandLine.arguments[1])
        guard let image = NSImage(contentsOf: url) else {
            fputs("cannot load image: \(url.path)\n", stderr)
            exit(2)
        }
        do {
            let regions = try await OCRService().recognize(image)
            print("recognized_regions=\(regions.count)")
            for region in regions.prefix(10) {
                print("\(Int(region.bounds.minX)),\(Int(region.bounds.minY)),\(Int(region.bounds.width)),\(Int(region.bounds.height))\t\(region.sourceText)")
            }
            if regions.isEmpty { exit(1) }
        } catch {
            fputs("OCR failed: \(error.localizedDescription)\n", stderr)
            exit(1)
        }
    }
}
