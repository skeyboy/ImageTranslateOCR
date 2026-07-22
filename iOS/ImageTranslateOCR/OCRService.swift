import MLKitTextRecognitionCommon
import MLKitTextRecognitionChinese
import MLKitVision
import UIKit

final class OCRService {
    private let recognizer = TextRecognizer.textRecognizer(options: ChineseTextRecognizerOptions())

    func recognize(_ image: UIImage) async throws -> [RecognizedRegion] {
        let visionImage = VisionImage(image: image)
        visionImage.orientation = image.imageOrientation

        return try await withCheckedThrowingContinuation { continuation in
            recognizer.process(visionImage) { result, error in
                if let error {
                    continuation.resume(throwing: error)
                    return
                }
                guard let result else {
                    continuation.resume(throwing: OCRFailure.emptyResult)
                    return
                }

                let imageBounds = CGRect(origin: .zero, size: image.size)
                let regions = result.blocks
                    .flatMap(\.lines)
                    .compactMap { line -> RecognizedRegion? in
                        let text = line.text.trimmingCharacters(in: .whitespacesAndNewlines)
                        guard Self.isUseful(text) else { return nil }
                        let bounds = line.frame.intersection(imageBounds).integral
                        guard !bounds.isNull, bounds.width > 1, bounds.height > 1 else { return nil }
                        return RecognizedRegion(sourceText: text, bounds: bounds)
                    }
                    .sorted {
                        $0.bounds.minY == $1.bounds.minY
                            ? $0.bounds.minX < $1.bounds.minX
                            : $0.bounds.minY < $1.bounds.minY
                    }
                continuation.resume(returning: regions)
            }
        }
    }

    private static func isUseful(_ text: String) -> Bool {
        let visible = text.filter { !$0.isWhitespace }
        guard !visible.isEmpty else { return false }
        let meaningful = visible.filter { $0.isLetter || $0.isNumber }.count
        return Double(meaningful) / Double(visible.count) >= 0.5
    }

    enum OCRFailure: LocalizedError {
        case emptyResult
        var errorDescription: String? { "ML Kit 未返回识别结果" }
    }
}
