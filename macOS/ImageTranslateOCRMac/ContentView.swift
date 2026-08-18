import SwiftUI
import Translation
import UniformTypeIdentifiers

struct ContentView: View {
    @Environment(\.accessibilityReduceMotion) private var reduceMotion
    @ObservedObject var model: ImageTranslateViewModel
    @State private var isImporterPresented = false
    @State private var translationConfiguration: TranslationSession.Configuration?
    @State private var configuredDirection: TranslationDirection?
    @State private var inspectedRegionID: UUID?
    @State private var isDropTargeted = false
    @State private var copiedField: String?

    var body: some View {
        VStack(spacing: 0) {
            toolbar
            Divider()
            screenCaptureBar
            Divider()
            HSplitView {
                imagePanel(title: "原图", image: model.originalImage)
                resultPanel
            }
            Divider()
            statusBar
        }
        .frame(minWidth: 820, minHeight: 580)
        .fileImporter(isPresented: $isImporterPresented, allowedContentTypes: [.image]) { result in
            if case .success(let url) = result { model.load(url: url) }
            else if case .failure(let error) = result { model.errorMessage = error.localizedDescription }
        }
        .dropDestination(
            for: URL.self,
            action: { urls, _ in
                guard let url = urls.first,
                      UTType(filenameExtension: url.pathExtension)?.conforms(to: .image) == true else { return false }
                model.load(url: url)
                return true
            },
            isTargeted: { targeted in
                if reduceMotion {
                    isDropTargeted = targeted
                } else {
                    withAnimation(.easeOut(duration: 0.15)) { isDropTargeted = targeted }
                }
            }
        )
        .overlay {
            if isDropTargeted {
                RoundedRectangle(cornerRadius: 8)
                    .stroke(Color.accentColor, lineWidth: 2)
                    .padding(4)
                    .allowsHitTesting(false)
                    .transition(.opacity)
            }
        }
        .translationTask(translationConfiguration) { session in await model.process(using: session) }
        .task { await model.loadCaptureSourcesIfAuthorized() }
        .onChange(of: model.translationRequestVersion) { _, _ in activateTranslationSession() }
        .alert("错误", isPresented: Binding(get: { model.errorMessage != nil }, set: { if !$0 { model.errorMessage = nil } })) {
            Button("确定", role: .cancel) { model.errorMessage = nil }
        } message: { Text(model.errorMessage ?? "未知错误") }
    }

    private var toolbar: some View {
        HStack(spacing: 12) {
            Button { isImporterPresented = true } label: { Label("打开图片", systemImage: "folder") }
                .keyboardShortcut("o")
                .disabled(model.isProcessing)
            Picker("引擎", selection: Binding(
                get: { model.eraseEngine },
                set: { model.setEraseEngine($0) }
            )) {
                Text(EraseEngine.system.title).tag(EraseEngine.system)
                Text(EraseEngine.openCV.title).tag(EraseEngine.openCV)
                    .disabled(!model.isOpenCVAvailable)
            }.frame(width: 175).disabled(model.isProcessing)
            Picker("遮罩", selection: Binding(
                get: { model.maskMode },
                set: { model.setMaskMode($0) }
            )) {
                ForEach(MaskMode.allCases) { Text($0.title).tag($0) }
            }.pickerStyle(.segmented).frame(width: 180).disabled(model.isProcessing)
            Toggle("显示标号", isOn: $model.showMarkers).toggleStyle(.checkbox)
            Spacer()
            if model.isProcessing && !model.isLiveScreenTranslationEnabled {
                Button { model.cancelProcessing() } label: {
                    Label("停止", systemImage: "stop.fill").frame(minWidth: 68)
                }
                .buttonStyle(.bordered)
                .help("完成当前步骤后停止")
            } else {
                Button { model.requestLoadedImageTranslation() } label: {
                    Label("翻译", systemImage: "character.book.closed").frame(minWidth: 68)
                }
                .buttonStyle(.borderedProminent)
                .disabled(model.originalImage == nil)
                .keyboardShortcut(.return, modifiers: [.command])
            }
            Button { model.save() } label: { Label("导出 PNG", systemImage: "square.and.arrow.down") }
                .disabled(model.resultImage == nil || model.isProcessing)
                .keyboardShortcut("s")
        }.padding(12)
    }

