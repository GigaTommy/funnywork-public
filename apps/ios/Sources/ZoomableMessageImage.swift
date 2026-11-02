import SwiftUI
import UIKit

struct ZoomableMessageImage: UIViewRepresentable {
    let image: UIImage
    let name: String
    let move: (Int) -> Void
    func makeUIView(context: Context) -> MessageImageScrollView { MessageImageScrollView(image: image, name: name, move: move) }
    func updateUIView(_ view: MessageImageScrollView, context: Context) {}
    func sizeThatFits(_ proposal: ProposedViewSize, uiView: MessageImageScrollView, context: Context) -> CGSize? {
        guard let width = proposal.width, let height = proposal.height else { return nil }
        return CGSize(width: width, height: height)
    }
}

final class MessageImageScrollView: UIScrollView, UIScrollViewDelegate, UIGestureRecognizerDelegate {
    private let picture = UIImageView()
    private let canvas = UIView()
    private let move: (Int) -> Void
    private var previousSize = CGSize.zero
    init(image: UIImage, name: String, move: @escaping (Int) -> Void) {
        self.move = move
        super.init(frame: .zero)
        picture.image = image; picture.frame = CGRect(origin: .zero, size: image.size)
        canvas.frame = picture.frame; canvas.addSubview(picture)
        addSubview(canvas); contentSize = image.size
        delegate = self; showsHorizontalScrollIndicator = false; showsVerticalScrollIndicator = false
        backgroundColor = .clear; contentInsetAdjustmentBehavior = .never
        isAccessibilityElement = true; accessibilityLabel = name; accessibilityTraits = .image
        accessibilityIdentifier = "gallery-image"
        let doubleTap = UITapGestureRecognizer(target: self, action: #selector(toggleZoom(_:)))
        doubleTap.numberOfTapsRequired = 2; addGestureRecognizer(doubleTap)
        for direction in [UISwipeGestureRecognizer.Direction.left, .right] {
            let swipe = UISwipeGestureRecognizer(target: self, action: #selector(swipe(_:)))
            swipe.direction = direction; swipe.delegate = self; addGestureRecognizer(swipe)
            panGestureRecognizer.require(toFail: swipe)
        }
        accessibilityCustomActions = [
            UIAccessibilityCustomAction(name: String(localized: "放大"), target: self, selector: #selector(zoomIn)),
            UIAccessibilityCustomAction(name: String(localized: "适合屏幕"), target: self, selector: #selector(resetZoom))
        ]
    }
    required init?(coder: NSCoder) { fatalError("init(coder:) has not been implemented") }
    override func layoutSubviews() {
        super.layoutSubviews()
        guard bounds.width > 0, bounds.height > 0, let image = picture.image else { return }
        if previousSize != bounds.size {
            previousSize = bounds.size
            let fit = min(bounds.width / image.size.width, bounds.height / image.size.height)
            UIView.performWithoutAnimation {
                minimumZoomScale = fit; maximumZoomScale = fit * 6; zoomScale = fit
                setContentOffset(.zero, animated: false)
                centerPicture()
                canvas.layer.removeAllAnimations()
            }
        }
        centerPicture()
    }
    func viewForZooming(in scrollView: UIScrollView) -> UIView? { canvas }
    func scrollViewDidZoom(_ scrollView: UIScrollView) { centerPicture() }
    func scrollViewDidEndZooming(_ scrollView: UIScrollView, with view: UIView?, atScale scale: CGFloat) {
        if scale <= minimumZoomScale * 1.01 { setContentOffset(.zero, animated: false) }
    }
    private func centerPicture() {
        canvas.center = CGPoint(x: max(contentSize.width, bounds.width) / 2, y: max(contentSize.height, bounds.height) / 2)
        if zoomScale <= minimumZoomScale * 1.01 && contentOffset != .zero { setContentOffset(.zero, animated: false) }
        accessibilityValue = "\(Int((zoomScale / max(minimumZoomScale, 0.001) * 100).rounded()))%"

    }
    override func gestureRecognizerShouldBegin(_ gestureRecognizer: UIGestureRecognizer) -> Bool {
        guard gestureRecognizer is UISwipeGestureRecognizer else { return super.gestureRecognizerShouldBegin(gestureRecognizer) }
        return zoomScale <= minimumZoomScale * 1.01
    }
    @objc private func swipe(_ gesture: UISwipeGestureRecognizer) { move(gesture.direction == .left ? 1 : -1) }
    @objc private func toggleZoom(_ gesture: UITapGestureRecognizer) {
        if zoomScale > minimumZoomScale * 1.01 { setZoomScale(minimumZoomScale, animated: true) }
        else {
            let point = gesture.location(in: picture), target = minimumZoomScale * 3
            let size = CGSize(width: bounds.width / target, height: bounds.height / target)
            zoom(to: CGRect(x: point.x - size.width / 2, y: point.y - size.height / 2, width: size.width, height: size.height), animated: true)
        }
    }
    @objc private func zoomIn() -> Bool { setZoomScale(min(maximumZoomScale, zoomScale * 2), animated: true); return true }
    @objc private func resetZoom() -> Bool { setZoomScale(minimumZoomScale, animated: true); return true }
}
