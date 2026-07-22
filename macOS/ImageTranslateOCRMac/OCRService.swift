import AppKit
import Vision

struct OCRService: Sendable {
    func recognize(_ image: NSImage) async throws -> [RecognizedRegion] {
        guard let cgImage = image.cgImageForProcessing else { throw OCRFailure.invalidImage }
        let imageSize = CGSize(width: cgImage.width, height: cgImage.height)

        return try await withCheckedThrowingContinuation { continuation in
            let request = VNRecognizeTextRequest { request, error in
                if let error { continuation.resume(throwing: error); return }
                let regions = (request.results as? [VNRecognizedTextObservation] ?? []).compactMap {
                    observation -> RecognizedRegion? in
                    guard let candidate = observation.topCandidates(1).first else { return nil }
                    let text = candidate.string.trimmingCharacters(in: .whitespacesAndNewlines)
                    guard Self.isUseful(text) else { return nil }
                    let box = observation.boundingBox
                    return RecognizedRegion(sourceText: text, bounds: CGRect(
                        x: box.minX * imageSize.width,
                        y: (1 - box.maxY) * imageSize.height,
                        width: box.width * imageSize.width,
                        height: box.height * imageSize.height
                    ).integral)
                }.sorted {
                    $0.bounds.minY == $1.bounds.minY
                        ? $0.bounds.minX < $1.bounds.minX
                        : $0.bounds.minY < $1.bounds.minY
                }
                continuation.resume(returning: regions)
            }
            request.recognitionLevel = .accurate
            request.recognitionLanguages = ["zh-Hans", "zh-Hant", "en-US"]
            request.usesLanguageCorrection = true
            request.minimumTextHeight = 0.008
            do { try VNImageRequestHandler(cgImage: cgImage).perform([request]) }
            catch { continuation.resume(throwing: error) }
        }
    }

    private static func isUseful(_ text: String) -> Bool {
        let visible = text.filter { !$0.isWhitespace }
        guard !visible.isEmpty else { return false }
        return Double(visible.filter { $0.isLetter || $0.isNumber }.count) / Double(visible.count) >= 0.5
    }

    enum OCRFailure: LocalizedError {
        case invalidImage
        var errorDescription: String? { "无法读取图片" }
    }
}

extension NSImage {
    var cgImageForProcessing: CGImage? {
        var rect = CGRect(origin: .zero, size: size)
        return cgImage(forProposedRect: &rect, context: nil, hints: nil)
    }
}
