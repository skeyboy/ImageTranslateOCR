import Foundation

@main
enum ScreenFrameChangePolicySmoke {
    static func main() {
        let baseline = [UInt8](repeating: 100, count: 100)
        var belowThreshold = baseline
        for index in 0..<5 { belowThreshold[index] = 140 }
        precondition(!ScreenFrameChangePolicy.hasMovement(from: baseline, to: belowThreshold))

        var movement = baseline
        for index in 0..<6 { movement[index] = 140 }
        precondition(ScreenFrameChangePolicy.hasMovement(from: baseline, to: movement))
        precondition(ScreenFrameChangePolicy.differenceRatio([], []) == 1)
        precondition(ScreenFrameChangePolicy.settleDelay == .milliseconds(520))
        precondition(ScreenFrameChangePolicy.isSelectedWindowFrontmost(
            selectedID: 42,
            orderedWindowIDs: [42, 99]
        ))
        precondition(!ScreenFrameChangePolicy.isSelectedWindowFrontmost(
            selectedID: 42,
            orderedWindowIDs: [99, 42]
        ))
        let mapped = ScreenOverlayGeometry.appKitFrame(
            captureFrame: CGRect(x: 2_000, y: 100, width: 800, height: 500),
            displayFrame: CGRect(x: 1_920, y: 0, width: 1_280, height: 1_024),
            screenFrame: CGRect(x: 1_920, y: 0, width: 1_280, height: 1_024)
        )
        precondition(mapped == CGRect(x: 2_000, y: 424, width: 800, height: 500))
        print("Screen frame change policy smoke passed")
    }
}