    private var screenCaptureBar: some View {
        HStack(spacing: 10) {
            Image(systemName: "rectangle.inset.filled.and.person.filled")
                .foregroundStyle(.secondary)
            Text("屏幕翻译").font(.callout.weight(.medium))
            Picker("方向", selection: $model.translationDirection) {
                ForEach(TranslationDirection.allCases) { direction in
                    Text(direction.title).tag(direction)
                }
            }
            .pickerStyle(.segmented)
            .frame(width: 130)
            .disabled(model.isLiveScreenTranslationEnabled || model.isProcessing)
            Picker("采集目标", selection: $model.selectedCaptureSourceID) {
                if model.captureSources.isEmpty {
                    Text("没有可用目标").tag(String?.none)
                }
                ForEach(model.captureSources) { source in
                    Label(
                        source.displayTitle,
                        systemImage: source.kind == .display ? "display" : "macwindow"
                    ).tag(Optional(source.id))
                }
            }
            .labelsHidden()
            .frame(minWidth: 260, idealWidth: 360, maxWidth: 460)
            .disabled(model.isLiveScreenTranslationEnabled || model.isRefreshingCaptureSources)

            Button {
                Task { await model.requestCaptureAccessAndRefresh() }
            } label: {
                Image(systemName: "arrow.clockwise")
            }
            .help("刷新采集目标")
            .disabled(model.isLiveScreenTranslationEnabled || model.isRefreshingCaptureSources)

            if model.isRefreshingCaptureSources { ProgressView().controlSize(.small) }
            Spacer()
            if model.isLiveScreenTranslationEnabled {
                Button(role: .destructive) { model.stopLiveScreenTranslation() } label: {
                    Label("停止", systemImage: "stop.fill")
                }
            } else {
                Button { model.startLiveScreenTranslation() } label: {
                    Label("开始", systemImage: "play.fill")
                }
                .buttonStyle(.borderedProminent)
                .disabled(model.selectedCaptureSourceID == nil || model.isRefreshingCaptureSources)
            }
        }
        .padding(.horizontal, 12)
        .frame(height: 48)
    }

    private func imagePanel(title: String, image: NSImage?) -> some View {
        VStack(alignment: .leading, spacing: 8) {
            Text(title).font(.headline)
            GeometryReader { proxy in
                if let image {
                    Image(nsImage: image).resizable().scaledToFit().frame(maxWidth: .infinity, maxHeight: .infinity)
                } else {
                    ContentUnavailableView("拖入图片", systemImage: "photo.badge.plus", description: Text("或点击“打开图片”"))
                        .frame(maxWidth: .infinity, maxHeight: .infinity)
                }
            }
            .background(isDropTargeted ? Color.accentColor.opacity(0.08) : Color(nsColor: .controlBackgroundColor))
            .clipShape(RoundedRectangle(cornerRadius: 6))
        }.padding(12).frame(minWidth: 380)
    }

    private var resultPanel: some View {
        VStack(alignment: .leading, spacing: 8) {
            Text("翻译结果").font(.headline)
            GeometryReader { proxy in
                if let image = model.displayedResult {
                    let size = fittedSize(image: image.size, in: proxy.size)
                    ZStack(alignment: .topLeading) {
                        Image(nsImage: image).resizable().scaledToFit().frame(width: size.width, height: size.height)
                        ForEach(Array(model.regions.enumerated()), id: \.element.id) { index, region in
                            let sx = size.width / image.size.width, sy = size.height / image.size.height
                            let box = CGRect(x: region.bounds.minX * sx, y: region.bounds.minY * sy,
                                width: region.bounds.width * sx, height: region.bounds.height * sy)
                            Button { model.toggle(region.id) } label: {
                                Color.clear.contentShape(Rectangle())
                            }.buttonStyle(.plain).frame(width: box.width, height: box.height)
                                .position(x: box.midX, y: box.midY)
                            if model.showMarkers {
                                Button { inspectedRegionID = region.id } label: {
                                    Text("\(index + 1)")
                                        .font(.caption2.bold())
                                        .foregroundStyle(.white)
                                        .frame(width: 18, height: 18)
                                        .background(model.showingOriginal.contains(region.id) ? .green : .red)
                                        .clipShape(Circle())
                                }
                                .buttonStyle(MarkerButtonStyle())
                                .position(x: max(10, box.minX - 11), y: box.midY)
                                .help("查看原文和译文")
                                .popover(isPresented: Binding(
                                    get: { inspectedRegionID == region.id },
                                    set: { if !$0 { inspectedRegionID = nil } }
                                ), arrowEdge: .trailing) {
                                    translationDetails(region: region, number: index + 1)
                                }
                            }
                        }
                    }.frame(width: size.width, height: size.height)
                        .frame(maxWidth: .infinity, maxHeight: .infinity)
                } else {
                    if model.isProcessing {
                        VStack(spacing: 10) {
                            ProgressView().controlSize(.small)
                            Text(model.status).font(.callout).foregroundStyle(.secondary)
                        }.frame(maxWidth: .infinity, maxHeight: .infinity)
                    } else {
                        ContentUnavailableView("等待处理", systemImage: "character.book.closed")
                            .frame(maxWidth: .infinity, maxHeight: .infinity)
                    }
                }
            }
            .background(Color(nsColor: .controlBackgroundColor))
            .clipShape(RoundedRectangle(cornerRadius: 6))
        }.padding(12).frame(minWidth: 380)
    }

