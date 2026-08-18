import AppKit

enum ImageRenderer {
    static func render(
        image: NSImage,
        regions: [TranslatedRegion],
        mode: MaskMode,
        engine: EraseEngine
    ) -> NSImage {
        let changedRegions = regions.filter { $0.sourceText != $0.translatedText }
        guard let originalSource = image.cgImageForProcessing else { return image }
        let source = if engine == .openCV, OpenCVWrapper.isAvailable() {
            OpenCVWrapper.createInpaintedImage(
                originalSource,
                regions: changedRegions.map { NSValue(rect: $0.bounds) },
                precise: mode == .precise
            ) ?? originalSource
        } else {
            originalSource
        }
        let colorSource = originalSource
        let width = source.width, height = source.height
        guard let bitmap = NSBitmapImageRep(bitmapDataPlanes: nil, pixelsWide: width, pixelsHigh: height,
            bitsPerSample: 8, samplesPerPixel: 4, hasAlpha: true, isPlanar: false,
            colorSpaceName: .deviceRGB, bytesPerRow: 0, bitsPerPixel: 0),
              let context = NSGraphicsContext(bitmapImageRep: bitmap) else { return image }

        NSGraphicsContext.saveGraphicsState()
        NSGraphicsContext.current = context
        NSImage(cgImage: source, size: CGSize(width: width, height: height))
            .draw(in: CGRect(x: 0, y: 0, width: width, height: height))

        let canvas = CGRect(x: 0, y: 0, width: width, height: height)
        for region in changedRegions {
            let sourceBounds = region.bounds.intersection(canvas)
            guard !sourceBounds.isNull, sourceBounds.width > 1, sourceBounds.height > 1 else { continue }

            // Vision reports top-left coordinates; AppKit draws text in a
            // bottom-left coordinate system. Convert the rectangle instead of
            // flipping the context, which would also turn the glyphs upside down.
            let drawingBounds = CGRect(
                x: sourceBounds.minX,
                y: CGFloat(height) - sourceBounds.maxY,
                width: sourceBounds.width,
                height: sourceBounds.height
            )
            let color = backgroundColor(source: colorSource, around: sourceBounds)
            if engine == .system || !OpenCVWrapper.isAvailable() {
                color.setFill()
                let padding = mode == .precise ? max(2, drawingBounds.height * 0.08) : 4
                let erased = drawingBounds.insetBy(dx: -padding, dy: -padding).intersection(canvas)
                if mode == .precise {
                    NSBezierPath(roundedRect: erased, xRadius: min(8, erased.height * 0.16),
                                 yRadius: min(8, erased.height * 0.16)).fill()
                } else { erased.fill() }
            }
            draw(region.translatedText, in: drawingBounds, background: color)
        }
        NSGraphicsContext.restoreGraphicsState()

        let output = NSImage(size: CGSize(width: width, height: height))
        output.addRepresentation(bitmap)
        return output
    }

    static func pngData(_ image: NSImage) -> Data? {
        guard let cgImage = image.cgImageForProcessing else { return nil }
        return NSBitmapImageRep(cgImage: cgImage).representation(using: .png, properties: [:])
    }

