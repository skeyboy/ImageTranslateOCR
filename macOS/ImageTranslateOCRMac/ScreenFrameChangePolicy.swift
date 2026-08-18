import CoreGraphics
import Foundation

enum ScreenFrameChangePolicy {
    static let settleDelay: Duration = .milliseconds(520)
    static let sampleInterval: Duration = .milliseconds(300)
    static let changedSampleRatio = 0.055
    static let luminanceDelta = 20

    static func differenceRatio(_ first: [UInt8], _ second: [UInt8]) -> Double {
        guard first.count == second.count, !first.isEmpty else { return 1 }
        let changed = zip(first, second).reduce(into: 0) { count, pair in
            if abs(Int(pair.0) - Int(pair.1)) >= luminanceDelta { count += 1 }
        }
        return Double(changed) / Double(first.count)
    }

    static func hasMovement(from first: [UInt8], to second: [UInt8]) -> Bool {
        differenceRatio(first, second) >= changedSampleRatio
    }

    static func isSelectedWindowFrontmost(selectedID: UInt32, orderedWindowIDs: [UInt32]) -> Bool {
        orderedWindowIDs.first == selectedID
    }
}

enum ScreenOverlayGeometry {
    static func appKitFrame(
        captureFrame: CGRect,
        displayFrame: CGRect,
        screenFrame: CGRect
    ) -> CGRect {
        CGRect(
            x: screenFrame.minX + captureFrame.minX - displayFrame.minX,
            y: screenFrame.maxY - (captureFrame.maxY - displayFrame.minY),
            width: captureFrame.width,
            height: captureFrame.height
        )
    }
}
