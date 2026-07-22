import PhotosUI
import SwiftUI
import Translation

struct ContentView: View {
    @StateObject private var model = ImageTranslateViewModel()
    @State private var selectedItem: PhotosPickerItem?
    @State private var translationConfiguration: TranslationSession.Configuration?

    var body: some View {
        NavigationStack {
            VStack(spacing: 12) {
                HStack(spacing: 8) {
                    if model.isProcessing { ProgressView().controlSize(.small) }
                    Text(model.status).font(.footnote).foregroundStyle(.secondary).lineLimit(2)
                    Spacer()
                    PhotosPicker(selection: $selectedItem, matching: .images) {
                        Label("选择图片", systemImage: "photo.on.rectangle")
                    }
                }
                ScrollView {
                    VStack(spacing: 12) {
                        imagePanel(title: "原图", image: model.originalImage)
                        Divider()
                        resultPanel
                    }
                }
                controls
            }
            .padding()
            .navigationTitle("图片翻译")
            .navigationBarTitleDisplayMode(.inline)
        }
        .onChange(of: selectedItem) { _, item in Task { await load(item) } }
        .translationTask(translationConfiguration) { session in await model.process(using: session) }
        .alert("错误", isPresented: Binding(get: { model.errorMessage != nil }, set: { if !$0 { model.errorMessage = nil } })) {
            Button("确定", role: .cancel) { model.errorMessage = nil }
        } message: { Text(model.errorMessage ?? "未知错误") }
    }

    private func imagePanel(title: String, image: UIImage?) -> some View {
        VStack(alignment: .leading, spacing: 6) {
            Text(title).font(.caption).foregroundStyle(.secondary)
            Group {
                if let image { Image(uiImage: image).resizable().scaledToFit() }
                else { ContentUnavailableView("等待图片", systemImage: "photo") }
            }
            .frame(maxWidth: .infinity, minHeight: 170, maxHeight: 330)
            .background(Color(uiColor: .secondarySystemBackground))
            .clipShape(RoundedRectangle(cornerRadius: 6))
        }
    }

    private var resultPanel: some View {
        VStack(alignment: .leading, spacing: 6) {
            Text("翻译结果").font(.caption).foregroundStyle(.secondary)
            GeometryReader { proxy in
                if let image = model.displayedResult {
                    let frame = fittedFrame(image: image.size, in: proxy.size)
                    ZStack(alignment: .topLeading) {
                        Image(uiImage: image).resizable().scaledToFit().frame(width: frame.width, height: frame.height)
                        ForEach(Array(model.regions.enumerated()), id: \.element.id) { index, region in
                            let sx = frame.width / image.size.width, sy = frame.height / image.size.height
                            let box = CGRect(x: region.bounds.minX * sx, y: region.bounds.minY * sy,
                                             width: region.bounds.width * sx, height: region.bounds.height * sy)
                            Button { model.toggle(region.id) } label: {
                                Color.clear.contentShape(Rectangle())
                                    .overlay(alignment: .topLeading) {
                                        if model.showMarkers {
                                            Text("\(index + 1)").font(.caption2.bold()).foregroundStyle(.white)
                                                .frame(width: 18, height: 18).background(model.showingOriginal.contains(region.id) ? .green : .red)
                                                .clipShape(Circle()).offset(x: -20)
                                        }
                                    }
                            }
                            .buttonStyle(.plain).frame(width: box.width, height: box.height)
                            .position(x: box.midX, y: box.midY)
                            .accessibilityLabel("切换第 \(index + 1) 段原文与译文")
                        }
                    }.frame(width: frame.width, height: frame.height)
                } else { ContentUnavailableView("等待处理", systemImage: "character.book.closed") }
            }
            .frame(minHeight: 220, maxHeight: 380)
            .frame(maxWidth: .infinity)
            .background(Color(uiColor: .secondarySystemBackground))
            .clipShape(RoundedRectangle(cornerRadius: 6))
        }
    }

    private var controls: some View {
        VStack(spacing: 10) {
            HStack {
                Picker("遮罩", selection: $model.maskMode) {
                    ForEach(MaskMode.allCases) { Text($0.title).tag($0) }
                }.pickerStyle(.segmented)
                Toggle("标号", isOn: $model.showMarkers).fixedSize()
            }
            HStack {
                Button { startTranslation() } label: { Label("翻译", systemImage: "translate").frame(maxWidth: .infinity) }
                    .buttonStyle(.borderedProminent).disabled(model.originalImage == nil || model.isProcessing)
                Button { Task { await model.save() } } label: { Label("保存", systemImage: "square.and.arrow.down").frame(maxWidth: .infinity) }
                    .buttonStyle(.bordered).disabled(model.resultImage == nil || model.isProcessing)
            }
        }
    }

    private func startTranslation() {
        if translationConfiguration == nil {
            translationConfiguration = .init(source: Locale.Language(identifier: "zh-Hans"), target: Locale.Language(identifier: "en"))
        } else { translationConfiguration?.invalidate() }
    }

    private func load(_ item: PhotosPickerItem?) async {
        guard let data = try? await item?.loadTransferable(type: Data.self), let image = UIImage(data: data) else { return }
        await MainActor.run { model.setImage(image) }
    }

    private func fittedFrame(image: CGSize, in available: CGSize) -> CGSize {
        let scale = min(available.width / image.width, available.height / image.height)
        return CGSize(width: image.width * scale, height: image.height * scale)
    }
}
