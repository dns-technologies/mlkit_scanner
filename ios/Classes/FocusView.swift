//
//  CenterFocusView.swift
//  mlkit_scanner
//
//  Created by ООО "ДНС Технологии" on 17.08.2021.
//

import UIKit

/// Receives focus gestures from the scanner focus overlay.
protocol FocusViewDelegate: NSObject {
    /// Requests continuous focus at the current point.
    func onFocus()
    
    /// Requests locked focus at the current point.
    func onLockFocus()
}

/// Handles focus gestures and their focus-circle and lock animations.
class FocusView: UIView {
    private let lockImage: UIImageView
    private var circleLayer: CAShapeLayer
    private let circleRadius: CGFloat = 40
    private let fadeDuration: TimeInterval = 0.2
    private var lockInitialCenter: CGPoint
    weak var delegate: FocusViewDelegate?
    
    /// Creates a focus overlay centered on `point`.
    init(frame: CGRect, point: CGPoint) {
        let image = UIImage.fromLibraryAssets(name: "lock")
        lockImage = UIImageView(image: image)
        lockInitialCenter = CGPoint(x: point.x - (circleRadius + lockImage.bounds.width), y: point.y)
        lockImage.center = lockInitialCenter
        lockImage.alpha = 0
        
        circleLayer = FocusView.buildCircle(with: circleRadius, point: point)
        
        super.init(frame: frame)
        layer.addSublayer(circleLayer)
        addSubview(lockImage)
        addTapRecognizer()
        addLongPressRecognizer()
    }
    
    override func didMoveToSuperview() {
        super.didMoveToSuperview()
        if let superview = superview {
            frame = superview.bounds
            autoresizingMask = [.flexibleWidth, .flexibleHeight]
        }
    }
    
    override func layoutSubviews() {
        super.layoutSubviews()
    }

    /// Moves focus visuals to a new preview coordinate.
    func moveFocus(to point: CGPoint) {
        guard point.x.isFinite, point.y.isFinite else { return }
        circleLayer.path = FocusView.buildCirclePath(radius: circleRadius, point: point).cgPath
        lockInitialCenter = CGPoint(x: point.x - (circleRadius + lockImage.bounds.width), y: point.y)
        if (lockImage.alpha == 0) {
            lockImage.center = lockInitialCenter
        }
    }

    /// Builds the initially hidden focus-circle layer.
    private class func buildCircle(with radius: CGFloat, point: CGPoint) -> CAShapeLayer {
        let layer = CAShapeLayer()
        let path = FocusView.buildCirclePath(radius: radius, point: point)
        layer.path = path.cgPath
        layer.fillColor = UIColor.clear.cgColor
        layer.strokeColor = UIColor.white.cgColor
        layer.lineWidth = 1.0
        layer.opacity = 0
        return layer
    }
    
    /// Builds a circular path centered on the focus point.
    private class func buildCirclePath(radius: CGFloat, point: CGPoint) -> UIBezierPath {
        return UIBezierPath(arcCenter: point, radius: radius, startAngle: 0, endAngle: .pi * 2, clockwise: true)
    }
    
    required init?(coder: NSCoder) {
        fatalError("init(coder:) has not been implemented")
    }
    
    /// Hides a visible focus-lock indicator.
    func cancelLockFocus() {
        fadeOutLock()
    }
    
    /// Adds the gesture that requests continuous focus.
    private func addTapRecognizer() {
        let tap = UITapGestureRecognizer(target: self, action: #selector(onTap(_:)))
        addGestureRecognizer(tap)
    }
    
    /// Animates and forwards a continuous-focus tap.
    @objc private func onTap(_ sender: UITapGestureRecognizer) {
        fadeOutLock()
        animateCircle()
        delegate?.onFocus()
    }
    
    /// Adds the gesture that requests locked focus.
    private func addLongPressRecognizer() {
        let longTap = UILongPressGestureRecognizer(target: self, action: #selector(onLongTap(_:)))
        addGestureRecognizer(longTap)
    }
    
    /// Animates and forwards the beginning of a long-press focus lock.
    @objc private func onLongTap(_ sender: UILongPressGestureRecognizer) {
        guard sender.state == .began else {
            return
        }
        fadeInLock()
        animateCircle()
        delegate?.onLockFocus()
    }
    
    /// Briefly reveals the focus circle for gesture feedback.
    private func animateCircle() {
        let animation = CABasicAnimation(keyPath: "opacity")
        animation.autoreverses = true
        animation.fromValue = 0
        animation.toValue = 1
        animation.duration = 0.5
        animation.isRemovedOnCompletion = true
        circleLayer.add(animation, forKey: nil)
    }
    
    /// Reveals and moves the focus-lock indicator into its locked position.
    private func fadeInLock() {
        guard lockInitialCenter.x.isFinite, lockInitialCenter.y.isFinite else { return }
        UIView.animateKeyframes(withDuration: 1, delay: 0, options: .calculationModeLinear) {
            UIView.addKeyframe(withRelativeStartTime: 0, relativeDuration: 0.25) { [weak self] in
                self?.lockImage.alpha = 1
            }
            UIView.addKeyframe(withRelativeStartTime: 0.25, relativeDuration: 0.75) { [weak self] in
                guard let self = self else { return }
                self.lockImage.frame.origin = CGPoint(x: 20, y: 20)
            }
        }
    }
    
    /// Hides and resets the focus-lock indicator.
    private func fadeOutLock() {
        guard lockInitialCenter.x.isFinite, lockInitialCenter.y.isFinite else { return }
        UIView.animate(withDuration: fadeDuration) { [weak self] in
            self?.lockImage.alpha = 0
        } completion: { [weak self] _ in
            guard let self = self else { return }
            self.lockImage.center = self.lockInitialCenter
        }
    }
}
