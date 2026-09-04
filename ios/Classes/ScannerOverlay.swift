//
//  ScannerOverlay.swift
//  mlkit_scanner
//
//  Created by ООО "ДНС Технологии" on 17.08.2021.
//

import Foundation

/// Draws the active recognition rectangle and dims the surrounding preview.
class ScannerOverlay: UIView {
    private let overlayColor = UIColor(red: 0, green: 0, blue: 0, alpha: 0.5)
    private var cornerLinelength: CGFloat = 0
    private var radius: CGFloat = 0
    private var borderRect: CGRect = CGRect.zero
    private(set) var cropRect: CropRect

    /// Controls whether the recognition border uses its active color.
    var isActive = false {
        didSet {
            setNeedsDisplay()
        }
    }
    
    private var borderColor: UIColor {
        isActive
            ? UIColor(red: 0.26, green: 0.63, blue: 0.28, alpha: 1)
            : UIColor(red: 0.38, green: 0.38, blue: 0.38, alpha: 1.00)
    }
    
    /// Creates an overlay for normalized `cropRect` geometry.
    required init(cropRect: CropRect) {
        self.cropRect = cropRect
        super.init(frame: CGRect.zero)
        backgroundColor = .clear
    }
    
    override func didMoveToSuperview() {
        super.didMoveToSuperview()
        if let superview = superview {
            frame = superview.bounds
            autoresizingMask = [.flexibleWidth, .flexibleHeight]
        }
    }
    
    required init?(coder: NSCoder) {
        fatalError("init(coder:) has not been implemented")
    }
    
    override func layoutSubviews() {
        super.layoutSubviews()
        updateOverlay()
    }
    
    override func draw(_ rect: CGRect) {
        let background = UIBezierPath()
        let topLeftArcCenter = CGPoint(x: borderRect.minX + radius, y: borderRect.minY + radius)
        let topRightArcCenter = CGPoint(x: borderRect.maxX - radius, y: borderRect.minY + radius)
        let bottomRightArcCenter = CGPoint(x: borderRect.maxX - radius, y: borderRect.maxY - radius)
        let bottomLeftArcCenter = CGPoint(x: borderRect.minX + radius, y: borderRect.maxY - radius)
        background.move(to: rect.origin)
        background.addLine(to: borderRect.origin)
        background.addLine(to: CGPoint(x: borderRect.minX, y: borderRect.minY + radius))
        background.addArc(
            withCenter: topLeftArcCenter,
            radius: radius,
            startAngle: -.pi,
            endAngle: -.pi / 2,
            clockwise: true
        )
        background.addLine(to: CGPoint(x: borderRect.maxX, y: borderRect.minY))
        background.addArc(
            withCenter: topRightArcCenter,
            radius: radius,
            startAngle: -.pi / 2,
            endAngle: 0,
            clockwise: true
        )
        background.addLine(to: CGPoint(x: borderRect.maxX, y: borderRect.maxY))
        background.addArc(
            withCenter: CGPoint(x: borderRect.maxX - radius, y: borderRect.maxY - radius),
            radius: radius,
            startAngle: 0,
            endAngle: .pi / 2,
            clockwise: true
        )
        background.addLine(to: CGPoint(x: borderRect.maxX, y: borderRect.maxY))
        background.addLine(to: CGPoint(x: rect.maxX, y: rect.maxY))
        background.addLine(to: CGPoint(x: rect.maxX, y: rect.minY))
        background.addLine(to: rect.origin)
        background.addLine(to: borderRect.origin)
        background.addLine(to: CGPoint(x: borderRect.minX, y: borderRect.maxY))
        background.addArc(
            withCenter: bottomLeftArcCenter,
            radius: radius,
            startAngle: .pi / 2,
            endAngle: .pi,
            clockwise: true
        )
        background.addLine(to: CGPoint(x: borderRect.minX, y: borderRect.maxY))
        background.addLine(to: CGPoint(x: borderRect.maxX, y: borderRect.maxY))
        background.addLine(to: CGPoint(x: rect.maxX, y: rect.maxY))
        background.addLine(to: CGPoint(x: rect.minY, y: rect.maxY))


        overlayColor.setFill()
        background.fill()
        let borderPath = UIBezierPath()
        // Top Left Corner
        borderPath.roundCorner(
            from: CGPoint(x: borderRect.minX, y: borderRect.minY + cornerLinelength),
            to: CGPoint(x: borderRect.minX + cornerLinelength, y: borderRect.minY),
            arcCenter: topLeftArcCenter,
            startAngle: -.pi,
            endAngle: -.pi / 2,
            radius: radius
        )

        // Top Right Corner
        borderPath.roundCorner(
            from: CGPoint(x: borderRect.maxX - cornerLinelength, y: borderRect.minY),
            to: CGPoint(x: borderRect.maxX, y: borderRect.minY + cornerLinelength),
            arcCenter: topRightArcCenter,
            startAngle: -.pi / 2,
            endAngle: 0,
            radius: radius
        )
    
        // Bottom Right Corner
        borderPath.roundCorner(
            from: CGPoint(x: borderRect.maxX, y: borderRect.maxY - cornerLinelength),
            to: CGPoint(x: borderRect.maxX - cornerLinelength, y: borderRect.maxY),
            arcCenter: bottomRightArcCenter,
            startAngle: 0,
            endAngle: .pi / 2,
            radius: radius
        )

        // Bottom Left Corner
        borderPath.roundCorner(
            from: CGPoint(x: borderRect.minX + cornerLinelength, y: borderRect.maxY),
            to: CGPoint(x: borderRect.minX, y: borderRect.maxY - cornerLinelength),
            arcCenter: bottomLeftArcCenter,
            startAngle: .pi / 2,
            endAngle: .pi,
            radius: radius
        )
        
        borderColor.setStroke()
        borderPath.lineWidth = 2
        borderPath.lineCapStyle = .round
        borderPath.stroke()
    }

    
    /// Updates the normalized crop geometry displayed by the overlay.
    func updateCropRect(rect: CropRect) {
        cropRect = rect
        updateOverlay()
    }

    /// Resolves normalized crop geometry against the current overlay bounds.
    private func updateOverlay() {
        let width = bounds.width * cropRect.scaleWidth
        let height = bounds.height * cropRect.scaleHeight
        let x = bounds.midX * (1 + cropRect.offsetX) - width / 2
        let y = bounds.midY * (1 + cropRect.offsetY) - height / 2
        borderRect = CGRect(x: x, y: y, width: width, height: height)
        cornerLinelength = width * 0.05
        radius = cornerLinelength / 4
        setNeedsDisplay()
    }
}

private extension UIBezierPath {
    /// Adds one rounded scanner-border corner to this path.
    func roundCorner(
        from: CGPoint,
        to: CGPoint,
        arcCenter: CGPoint,
        startAngle: Double,
        endAngle: Double,
        radius: CGFloat
    ) {
        move(to: from)
        addArc(
            withCenter: arcCenter,
            radius: radius,
            startAngle: CGFloat(startAngle),
            endAngle: CGFloat(endAngle),
            clockwise: true
        )
        addLine(to: to)
    }
}
