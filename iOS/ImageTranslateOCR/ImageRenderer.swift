import UIKit

enum ImageRenderer {
    static func render(image: UIImage, regions: [TranslatedRegion], mode: MaskMode) -> UIImage {
        guard let source = image.normalizedCGImage else { return image }
        let size = CGSize(width: source.width, height: source.height)
        let format = UIGraphicsImageRendererFormat()
        format.scale = 1
        format.opaque = false
        return UIGraphicsImageRenderer(size: size, format: format).image { renderer in
            let context = renderer.cgContext
            context.draw(source, in: CGRect(origin: .zero, size: size))
            for region in regions where region.sourceText != region.translatedText {
                let bounds = region.bounds.intersection(CGRect(origin: .zero, size: size))
                guard !bounds.isNull, bounds.width > 1, bounds.height > 1 else { continue }
                erase(bounds, in: context, source: source, mode: mode)
                draw(region.translatedText, in: bounds, context: context, source: source)
            }
        }
    }

    private static func erase(_ rect: CGRect, in context: CGContext, source: CGImage, mode: MaskMode) {
        let padding = mode == .precise ? max(2, rect.height * 0.08) : 4
        let area = rect.insetBy(dx: -padding, dy: -padding)
            .intersection(CGRect(x: 0, y: 0, width: source.width, height: source.height))
        let color = sampledBackground(around: area, source: source)
        context.saveGState()
        context.setFillColor(color.cgColor)
        if mode == .precise {
            let radius = min(area.height * 0.16, 8)
            context.addPath(UIBezierPath(roundedRect: area, cornerRadius: radius).cgPath)
            context.fillPath()
        } else {
            context.fill(area)
        }
        context.restoreGState()
    }

    private static func sampledBackground(around rect: CGRect, source: CGImage) -> UIColor {
        guard let data = source.dataProvider?.data, let bytes = CFDataGetBytePtr(data),
              source.bitsPerPixel >= 24 else { return .white }
        let points = [
            CGPoint(x: rect.minX, y: rect.minY), CGPoint(x: rect.midX, y: rect.minY),
            CGPoint(x: rect.maxX - 1, y: rect.minY), CGPoint(x: rect.minX, y: rect.maxY - 1),
            CGPoint(x: rect.midX, y: rect.maxY - 1), CGPoint(x: rect.maxX - 1, y: rect.maxY - 1)
        ]
        var red = 0, green = 0, blue = 0
        for point in points {
            let x = min(max(Int(point.x), 0), source.width - 1)
            let y = min(max(Int(point.y), 0), source.height - 1)
            let offset = y * source.bytesPerRow + x * max(source.bitsPerPixel / 8, 4)
            red += Int(bytes[offset]); green += Int(bytes[offset + 1]); blue += Int(bytes[offset + 2])
        }
        let count = CGFloat(points.count) * 255
        return UIColor(red: CGFloat(red) / count, green: CGFloat(green) / count,
                       blue: CGFloat(blue) / count, alpha: 1)
    }

    private static func draw(_ text: String, in rect: CGRect, context: CGContext, source: CGImage) {
        let background = sampledBackground(around: rect, source: source)
        var white: CGFloat = 1
        background.getWhite(&white, alpha: nil)
        let color: UIColor = white < 0.48 ? .white : .black
        let paragraph = NSMutableParagraphStyle()
        paragraph.alignment = rect.height < 80 ? .center : .left
        paragraph.lineBreakMode = .byWordWrapping
        let inset = max(1, rect.height * 0.06)
        let target = rect.insetBy(dx: inset, dy: 0)
        var low: CGFloat = 7
        var high = max(9, rect.height * 0.86)
        for _ in 0..<9 {
            let size = (low + high) / 2
            let attributes: [NSAttributedString.Key: Any] = [
                .font: UIFont.systemFont(ofSize: size, weight: .semibold),
                .foregroundColor: color, .paragraphStyle: paragraph
            ]
            let measured = (text as NSString).boundingRect(
                with: CGSize(width: target.width, height: .greatestFiniteMagnitude),
                options: [.usesLineFragmentOrigin, .usesFontLeading], attributes: attributes, context: nil)
            if measured.height <= target.height { low = size } else { high = size }
        }
        let attributes: [NSAttributedString.Key: Any] = [
            .font: UIFont.systemFont(ofSize: low, weight: .semibold),
            .foregroundColor: color, .paragraphStyle: paragraph
        ]
        let measured = (text as NSString).boundingRect(with: target.size,
            options: [.usesLineFragmentOrigin, .usesFontLeading], attributes: attributes, context: nil)
        let drawingRect = CGRect(x: target.minX, y: target.midY - measured.height / 2,
                                 width: target.width, height: min(measured.height, target.height))
        UIGraphicsPushContext(context)
        (text as NSString).draw(with: drawingRect, options: [.usesLineFragmentOrigin, .usesFontLeading],
                                attributes: attributes, context: nil)
        UIGraphicsPopContext()
    }
}

extension UIImage {
    var normalizedCGImage: CGImage? {
        if imageOrientation == .up, let cgImage { return cgImage }
        let format = UIGraphicsImageRendererFormat(); format.scale = 1
        return UIGraphicsImageRenderer(size: size, format: format).image { _ in
            draw(in: CGRect(origin: .zero, size: size))
        }.cgImage
    }
}
