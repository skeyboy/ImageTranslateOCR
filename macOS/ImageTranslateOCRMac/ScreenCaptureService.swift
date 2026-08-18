import AppKit
import CoreGraphics
import ScreenCaptureKit

struct ScreenCaptureService: Sendable {
    func availableSources() async throws -> [ScreenCaptureSource] {
        let content = try await SCShareableContent.excludingDesktopWindows(
            true,
            onScreenWindowsOnly: true
        )
        let displays = content.displays.enumerated().map { index, display in
            ScreenCaptureSource(
                id: "display:\(display.displayID)",
                kind: .display,
                nativeID: display.displayID,
                title: "显示器 \(index + 1)",
                applicationName: nil,
                processID: nil
            )
        }
        let ownBundleID = Bundle.main.bundleIdentifier
        let windows = content.windows
            .filter { window in
                window.isOnScreen && window.windowLayer == 0 &&
                    window.frame.width >= 160 && window.frame.height >= 120 &&
                    window.owningApplication?.bundleIdentifier != ownBundleID &&
                    !(window.title?.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty ?? true)
            }
            .sorted {
                let firstApp = $0.owningApplication?.applicationName ?? ""
                let secondApp = $1.owningApplication?.applicationName ?? ""
                return firstApp == secondApp
                    ? ($0.title ?? "").localizedStandardCompare($1.title ?? "") == .orderedAscending
                    : firstApp.localizedStandardCompare(secondApp) == .orderedAscending
            }
            .map { window in
                ScreenCaptureSource(
                    id: "window:\(window.windowID)",
                    kind: .window,
                    nativeID: window.windowID,
                    title: window.title ?? "未命名窗口",
                    applicationName: window.owningApplication?.applicationName,
                    processID: window.owningApplication?.processID
                )
            }
        return displays + windows
    }

    func capture(_ source: ScreenCaptureSource, maximumDimension: Int? = nil) async throws -> CapturedScreenFrame {
        guard targetState(for: source)?.isFrontmost == true else {
            throw CaptureFailure.sourceNotFrontmost
        }
        let content = try await SCShareableContent.excludingDesktopWindows(
            true,
            onScreenWindowsOnly: true
        )
        let resolved = try resolve(source, from: content)
        let filter: SCContentFilter
        let targetFrame: CGRect
        let displayID: CGDirectDisplayID

        switch resolved {
        case .display(let display):
            let ownApplication = content.applications.first {
                $0.bundleIdentifier == Bundle.main.bundleIdentifier
            }
            filter = SCContentFilter(
                display: display,
                excludingApplications: ownApplication.map { [$0] } ?? [],
                exceptingWindows: []
            )
            targetFrame = display.frame
            displayID = display.displayID
        case .window(let window):
            filter = SCContentFilter(desktopIndependentWindow: window)
            targetFrame = window.frame
            displayID = Self.displayContaining(targetFrame, displays: content.displays)?.displayID
                ?? CGMainDisplayID()
        }

        let info = SCShareableContent.info(for: filter)
        let configuration = SCStreamConfiguration()
        let naturalWidth = max(1, Int((info.contentRect.width * CGFloat(info.pointPixelScale)).rounded()))
        let naturalHeight = max(1, Int((info.contentRect.height * CGFloat(info.pointPixelScale)).rounded()))
        if let maximumDimension, max(naturalWidth, naturalHeight) > maximumDimension {
            let scale = CGFloat(maximumDimension) / CGFloat(max(naturalWidth, naturalHeight))
            configuration.width = max(1, Int((CGFloat(naturalWidth) * scale).rounded()))
            configuration.height = max(1, Int((CGFloat(naturalHeight) * scale).rounded()))
        } else {
            configuration.width = naturalWidth
            configuration.height = naturalHeight
        }
        configuration.scalesToFit = true
        configuration.preservesAspectRatio = true
        configuration.showsCursor = false
        configuration.ignoreShadowsSingleWindow = true
        configuration.ignoreShadowsDisplay = true

        let image = try await SCScreenshotManager.captureImage(
            contentFilter: filter,
            configuration: configuration
        )
        guard targetState(for: source)?.isFrontmost == true else {
            throw CaptureFailure.sourceNotFrontmost
        }
        return CapturedScreenFrame(
            image: image,
            source: source,
            targetFrame: targetFrame,
            displayID: displayID
        )
    }

