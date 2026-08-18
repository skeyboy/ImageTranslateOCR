import AppKit
import SwiftUI

@main
struct ImageTranslateOCRMacApp: App {
    @StateObject private var model = ImageTranslateViewModel()

    var body: some Scene {
        WindowGroup("图片翻译", id: "main") { ContentView(model: model) }
            .defaultSize(width: 1120, height: 760)

        MenuBarExtra("屏幕翻译", systemImage: model.isLiveScreenTranslationEnabled
            ? "character.book.closed.fill"
            : "character.book.closed") {
            ScreenTranslationMenu(model: model)
        }
        .menuBarExtraStyle(.menu)
    }
}

private struct ScreenTranslationMenu: View {
    @Environment(\.openWindow) private var openWindow
    @ObservedObject var model: ImageTranslateViewModel

    var body: some View {
        Text(model.status)
        if let selected = model.captureSources.first(where: { $0.id == model.selectedCaptureSourceID }) {
            Text(selected.displayTitle)
        }
        Divider()
        if model.isLiveScreenTranslationEnabled {
            Button("停止屏幕翻译", systemImage: "stop.fill") {
                model.stopLiveScreenTranslation()
            }
        } else {
            Button("开始屏幕翻译", systemImage: "play.fill") {
                model.startLiveScreenTranslation()
            }
            .disabled(model.selectedCaptureSourceID == nil)
        }
        Button("刷新采集目标", systemImage: "arrow.clockwise") {
            Task { await model.requestCaptureAccessAndRefresh() }
        }
        .disabled(model.isLiveScreenTranslationEnabled || model.isRefreshingCaptureSources)
        Divider()
        Button("打开主窗口", systemImage: "macwindow") {
            openWindow(id: "main")
            NSApp.unhide(nil)
            NSApp.activate(ignoringOtherApps: true)
        }
        Button("退出", systemImage: "power") {
            NSApp.terminate(nil)
        }
    }
}
