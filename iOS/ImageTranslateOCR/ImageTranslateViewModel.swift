import Photos
import SwiftUI
import Translation

@MainActor
final class ImageTranslateViewModel: ObservableObject {
    @Published var originalImage: UIImage?
    @Published var resultImage: UIImage?
    @Published var regions: [TranslatedRegion] = []
    @Published var showingOriginal = Set<UUID>()
    @Published var isProcessing = false
    @Published var showMarkers = true
    @Published var maskMode: MaskMode = .precise
    @Published var status = "就绪，请选择图片"
    @Published var errorMessage: String?

    private let ocr = OCRService()

    func setImage(_ image: UIImage) {
        originalImage = image
        resultImage = nil
        regions = []
        showingOriginal = []
        status = "图片已加载"
    }

    func process(using session: TranslationSession) async {
        guard let image = originalImage, !isProcessing else { return }
        isProcessing = true
        defer { isProcessing = false }
        do {
            status = "识别中..."
            let found = try await ocr.recognize(image)
            guard !found.isEmpty else { status = "未识别到文字"; return }
            status = "翻译 \(found.count) 段文字..."
            var translated: [TranslatedRegion] = []
            for (index, item) in found.enumerated() {
                status = "翻译 \(index + 1)/\(found.count)..."
                let target: String
                if Self.shouldTranslate(item.sourceText) {
                    target = try await session.translate(item.sourceText).targetText.trimmingCharacters(in: .whitespacesAndNewlines)
                } else { target = item.sourceText }
                translated.append(TranslatedRegion(id: item.id, sourceText: item.sourceText,
                                                    translatedText: target.isEmpty ? item.sourceText : target,
                                                    bounds: item.bounds))
            }
            status = "写入翻译..."
            regions = translated.filter { $0.sourceText != $0.translatedText }
            resultImage = ImageRenderer.render(image: image, regions: regions, mode: maskMode)
            status = "完成，共替换 \(regions.count) 段文字；点击译文可切换原文"
        } catch {
            errorMessage = error.localizedDescription
            status = "失败：\(error.localizedDescription)"
        }
    }

    func toggle(_ id: UUID) {
        if showingOriginal.contains(id) { showingOriginal.remove(id) }
        else { showingOriginal.insert(id) }
    }

    func save() async {
        guard let image = displayedResult else { return }
        let authorization = await PHPhotoLibrary.requestAuthorization(for: .addOnly)
        guard authorization == .authorized || authorization == .limited else {
            errorMessage = "没有相册写入权限"; return
        }
        do {
            try await PHPhotoLibrary.shared().performChanges {
                PHAssetChangeRequest.creationRequestForAsset(from: image)
            }
            status = "已保存到相册"
        } catch { errorMessage = error.localizedDescription }
    }

    var displayedResult: UIImage? {
        guard let originalImage else { return resultImage }
        let visibleRegions = regions.filter { !showingOriginal.contains($0.id) }
        return ImageRenderer.render(image: originalImage, regions: visibleRegions, mode: maskMode)
    }

    private static func shouldTranslate(_ text: String) -> Bool {
        let scalars = text.unicodeScalars
        let hasHan = scalars.contains { (0x3400...0x9FFF).contains(Int($0.value)) }
        guard hasHan else { return false }
        let digits = text.filter(\.isNumber).count
        return !(digits >= 4 && Double(digits) / Double(max(text.count, 1)) >= 0.65)
    }
}
