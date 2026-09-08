import Flutter
import UIKit

/// Flutter-owned UI address. It retains no scanner settings and never owns the SDK.
final class ScannerView: NSObject, FlutterPlatformView {
    let viewId: Int64
    private let container: UIView
    private let onDispose: (ScannerView) -> Void

    init(frame: CGRect, viewId: Int64, onDispose: @escaping (ScannerView) -> Void) {
        self.viewId = viewId
        self.onDispose = onDispose
        container = UIView(frame: frame)
        super.init()
    }

    deinit { onDispose(self) }

    func view() -> UIView { container }

    /// UIKit owns layout; the shared native preview simply fills its current container.
    func attach(_ preview: UIView) {
        preview.removeFromSuperview()
        preview.frame = container.bounds
        preview.autoresizingMask = [.flexibleWidth, .flexibleHeight]
        container.addSubview(preview)
        preview.setNeedsLayout()
        preview.layoutIfNeeded()
    }
}
