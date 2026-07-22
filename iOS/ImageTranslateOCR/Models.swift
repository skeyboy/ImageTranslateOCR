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

enum MaskMode: String, CaseIterable, Identifiable {
    case precise
    case rectangle

    var id: Self { self }
    var title: String { self == .precise ? "精确" : "矩形" }
}
