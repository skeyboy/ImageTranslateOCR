import AppKit
import SwiftUI
import Translation

@MainActor
final class ImageTranslateViewModel: ObservableObject {
    @Published var originalImage: NSImage?
    @Published var resultImage: NSImage?
    @Published var regions: [TranslatedRegion] = []
    @Published var showingOriginal = Set<UUID>()
    @Published var isProcessing = false
    @Published var showMarkers = true
    @Published var maskMode: MaskMode = .precise
    @Published var eraseEngine: EraseEngine = .system
    @Published var status = "就绪，请选择或拖入图片"
    @Published var errorMessage: String?

    private let ocr = OCRService()
    private var cancellationRequested = false
    private var hasProcessed = false

    func load(url: URL) {
        guard let image = NSImage(contentsOf: url) else { errorMessage = "无法打开该图片"; return }
        originalImage = image
        resultImage = nil
        regions = []
        showingOriginal = []
        hasProcessed = false
        status = "已加载：\(url.lastPathComponent)"
    }

    func process(using session: TranslationSession) async {
        guard let image = originalImage, !isProcessing else { return }
        cancellationRequested = false
        isProcessing = true
        defer { isProcessing = false }
        do {
            status = "识别中..."
            let found = try await ocr.recognize(image)
            try checkCancellation()
            guard !found.isEmpty else { status = "未识别到文字"; return }
            var translated: [TranslatedRegion] = []
            for (index, item) in found.enumerated() {
                try checkCancellation()
                status = "翻译 \(index + 1)/\(found.count)..."
                let target = Self.shouldTranslate(item.sourceText)
                    ? try await session.translate(item.sourceText).targetText.trimmingCharacters(in: .whitespacesAndNewlines)
                    : item.sourceText
                try checkCancellation()
                translated.append(TranslatedRegion(id: item.id, sourceText: item.sourceText,
                    translatedText: target.isEmpty ? item.sourceText : target, bounds: item.bounds))
            }
            regions = translated.filter { $0.sourceText != $0.translatedText }
            hasProcessed = true
            renderCurrentResult()
            status = "完成，共替换 \(regions.count) 段文字"
        } catch is CancellationError {
            status = "已停止处理"
        } catch {
            errorMessage = error.localizedDescription
            status = "失败：\(error.localizedDescription)"
        }
    }

    var displayedResult: NSImage? {
        resultImage
    }

    var isOpenCVAvailable: Bool { OpenCVWrapper.isAvailable() }

    func toggle(_ id: UUID) {
        if showingOriginal.contains(id) { showingOriginal.remove(id) } else { showingOriginal.insert(id) }
        renderCurrentResult()
    }

    func setMaskMode(_ mode: MaskMode) {
        guard maskMode != mode else { return }
        maskMode = mode
        renderCurrentResult()
    }

    func setEraseEngine(_ engine: EraseEngine) {
        guard eraseEngine != engine else { return }
        eraseEngine = engine
        renderCurrentResult()
    }

    func cancelProcessing() {
        guard isProcessing else { return }
        cancellationRequested = true
        status = "正在停止..."
    }

    func save() {
        guard let image = resultImage, let data = ImageRenderer.pngData(image) else { return }
        let panel = NSSavePanel()
        panel.allowedContentTypes = [.png]
        panel.nameFieldStringValue = "translated.png"
        guard panel.runModal() == .OK, let url = panel.url else { return }
        do { try data.write(to: url, options: .atomic); status = "已保存：\(url.lastPathComponent)" }
        catch { errorMessage = error.localizedDescription }
    }

    private func renderCurrentResult() {
        guard hasProcessed, let originalImage else { return }
        guard !regions.isEmpty else {
            resultImage = originalImage
            return
        }
        resultImage = ImageRenderer.render(
            image: originalImage,
            regions: regions.filter { !showingOriginal.contains($0.id) },
            mode: maskMode,
            engine: eraseEngine
        )
    }

    private func checkCancellation() throws {
        if cancellationRequested || Task.isCancelled { throw CancellationError() }
    }

    private static func shouldTranslate(_ text: String) -> Bool {
        let hasHan = text.unicodeScalars.contains { (0x3400...0x9FFF).contains(Int($0.value)) }
        guard hasHan else { return false }
        let digits = text.filter(\.isNumber).count
        return !(digits >= 4 && Double(digits) / Double(max(text.count, 1)) >= 0.65)
    }
}