    static func renderOverlay(
        image: NSImage,
        regions: [TranslatedRegion],
        mode: MaskMode
    ) -> NSImage {
        let changedRegions = regions.filter { $0.sourceText != $0.translatedText }
        guard let source = image.cgImageForProcessing else { return NSImage(size: image.size) }
        let width = source.width, height = source.height
        guard let bitmap = NSBitmapImageRep(
            bitmapDataPlanes: nil,
            pixelsWide: width,
            pixelsHigh: height,
            bitsPerSample: 8,
            samplesPerPixel: 4,
            hasAlpha: true,
            isPlanar: false,
            colorSpaceName: .deviceRGB,
            bytesPerRow: 0,
            bitsPerPixel: 0
        ), let context = NSGraphicsContext(bitmapImageRep: bitmap) else {
            return NSImage(size: image.size)
        }

        NSGraphicsContext.saveGraphicsState()
        NSGraphicsContext.current = context
        NSColor.clear.setFill()
        CGRect(x: 0, y: 0, width: width, height: height).fill()
        let canvas = CGRect(x: 0, y: 0, width: width, height: height)
        for region in changedRegions {
            let sourceBounds = region.bounds.intersection(canvas)
            guard !sourceBounds.isNull, sourceBounds.width > 1, sourceBounds.height > 1 else { continue }
            let drawingBounds = CGRect(
                x: sourceBounds.minX,
                y: CGFloat(height) - sourceBounds.maxY,
                width: sourceBounds.width,
                height: sourceBounds.height
            )
            let color = backgroundColor(source: source, around: sourceBounds)
            color.setFill()
            let padding = mode == .precise ? max(2, drawingBounds.height * 0.08) : 4
            let erased = drawingBounds.insetBy(dx: -padding, dy: -padding).intersection(canvas)
            if mode == .precise {
                NSBezierPath(
                    roundedRect: erased,
                    xRadius: min(8, erased.height * 0.16),
                    yRadius: min(8, erased.height * 0.16)
                ).fill()
            } else {
                erased.fill()
            }
            draw(region.translatedText, in: drawingBounds, background: color)
        }
        NSGraphicsContext.restoreGraphicsState()

        let output = NSImage(size: CGSize(width: width, height: height))
        output.addRepresentation(bitmap)
        return output
    }

    private static func backgroundColor(source: CGImage, around rect: CGRect) -> NSColor {
        guard let rep = NSBitmapImageRep(cgImage: source) as NSBitmapImageRep? else { return .white }
        let points = [CGPoint(x: rect.minX, y: rect.minY), CGPoint(x: rect.midX, y: rect.minY),
                      CGPoint(x: rect.maxX - 1, y: rect.minY), CGPoint(x: rect.minX, y: rect.maxY - 1),
                      CGPoint(x: rect.midX, y: rect.maxY - 1), CGPoint(x: rect.maxX - 1, y: rect.maxY - 1)]
        let colors = points.compactMap { point -> NSColor? in
            let x = min(max(Int(point.x), 0), source.width - 1)
            let topY = min(max(Int(point.y), 0), source.height - 1)
            return rep.colorAt(x: x, y: source.height - 1 - topY)?.usingColorSpace(.deviceRGB)
        }
        guard !colors.isEmpty else { return .white }
        let count = CGFloat(colors.count)
        return NSColor(deviceRed: colors.reduce(0) { $0 + $1.redComponent } / count,
                       green: colors.reduce(0) { $0 + $1.greenComponent } / count,
                       blue: colors.reduce(0) { $0 + $1.blueComponent } / count, alpha: 1)
    }

    private static func draw(_ text: String, in rect: CGRect, background: NSColor) {
        let foreground: NSColor = background.brightnessComponent < 0.48 ? .white : .black
        let paragraph = NSMutableParagraphStyle()
        paragraph.alignment = rect.height < 80 ? .center : .left
        paragraph.lineBreakMode = .byWordWrapping
        let inset = max(1, rect.height * 0.06)
        let target = rect.insetBy(dx: inset, dy: 0)
        var low: CGFloat = 7, high = max(9, rect.height * 0.86)
        for _ in 0..<9 {
            let size = (low + high) / 2
            let attributes: [NSAttributedString.Key: Any] = [.font: NSFont.systemFont(ofSize: size, weight: .semibold),
                .foregroundColor: foreground, .paragraphStyle: paragraph]
            let measured = (text as NSString).boundingRect(with: CGSize(width: target.width, height: .greatestFiniteMagnitude),
                options: [.usesLineFragmentOrigin, .usesFontLeading], attributes: attributes)
            if measured.height <= target.height { low = size } else { high = size }
        }
        let attributes: [NSAttributedString.Key: Any] = [.font: NSFont.systemFont(ofSize: low, weight: .semibold),
            .foregroundColor: foreground, .paragraphStyle: paragraph]
        let measured = (text as NSString).boundingRect(with: target.size,
            options: [.usesLineFragmentOrigin, .usesFontLeading], attributes: attributes)
        (text as NSString).draw(with: CGRect(x: target.minX, y: target.midY - measured.height / 2,
            width: target.width, height: min(measured.height, target.height)),
            options: [.usesLineFragmentOrigin, .usesFontLeading], attributes: attributes)
    }
}