    private var statusBar: some View {
        HStack {
            Image(systemName: statusIcon)
                .foregroundStyle(statusColor)
                .frame(width: 14)
            if model.isProcessing { ProgressView().controlSize(.mini) }
            Text(model.status).font(.footnote).foregroundStyle(.secondary)
            Spacer()
            Text("本地处理 · \(model.eraseEngine.title)").font(.caption).foregroundStyle(.tertiary)
        }.padding(.horizontal, 12).frame(height: 34)
    }

    private func translationDetails(region: TranslatedRegion, number: Int) -> some View {
        VStack(alignment: .leading, spacing: 12) {
            HStack {
                Text("文字区域 \(number)").font(.headline)
                Spacer()
                Button { inspectedRegionID = nil } label: {
                    Image(systemName: "xmark")
                }.buttonStyle(.plain).help("关闭")
            }
            VStack(alignment: .leading, spacing: 4) {
                detailHeader(title: "识别原文", text: region.sourceText, key: "\(region.id)-source")
                Text(region.sourceText).textSelection(.enabled)
            }
            Divider()
            VStack(alignment: .leading, spacing: 4) {
                detailHeader(title: "翻译结果", text: region.translatedText, key: "\(region.id)-translation")
                Text(region.translatedText).textSelection(.enabled)
            }
        }
        .padding(14)
        .frame(minWidth: 280, idealWidth: 340, maxWidth: 420)
    }

    private func detailHeader(title: String, text: String, key: String) -> some View {
        HStack {
            Text(title).font(.caption).foregroundStyle(.secondary)
            Spacer()
            Button { copy(text, key: key) } label: {
                Image(systemName: copiedField == key ? "checkmark" : "doc.on.doc")
                    .foregroundStyle(copiedField == key ? Color.green : Color.secondary)
                    .contentTransition(.symbolEffect(.replace))
            }
            .buttonStyle(.plain)
            .help(copiedField == key ? "已复制" : "复制")
        }
    }

    private var statusIcon: String {
        if model.isProcessing { return "clock" }
        if model.status.hasPrefix("完成") || model.status.hasPrefix("已保存") { return "checkmark.circle.fill" }
        if model.status.hasPrefix("已停止") { return "stop.circle" }
        if model.status.hasPrefix("失败") { return "exclamationmark.triangle.fill" }
        if model.originalImage != nil { return "photo" }
        return "circle"
    }

    private var statusColor: Color {
        if model.status.hasPrefix("完成") || model.status.hasPrefix("已保存") { return .green }
        if model.status.hasPrefix("失败") { return .red }
        return .secondary
    }

    private func copy(_ text: String, key: String) {
        NSPasteboard.general.clearContents()
        NSPasteboard.general.setString(text, forType: .string)
        updateCopiedField(key, duration: 0.16)
        Task {
            try? await Task.sleep(for: .seconds(1.2))
            guard copiedField == key else { return }
            updateCopiedField(nil, duration: 0.12)
        }
    }

    private func updateCopiedField(_ value: String?, duration: Double) {
        if reduceMotion {
            copiedField = value
        } else {
            withAnimation(.easeOut(duration: duration)) { copiedField = value }
        }
    }

    private func activateTranslationSession() {
        if translationConfiguration == nil || configuredDirection != model.translationDirection {
            configuredDirection = model.translationDirection
            translationConfiguration = .init(
                source: Locale.Language(identifier: model.translationDirection.sourceLanguageIdentifier),
                target: Locale.Language(identifier: model.translationDirection.targetLanguageIdentifier)
            )
        } else { translationConfiguration?.invalidate() }
    }

    private func fittedSize(image: CGSize, in available: CGSize) -> CGSize {
        let scale = min(available.width / image.width, available.height / image.height)
        return CGSize(width: image.width * scale, height: image.height * scale)
    }
}

private struct MarkerButtonStyle: ButtonStyle {
    @Environment(\.accessibilityReduceMotion) private var reduceMotion

    func makeBody(configuration: Configuration) -> some View {
        configuration.label
            .scaleEffect(configuration.isPressed && !reduceMotion ? 0.94 : 1)
            .animation(reduceMotion ? nil : .easeOut(duration: 0.1), value: configuration.isPressed)
    }
}
