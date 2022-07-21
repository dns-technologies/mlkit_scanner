//
//  CenterFocusView.swift
//  mlkit_scanner
//
//  Created by ООО "ДНС Технологии" on 17.08.2021.
//

import UIKit

/// Delegate of CenterFocusView
protocol FocusViewDelegate: NSObject {
    /// Call delegate when user try focus on the center of view
    func onFocus()
    
    /// Call delegate when user try lock focus on the center of view
    func onLockFocus()
}

/// View handles animation and gestures when user try use AutoFocus or Lock Focus and call `FocusViewDelegate` methods
class FocusView: UIView {
    private let lockImage: UIImageView
    private var circleLayer: CAShapeLayer
    private let circleRadius: CGFloat = 40
    private let fadeDuration: TimeInterval = 0.2
    private var lockInitialCenter: CGPoint
    weak var delegate: FocusViewDelegate?
    
    init(frame: CGRect, point: CGPoint) {
        let image = UIImage.fromLibraryAssets(name: "lock")
        lockImage = UIImageView(image: image)
        lockInitialCenter = CGPoint(x: frame.midX - (circleRadius + lockImage.bounds.width), y: frame.midY)
        lockImage.center = lockInitialCenter
        lockImage.alpha = 0
        
        circleLayer = FocusView.buildCircle(in: frame, with: circleRadius, point: point)
        
        super.init(frame: frame)
        layer.addSublayer(circleLayer)
        addSubview(lockImage)
        addTapRecognizer()
        addLongPressRecognizer()
    }
    
    override func didMoveToSuperview() {
        super.didMoveToSuperview()
        if let superview = superview {
            translatesAutoresizingMaskIntoConstraints = false
            heightAnchor.constraint(equalTo: superview.heightAnchor).isActive = true
            widthAnchor.constraint(equalTo: superview.widthAnchor).isActive = true
        }
    }
    
    override func layoutSubviews() {
        super.layoutSubviews()
        updateLayers()
    }
    
    private func updateLayers() {
        lockInitialCenter = CGPoint(x: frame.midX - (circleRadius + lockImage.bounds.width), y: frame.midY)
        if (lockImage.alpha == 0) {
            lockImage.center = lockInitialCenter
        }
    }

    func changeFocusPoint(point: CGPoint) {
        circleLayer.path = FocusView.buildCirclePath(frame: frame, radius: circleRadius, point: point).cgPath
    }

    private class func buildCircle(in frame: CGRect, with radius: CGFloat, point: CGPoint) -> CAShapeLayer {
        let layer = CAShapeLayer()
        let path = FocusView.buildCirclePath(frame: frame, radius: radius, point: point)
        layer.path = path.cgPath
        layer.fillColor = UIColor.clear.cgColor
        layer.strokeColor = UIColor.white.cgColor
        layer.lineWidth = 1.0
        layer.opacity = 0
        return layer
    }
    
    private class func buildCirclePath(frame: CGRect, radius: CGFloat, point: CGPoint) -> UIBezierPath {
        return UIBezierPath(arcCenter: point, radius: radius, startAngle: 0, endAngle: .pi * 2, clockwise: true)
    }
    
    required init?(coder: NSCoder) {
        fatalError("init(coder:) has not been implemented")
    }
    
    func cancelLockFocus() {
        fadeOutLock()
    }
    
    private func addTapRecognizer() {
        let tap = UITapGestureRecognizer(target: self, action: #selector(onTap(_:)))
        addGestureRecognizer(tap)
    }
    
    @objc private func onTap(_ sender: UITapGestureRecognizer) {
        fadeOutLock()
        animateCircle()
        delegate?.onFocus()
    }
    
    private func addLongPressRecognizer() {
        let longTap = UILongPressGestureRecognizer(target: self, action: #selector(onLongTap(_:)))
        addGestureRecognizer(longTap)
    }
    
    @objc private func onLongTap(_ sender: UILongPressGestureRecognizer) {
        guard sender.state == .began else {
            return
        }
        fadeInLock()
        animateCircle()
        delegate?.onLockFocus()
    }
    
    private func animateCircle() {
        let animation = CABasicAnimation(keyPath: "opacity")
        animation.autoreverses = true
        animation.fromValue = 0
        animation.toValue = 1
        animation.duration = 0.5
        animation.isRemovedOnCompletion = true
        circleLayer.add(animation, forKey: nil)
    }
    
    private func fadeInLock() {
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
    
    private func fadeOutLock() {
        UIView.animate(withDuration: fadeDuration) { [weak self] in
            self?.lockImage.alpha = 0
        } completion: { [weak self] _ in
            guard let self = self else { return }
            self.lockImage.center = self.lockInitialCenter
        }
    }
}
