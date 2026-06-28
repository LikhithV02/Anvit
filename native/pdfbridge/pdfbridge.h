#ifndef PDFBRIDGE_H
#define PDFBRIDGE_H

#include <stdint.h>
#include <stddef.h>

#ifdef __cplusplus
extern "C" {
#endif

/**
 * Called with the extracted layout as a JSON string.
 * The json pointer is only valid during the callback; copy it if needed.
 *
 * JSON format:
 * [
 *   {
 *     "page": 0,
 *     "rows": [
 *       {
 *         "y": 700.5,
 *         "segs": [
 *           {"x": 72.0, "w": 42.3, "t": "word"},
 *           ...
 *         ]
 *       },
 *       ...
 *     ]
 *   },
 *   ...
 * ]
 *
 * Coordinates are in PDF points (origin bottom-left, same as PDFKit page bounds).
 * Rows are grouped by Y-position with a 3pt tolerance; segments within a row
 * are ordered left-to-right by X.
 */
typedef void (*PDFLayoutCallback)(void* user_data, const char* json);

/**
 * Extract word-level bounding boxes from a PDF.
 * pdf_bytes must remain valid until the callback fires.
 * The callback is called on a background thread.
 */
void pdf_extract_layout(
    const uint8_t* pdf_bytes,
    int32_t byte_count,
    void* user_data,
    PDFLayoutCallback callback
);

#ifdef __cplusplus
}
#endif

#endif /* PDFBRIDGE_H */