    func targetState(for source: ScreenCaptureSource) -> ScreenCaptureTargetState? {
        switch source.kind {
        case .display:
            let displayID = CGDirectDisplayID(source.nativeID)
            return ScreenCaptureTargetState(
                isFrontmost: true,
                frame: CGDisplayBounds(displayID),
                displayID: displayID
            )
        case .window:
            guard let windowList = CGWindowListCopyWindowInfo(
                [.optionOnScreenOnly, .excludeDesktopElements],
                kCGNullWindowID
            ) as? [[CFString: Any]] else { return nil }
            let ownPID = ProcessInfo.processInfo.processIdentifier
            let eligible = windowList.compactMap { info -> (id: UInt32, frame: CGRect)? in
                guard (info[kCGWindowLayer] as? NSNumber)?.intValue == 0,
                      (info[kCGWindowOwnerPID] as? NSNumber)?.int32Value != ownPID,
                      (info[kCGWindowAlpha] as? NSNumber)?.doubleValue ?? 1 > 0,
                      let id = (info[kCGWindowNumber] as? NSNumber)?.uint32Value else { return nil }
                let bounds = info[kCGWindowBounds] as! CFDictionary
                guard let frame = CGRect(dictionaryRepresentation: bounds),
                      frame.width >= 2, frame.height >= 2 else { return nil }
                return (id, frame)
            }
            guard let selected = eligible.first(where: { $0.id == source.nativeID }) else { return nil }
            return ScreenCaptureTargetState(
                isFrontmost: ScreenFrameChangePolicy.isSelectedWindowFrontmost(
                    selectedID: source.nativeID,
                    orderedWindowIDs: eligible.map(\.id)
                ),
                frame: selected.frame,
                displayID: Self.displayContaining(selected.frame)
            )
        }
    }

    private func resolve(
        _ source: ScreenCaptureSource,
        from content: SCShareableContent
    ) throws -> ResolvedSource {
        switch source.kind {
        case .display:
            guard let display = content.displays.first(where: { $0.displayID == source.nativeID }) else {
                throw CaptureFailure.sourceUnavailable
            }
            return .display(display)
        case .window:
            guard let window = content.windows.first(where: { $0.windowID == source.nativeID && $0.isOnScreen }) else {
                throw CaptureFailure.sourceUnavailable
            }
            return .window(window)
        }
    }

    private static func displayContaining(
        _ frame: CGRect,
        displays: [SCDisplay]
    ) -> SCDisplay? {
        displays.max { first, second in
            first.frame.intersection(frame).area < second.frame.intersection(frame).area
        }
    }

    private static func displayContaining(_ frame: CGRect) -> CGDirectDisplayID {
        var displayCount: UInt32 = 0
        var displays = [CGDirectDisplayID](repeating: 0, count: 16)
        guard CGGetDisplaysWithRect(frame, UInt32(displays.count), &displays, &displayCount) == .success,
              displayCount > 0 else { return CGMainDisplayID() }
        return displays.prefix(Int(displayCount)).max { first, second in
            CGDisplayBounds(first).intersection(frame).area < CGDisplayBounds(second).intersection(frame).area
        } ?? CGMainDisplayID()
    }

    private enum ResolvedSource {
        case display(SCDisplay)
        case window(SCWindow)
    }

    enum CaptureFailure: LocalizedError {
        case sourceUnavailable
        case sourceNotFrontmost

        var errorDescription: String? {
            switch self {
            case .sourceUnavailable:
                "采集目标已关闭或不再可见，请刷新列表后重试"
            case .sourceNotFrontmost:
                "等待所选窗口切换到最前"
            }
        }
    }
}

private extension CGRect {
    var area: CGFloat {
        isNull ? 0 : width * height
    }
}
