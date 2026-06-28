import Foundation
import PDFKit

// Row: a group of word segments that share the same Y position (within 3pt tolerance)
private struct LayoutRow {
    var y: CGFloat
    var segs: [(x: CGFloat, w: CGFloat, t: String)]
}

private func extractLayout(from doc: PDFDocument) -> String {
    var pages: [[String: Any]] = []

    for pageIdx in 0..<doc.pageCount {
        guard let page = doc.page(at: pageIdx) else { continue }
        let pageHeight = page.bounds(for: .mediaBox).height

        // Enumerate every word on the page using PDFSelection
        guard let fullSel = page.selection(for: page.bounds(for: .mediaBox)) else { continue }
        let text = fullSel.string ?? ""
        if text.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty { continue }

        var rows: [LayoutRow] = []

        // Use word-by-word selection: split words and get bounds for each
        let words = text.components(separatedBy: .whitespacesAndNewlines)
            .filter { !$0.trimmingCharacters(in: .whitespaces).isEmpty }

        // Build a word-level selection for each word token found in the page
        // PDFPage.selectionForRange can be expensive; use selectionForWord instead
        // by iterating character positions via PDFPage.selection(for: NSRange)
        let nsText = text as NSString
        var searchRange = NSRange(location: 0, length: nsText.length)

        for word in words {
            let wordRange = nsText.range(of: word, options: [], range: searchRange)
            guard wordRange.location != NSNotFound else { continue }

            // Advance search past this word
            let nextLoc = wordRange.location + wordRange.length
            searchRange = NSRange(location: nextLoc, length: nsText.length - nextLoc)

            guard let sel = page.selection(for: wordRange) else { continue }
            let rect = sel.bounds(for: page)
            if rect.isEmpty || rect.width < 1 { continue }

            // PDFKit Y is from bottom; convert to top-down for consistency
            let yTopDown = pageHeight - rect.maxY
            let x = rect.minX
            let w = rect.width

            // Find or create a row within 3pt Y tolerance
            if let idx = rows.lastIndex(where: { abs($0.y - yTopDown) <= 3.0 }) {
                rows[idx].segs.append((x: x, w: w, t: word))
            } else {
                rows.append(LayoutRow(y: yTopDown, segs: [(x: x, w: w, t: word)]))
            }
        }

        // Sort rows top-to-bottom, segments left-to-right within each row
        rows.sort { $0.y < $1.y }
        for i in 0..<rows.count {
            rows[i].segs.sort { $0.x < $1.x }
        }

        let rowDicts: [[String: Any]] = rows.map { row in
            let segDicts: [[String: Any]] = row.segs.map { seg in
                ["x": round(seg.x * 10) / 10,
                 "w": round(seg.w * 10) / 10,
                 "t": seg.t]
            }
            return ["y": round(row.y * 10) / 10, "segs": segDicts]
        }

        pages.append(["page": pageIdx, "rows": rowDicts])
    }

    guard let data = try? JSONSerialization.data(withJSONObject: pages, options: []),
          let json = String(data: data, encoding: .utf8) else {
        return "[]"
    }
    return json
}

@_cdecl("pdf_extract_layout")
public func pdf_extract_layout(
    _ pdfBytes: UnsafePointer<UInt8>?,
    _ byteCount: Int32,
    _ userData: UnsafeMutableRawPointer?,
    _ callback: (@convention(c) (UnsafeMutableRawPointer?, UnsafePointer<CChar>?) -> Void)?
) {
    guard let bytesPtr = pdfBytes, byteCount > 0, let cb = callback else {
        callback?(userData, "[]")
        return
    }

    let data = Data(bytes: bytesPtr, count: Int(byteCount))

    Task.detached(priority: .userInitiated) {
        guard let doc = PDFDocument(data: data) else {
            "[]".withCString { cb(userData, $0) }
            return
        }
        let json = extractLayout(from: doc)
        json.withCString { cb(userData, $0) }
    }
}
