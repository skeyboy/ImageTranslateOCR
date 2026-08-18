import AppKit
import CoreGraphics
import ScreenCaptureKit
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
    @Published var translationDirection: TranslationDirection = .englishToChinese
    @Published var status = "就绪，请选择或拖入图片"
    @Published var errorMessage: String?
    @Published var captureSources: [ScreenCaptureSource] = []
    @Published var selectedCaptureSourceID: String?
    @Published var isRefreshingCaptureSources = false
    @Published var isLiveScreenTranslationEnabled = false
    @Published private(set) var isCaptureTargetFrontmost = false
    @Published private(set) var translationRequestVersion = 0

    private let ocr = OCRService()
    private let screenCapture = ScreenCaptureService()
    private let screenOverlay = ScreenTranslationOverlayController()
    private var cancellationRequested = false
    private var hasProcessed = false
    private var pendingRequest: ProcessingRequest?
    private var liveGeneration = 0
    private var captureTask: Task<Void, Never>?
    private var settleTask: Task<Void, Never>?
    private var motionMonitorTask: Task<Void, Never>?
    private var targetMonitorTask: Task<Void, Never>?
    private var globalScrollMonitor: Any?
    private var localScrollMonitor: Any?
    private var previousScreenSignature: [UInt8]?
    private var lastTargetState: ScreenCaptureTargetState?

    func load(url: URL) {
        guard let image = NSImage(contentsOf: url) else { errorMessage = "无法打开该图片"; return }
        originalImage = image
        resultImage = nil
        regions = []
        showingOriginal = []
        hasProcessed = false
        status = "已加载：\(url.lastPathComponent)"
    }

    func requestLoadedImageTranslation() {
        guard let originalImage else { return }
        pendingRequest = .document(originalImage)
        translationRequestVersion &+= 1
    }

    func process(using session: TranslationSession) async {
        guard let request = pendingRequest else { return }
        let requestVersion = translationRequestVersion
        let image: NSImage
        let screenContext: (frame: CapturedScreenFrame, generation: Int)?
        switch request {
        case .document(let documentImage):
            image = documentImage
            screenContext = nil
        case .screen(let frame, let generation):
            image = NSImage(
                cgImage: frame.image,
                size: CGSize(width: frame.image.width, height: frame.image.height)
            )
            screenContext = (frame, generation)
        }
        cancellationRequested = false
        isProcessing = true
        defer {
            if requestVersion == translationRequestVersion { isProcessing = false }
        }
        do {
            status = "识别中..."
            let found = try await ocr.recognize(image)
            try checkCancellation(screenGeneration: screenContext?.generation)
            guard !found.isEmpty else { status = "未识别到文字"; return }
            var translated: [TranslatedRegion] = []
            for (index, item) in found.enumerated() {
                try checkCancellation(screenGeneration: screenContext?.generation)
                status = "翻译 \(index + 1)/\(found.count)..."
                let target = Self.shouldTranslate(item.sourceText, direction: translationDirection)
                    ? try await session.translate(item.sourceText).targetText.trimmingCharacters(in: .whitespacesAndNewlines)
                    : item.sourceText
                try checkCancellation(screenGeneration: screenContext?.generation)
                translated.append(TranslatedRegion(id: item.id, sourceText: item.sourceText,
                    translatedText: target.isEmpty ? item.sourceText : target, bounds: item.bounds))
            }
            let changed = translated.filter { $0.sourceText != $0.translatedText }
            if let screenContext {
                try checkCancellation(screenGeneration: screenContext.generation)
                let overlay = ImageRenderer.renderOverlay(image: image, regions: changed, mode: maskMode)
                try checkCancellation(screenGeneration: screenContext.generation)
                guard let source = selectedCaptureSource,
                      screenCapture.targetState(for: source)?.isFrontmost == true else {
                    throw CancellationError()
                }
                screenOverlay.present(image: overlay, frame: screenContext.frame)
                status = "屏幕翻译完成，共回贴 \(changed.count) 段文字"
            } else {
                regions = changed
                hasProcessed = true
                renderCurrentResult()
                status = "完成，共替换 \(regions.count) 段文字"
            }
        } catch is CancellationError {
            if !isLiveScreenTranslationEnabled { status = "已停止处理" }
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

    func refreshCaptureSources() async {
        guard !isRefreshingCaptureSources else { return }
        isRefreshingCaptureSources = true
        defer { isRefreshingCaptureSources = false }
        do {
            let sources = try await screenCapture.availableSources()
            captureSources = sources
            if !sources.contains(where: { $0.id == selectedCaptureSourceID }) {
                selectedCaptureSourceID = sources.first?.id
            }
            status = sources.isEmpty ? "未发现可采集的显示器或窗口" : "已发现 \(sources.count) 个采集目标"
        } catch {
            captureSources = []
            selectedCaptureSourceID = nil
            if Self.isCapturePermissionError(error) || !CGPreflightScreenCaptureAccess() {
                status = "请在系统设置中授予屏幕录制权限后重新打开应用"
            } else {
                errorMessage = "无法读取屏幕采集目标：\(error.localizedDescription)"
                status = "读取屏幕采集目标失败"
            }
        }
    }

    func loadCaptureSourcesIfAuthorized() async {
        if CGPreflightScreenCaptureAccess() {
            await refreshCaptureSources()
        } else {
            status = "屏幕翻译需要屏幕录制权限，点击刷新按钮授权"
        }
    }

    func requestCaptureAccessAndRefresh() async {
        if CGPreflightScreenCaptureAccess() {
            await refreshCaptureSources()
            return
        }
        guard CGRequestScreenCaptureAccess() else {
            status = "请在系统设置中授予屏幕录制权限后重新打开应用"
            return
        }
        status = "屏幕录制权限已授予，请重新打开应用后刷新目标"
    }

    func startLiveScreenTranslation() {
        guard selectedCaptureSource != nil else {
            errorMessage = "请先选择屏幕或窗口"
            return
        }
        guard !isLiveScreenTranslationEnabled else { return }
        isLiveScreenTranslationEnabled = true
        liveGeneration &+= 1
        isCaptureTargetFrontmost = false
        lastTargetState = nil
        previousScreenSignature = nil
        installScrollMonitors()
        startTargetMonitor()
        startMotionFallback()
        status = "等待所选窗口切换到最前..."
        Task { @MainActor [weak self] in
            try? await Task.sleep(for: .milliseconds(120))
            guard self?.isLiveScreenTranslationEnabled == true else { return }
            NSApp.hide(nil)
        }
    }

    func stopLiveScreenTranslation() {
        guard isLiveScreenTranslationEnabled else { return }
        isLiveScreenTranslationEnabled = false
        liveGeneration &+= 1
        captureTask?.cancel()
        settleTask?.cancel()
        motionMonitorTask?.cancel()
        targetMonitorTask?.cancel()
        captureTask = nil
        settleTask = nil
        motionMonitorTask = nil
        targetMonitorTask = nil
        isCaptureTargetFrontmost = false
        lastTargetState = nil
        removeScrollMonitors()
        screenOverlay.hide(clearImage: true)
        isProcessing = false
        status = "已停止屏幕翻译"
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

    private func checkCancellation(screenGeneration: Int? = nil) throws {
        if cancellationRequested || Task.isCancelled { throw CancellationError() }
        if let screenGeneration,
           (!isLiveScreenTranslationEnabled || !isCaptureTargetFrontmost ||
            screenGeneration != liveGeneration) {
            throw CancellationError()
        }
    }

    private var selectedCaptureSource: ScreenCaptureSource? {
        captureSources.first { $0.id == selectedCaptureSourceID }
    }

    private func requestLiveCapture(generation: Int) {
        guard isLiveScreenTranslationEnabled, isCaptureTargetFrontmost,
              let source = selectedCaptureSource else { return }
        captureTask?.cancel()
        captureTask = Task { [weak self] in
            guard let self else { return }
            do {
                let frame = try await screenCapture.capture(source)
                try Task.checkCancellation()
                guard isLiveScreenTranslationEnabled, generation == liveGeneration else { return }
                pendingRequest = .screen(frame, generation: generation)
                translationRequestVersion &+= 1
            } catch is CancellationError {
                return
            } catch ScreenCaptureService.CaptureFailure.sourceNotFrontmost {
                handleTargetNotFrontmost()
            } catch {
                guard generation == liveGeneration else { return }
                errorMessage = "屏幕采集失败：\(error.localizedDescription)"
                status = "屏幕采集失败"
            }
        }
    }

    private func handleViewportMotion(statusText: String = "滚动中，等待停稳...") {
        guard isLiveScreenTranslationEnabled, isCaptureTargetFrontmost else { return }
        liveGeneration &+= 1
        let generation = liveGeneration
        screenOverlay.hide(clearImage: true)
        status = statusText
        settleTask?.cancel()
        settleTask = Task { [weak self] in
            do { try await Task.sleep(for: ScreenFrameChangePolicy.settleDelay) }
            catch { return }
            guard let self, isLiveScreenTranslationEnabled, isCaptureTargetFrontmost,
                  generation == liveGeneration else { return }
            status = "滚动已停止，正在采集..."
            requestLiveCapture(generation: generation)
        }
    }

    private func installScrollMonitors() {
        globalScrollMonitor = NSEvent.addGlobalMonitorForEvents(matching: .scrollWheel) { [weak self] _ in
            Task { @MainActor in self?.handleViewportMotion() }
        }
        localScrollMonitor = NSEvent.addLocalMonitorForEvents(matching: .scrollWheel) { [weak self] event in
            Task { @MainActor in self?.handleViewportMotion() }
            return event
        }
    }

    private func removeScrollMonitors() {
        if let globalScrollMonitor { NSEvent.removeMonitor(globalScrollMonitor) }
        if let localScrollMonitor { NSEvent.removeMonitor(localScrollMonitor) }
        globalScrollMonitor = nil
        localScrollMonitor = nil
    }

    private func startMotionFallback() {
        motionMonitorTask?.cancel()
        motionMonitorTask = Task { [weak self] in
            guard let self else { return }
            while !Task.isCancelled && isLiveScreenTranslationEnabled {
                do { try await Task.sleep(for: ScreenFrameChangePolicy.sampleInterval) }
                catch { return }
                guard isCaptureTargetFrontmost, let source = selectedCaptureSource else { continue }
                do {
                    let frame = try await screenCapture.capture(source, maximumDimension: 64)
                    let signature = Self.signature(for: frame.image)
                    if let previousScreenSignature,
                       ScreenFrameChangePolicy.hasMovement(from: previousScreenSignature, to: signature) {
                        handleViewportMotion()
                    }
                    previousScreenSignature = signature
                } catch {
                    // The main capture path reports actionable errors. This sampler is only a fallback.
                }
            }
        }
    }

    private func startTargetMonitor() {
        targetMonitorTask?.cancel()
        targetMonitorTask = Task { [weak self] in
            guard let self else { return }
            while !Task.isCancelled && isLiveScreenTranslationEnabled {
                guard let source = selectedCaptureSource else { return }
                handleTargetState(screenCapture.targetState(for: source))
                do { try await Task.sleep(for: .milliseconds(180)) }
                catch { return }
            }
        }
    }

    private func handleTargetState(_ state: ScreenCaptureTargetState?) {
        guard isLiveScreenTranslationEnabled else { return }
        guard let state, state.isFrontmost else {
            handleTargetNotFrontmost()
            return
        }

        if !isCaptureTargetFrontmost {
            isCaptureTargetFrontmost = true
            lastTargetState = state
            previousScreenSignature = nil
            liveGeneration &+= 1
            status = "目标窗口已置顶，正在采集..."
            requestLiveCapture(generation: liveGeneration)
            return
        }

        if let previous = lastTargetState,
           previous.frame.integral != state.frame.integral || previous.displayID != state.displayID {
            lastTargetState = state
            handleViewportMotion(statusText: "窗口位置或大小已变化，等待停稳...")
        } else {
            lastTargetState = state
            screenOverlay.updateTargetFrame(state)
        }
    }

    private func handleTargetNotFrontmost() {
        guard isLiveScreenTranslationEnabled else { return }
        if isCaptureTargetFrontmost {
            liveGeneration &+= 1
            captureTask?.cancel()
            settleTask?.cancel()
        }
        isCaptureTargetFrontmost = false
        lastTargetState = nil
        previousScreenSignature = nil
        screenOverlay.hide(clearImage: true)
        isProcessing = false
        status = "等待所选窗口切换到最前..."
    }

    private static func signature(for image: CGImage) -> [UInt8] {
        let width = 32, height = 24
        var pixels = [UInt8](repeating: 0, count: width * height)
        pixels.withUnsafeMutableBytes { bytes in
            guard let context = CGContext(
                data: bytes.baseAddress,
                width: width,
                height: height,
                bitsPerComponent: 8,
                bytesPerRow: width,
                space: CGColorSpaceCreateDeviceGray(),
                bitmapInfo: CGImageAlphaInfo.none.rawValue
            ) else { return }
            context.interpolationQuality = .low
            context.draw(image, in: CGRect(x: 0, y: 0, width: width, height: height))
        }
        return pixels
    }

    private static func shouldTranslate(_ text: String, direction: TranslationDirection) -> Bool {
        let containsSourceLanguage: Bool
        switch direction {
        case .englishToChinese:
            containsSourceLanguage = text.unicodeScalars.contains {
                (0x0041...0x005A).contains(Int($0.value)) ||
                    (0x0061...0x007A).contains(Int($0.value))
            }
        case .chineseToEnglish:
            containsSourceLanguage = text.unicodeScalars.contains {
                (0x3400...0x9FFF).contains(Int($0.value))
            }
        }
        guard containsSourceLanguage else { return false }
        let digits = text.filter(\.isNumber).count
        return !(digits >= 4 && Double(digits) / Double(max(text.count, 1)) >= 0.65)
    }

    private static func isCapturePermissionError(_ error: Error) -> Bool {
        let error = error as NSError
        return error.domain == SCStreamErrorDomain && error.code == SCStreamError.Code.userDeclined.rawValue
    }

    private enum ProcessingRequest {
        case document(NSImage)
        case screen(CapturedScreenFrame, generation: Int)
    }
}
