package io.github.trebronwayne.droplink.core

import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.qrcode.QRCodeWriter
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel

/** QR code generation (ZXing core, no network needed). */
object Qr {
    /** Returns the module grid: true = dark. Square, without the quiet zone. */
    fun modules(text: String): Array<BooleanArray> {
        val hints = mapOf(EncodeHintType.MARGIN to 0, EncodeHintType.ERROR_CORRECTION to ErrorCorrectionLevel.M)
        val m = QRCodeWriter().encode(text, BarcodeFormat.QR_CODE, 0, 0, hints)
        return Array(m.height) { y -> BooleanArray(m.width) { x -> m.get(x, y) } }
    }

    /** A self-contained SVG with a 4-module quiet zone, for the web client. */
    fun svg(text: String): String {
        val grid = modules(text)
        val n = grid.size
        val size = n + 8
        val path = StringBuilder()
        for (y in 0 until n) for (x in 0 until n) if (grid[y][x]) path.append("M${x + 4} ${y + 4}h1v1h-1z")
        return "<svg xmlns=\"http://www.w3.org/2000/svg\" viewBox=\"0 0 $size $size\" shape-rendering=\"crispEdges\">" +
            "<rect width=\"$size\" height=\"$size\" fill=\"#fff\"/><path d=\"$path\" fill=\"#000\"/></svg>"
    }
}
