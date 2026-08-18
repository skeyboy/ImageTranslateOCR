import CoreGraphics
import Foundation

struct RecognizedRegion: Identifiable, Sendable {
    let id = UUID()
    let sourceText: String
    let bounds: CGRect
}

struct TranslatedRegion: Identifiable, Sendable {
    let id: UUID
    let sourceText: String
    let translatedText: String
    let bounds: CGRect
}

enum ScreenCaptureSourceKind: String, Sendable {
    case display
    case window
}

struct ScreenCaptureSource: Identifiable, Hashable, Sendable {
    let id: String
    let kind: ScreenCaptureSourceKind
    let nativeID: UInt32
    let title: String
    let applicationName: String?
    let processID: Int32?

    var displayTitle: String {
        switch kind {
        case .display:
            title
        case .window:
            if let applicationName, !applicationName.isEmpty {
                "\(applicationName) - \(title)"
            } else {
                title
            }
        }
    }
}

struct CapturedScreenFrame: Sendable {
    let image: CGImage
    let source: ScreenCaptureSource
    let targetFrame: CGRect
    let displayID: UInt32
}

struct ScreenCaptureTargetState: Equatable, Sendable {
    let isFrontmost: Bool
    let frame: CGRect
    let displayID: UInt32
}

enum TranslationDirection: String, CaseIterable, Identifiable, Sendable {
    case englishToChinese
    case chineseToEnglish

    var id: Self { self }
    var title: String {
        self == .englishToChinese ? "英译中" : "中译英"
    }
    var sourceLanguageIdentifier: String {
        self == .englishToChinese ? "en" : "zh-Hans"
    }
    var targetLanguageIdentifier: String {
        self == .englishToChinese ? "zh-Hans" : "en"
    }
}

enum MaskMode: String, CaseIterable, Identifiable {
    case precise
    case rectangle

    var id: Self { self }
    var title: String { self == .precise ? "精确" : "矩形" }
}

enum EraseEngine: String, CaseIterable, Identifiable {
    case system
    case openCV

    var id: Self { self }
    var title: String { self == .system ? "macOS 系统" : "OpenCV" }
}
