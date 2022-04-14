//
//  ScannerOverlay.swift
//  mlkit_scanner
//
//  Created by ООО "ДНС Технологии" on 17.08.2021.
//

import Foundation

// Scanner Overlay with area `CropRect` where detection will take a place
class ScannerOverlay: UIView {
    private let overlayColor = UIColor(red: 0, green: 0, blue: 0, alpha: 0.5)
    private var cornerLinelength: CGFloat = 0
    private var borderRect: CGRect = CGRect.zero
    private (set) var cropRect: CropRect

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
    
    required init( cropRect: CropRect) {
        self.cropRect = cropRect
        super.init(frame: CGRect.zero)
        backgroundColor = .clear
    }
    
    override func didMoveToSuperview() {
        super.didMoveToSuperview()
        if let superview = superview {
            translatesAutoresizingMaskIntoConstraints = false
            heightAnchor.constraint(equalTo: superview.heightAnchor).isActive = true
            widthAnchor.constraint(equalTo: superview.widthAnchor).isActive = true
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
        background.move(to: rect.origin)
        background.addLine(to: borderRect.origin)
        background.addLine(to: CGPoint(x: borderRect.maxX, y: borderRect.minY))
        background.addLine(to: CGPoint(x: borderRect.maxX, y: borderRect.maxY))
        background.addLine(to: CGPoint(x: rect.maxX, y: rect.maxY))
        background.addLine(to: CGPoint(x: rect.maxX, y: rect.minY))
        background.addLine(to: rect.origin)
        background.addLine(to: borderRect.origin)
        background.addLine(to: CGPoint(x: borderRect.minX, y: borderRect.maxY))
        background.addLine(to: CGPoint(x: borderRect.maxX, y: borderRect.maxY))
        background.addLine(to: CGPoint(x: rect.maxX, y: rect.maxY))
        background.addLine(to: CGPoint(x: rect.minY, y: rect.maxY))


        overlayColor.setFill()
        background.fill()
        
        let borderPath = UIBezierPath()
        borderPath.move(to: CGPoint(x: borderRect.minX, y: borderRect.minY + cornerLinelength))
        borderPath.addLine(to: CGPoint(x: borderRect.minX, y: borderRect.minY))
        borderPath.addLine(to: CGPoint(x: borderRect.minX + cornerLinelength, y: borderRect.minY))

        // Top Right Corner
        borderPath.move(to: CGPoint(x: borderRect.maxX - cornerLinelength, y: borderRect.minY))
        borderPath.addLine(to: CGPoint(x: borderRect.maxX, y: borderRect.minY))
        borderPath.addLine(to: CGPoint(x: borderRect.maxX, y: borderRect.minY + cornerLinelength))
    
        // Bottom Right Corner
        borderPath.move(to: CGPoint(x: borderRect.maxX, y: borderRect.maxY - cornerLinelength))
        borderPath.addLine(to: CGPoint(x: borderRect.maxX, y: borderRect.maxY))
        borderPath.addLine(to: CGPoint(x: borderRect.maxX - cornerLinelength, y: borderRect.maxY))

        // Bottom Left Corner
        borderPath.move(to: CGPoint(x: borderRect.minX + cornerLinelength, y: borderRect.maxY))
        borderPath.addLine(to: CGPoint(x: borderRect.minX, y: borderRect.maxY))
        borderPath.addLine(to: CGPoint(x: borderRect.minX, y: borderRect.maxY - cornerLinelength))

        borderColor.setStroke()
        borderPath.lineWidth = 2
        borderPath.lineCapStyle = .square
        borderPath.stroke()
    }

    
    /// Update overlay with given `CropRect`
    func updateCropRect(rect: CropRect) {
        cropRect = rect
        updateOverlay()
    }

    private func updateOverlay() {
            let width = frame.width * cropRect.scaleWidth
            let height = frame.height * cropRect.scaleHeight
            let x = frame.midX * (1 + cropRect.offsetX) - width / 2
            let y = frame.midY * (1 + cropRect.offsetY) - height / 2
            borderRect = CGRect(x: x, y: y, width: width, height: height)
            cornerLinelength = width * 0.10
            setNeedsDisplay()
    }
}
