import AppKit
import CoreGraphics

@MainActor
final class ScreenTranslationOverlayController {
    private var panel: NSPanel?
    private var imageView: NSImageView?

    func present(image: NSImage, frame: CapturedScreenFrame) {
        let panel = ensurePanel()
        panel.setFrame(Self.appKitFrame(for: frame.targetFrame, displayID: frame.displayID), display: true)
        imageView?.image = image
        panel.orderFrontRegardless()
    }

    func updateTargetFrame(_ state: ScreenCaptureTargetState) {
        guard panel?.isVisible == true else { return }
        panel?.setFrame(Self.appKitFrame(for: state.frame, displayID: state.displayID), display: true)
    }

    func hide(clearImage: Bool = false) {
        panel?.orderOut(nil)
        if clearImage { imageView?.image = nil }
    }

    func close() {
        panel?.orderOut(nil)
        panel?.close()
        panel = nil
        imageView = nil
    }

    private func ensurePanel() -> NSPanel {
        if let panel { return panel }
        let panel = NSPanel(
            contentRect: .zero,
            styleMask: [.borderless, .nonactivatingPanel],
            backing: .buffered,
            defer: false
        )
        panel.isOpaque = false
        panel.backgroundColor = .clear
        panel.hasShadow = false
        panel.ignoresMouseEvents = true
        panel.level = .floating
        panel.collectionBehavior = [.canJoinAllSpaces, .fullScreenAuxiliary, .stationary]
        panel.hidesOnDeactivate = false
        panel.isReleasedWhenClosed = false

        let imageView = NSImageView(frame: .zero)
        imageView.imageScaling = .scaleAxesIndependently
        imageView.autoresizingMask = [.width, .height]
        panel.contentView = imageView
        self.panel = panel
        self.imageView = imageView
        return panel
    }

    private static func appKitFrame(for captureFrame: CGRect, displayID: CGDirectDisplayID) -> CGRect {
        guard let screen = NSScreen.screens.first(where: { screen in
            (screen.deviceDescription[NSDeviceDescriptionKey("NSScreenNumber")] as? NSNumber)?.uint32Value
                == displayID
        }) else {
            return captureFrame
        }
        let displayFrame = CGDisplayBounds(displayID)
        return ScreenOverlayGeometry.appKitFrame(
            captureFrame: captureFrame,
            displayFrame: displayFrame,
            screenFrame: screen.frame
        )
    }
}
