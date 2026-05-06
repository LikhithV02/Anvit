package com.anvit.localai.ui.components

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.usePinned
import platform.Foundation.NSURL
import platform.Foundation.NSData
import platform.Foundation.dataWithContentsOfURL
import platform.UIKit.UIApplication
import platform.UIKit.UIDocumentPickerDelegateProtocol
import platform.UIKit.UIDocumentPickerViewController
import platform.UIKit.UIViewController
import platform.UIKit.UIWindow
import platform.UIKit.UIWindowScene
import platform.UniformTypeIdentifiers.UTType
import platform.darwin.NSObject
import platform.posix.memcpy

@OptIn(ExperimentalForeignApi::class)
@Composable
actual fun rememberPdfPicker(onResult: (fileName: String, bytes: ByteArray) -> Unit): () -> Unit {
    val cb = remember { onResult }
    return remember {
        {
            val delegate = DocumentPickerDelegate(cb)
            delegateRetainSet.add(delegate)

            val utTypes = listOfNotNull(
                UTType.typeWithIdentifier("com.adobe.pdf"),
                UTType.typeWithIdentifier("org.openxmlformats.wordprocessingml.document")
            )
            val picker = UIDocumentPickerViewController(
                forOpeningContentTypes = utTypes,
                asCopy = true
            )
            picker.setDelegate(delegate)
            picker.allowsMultipleSelection = false

            findRootViewController()
                ?.presentViewController(picker, animated = true, completion = null)
        }
    }
}

// keyWindow is deprecated iOS 13+ in scene-based (Compose MP) apps and returns nil on
// the simulator. Walk connectedScenes instead to find the key window's root controller.
private fun findRootViewController(): UIViewController? {
    UIApplication.sharedApplication.connectedScenes
        .filterIsInstance<UIWindowScene>()
        .flatMap { it.windows as List<UIWindow> }
        .firstOrNull { it.isKeyWindow() }
        ?.rootViewController
        ?.let { return it }
    // Fallback for single-scene apps / older iOS
    return UIApplication.sharedApplication.keyWindow?.rootViewController
}

private val delegateRetainSet = mutableSetOf<DocumentPickerDelegate>()

@OptIn(ExperimentalForeignApi::class)
private class DocumentPickerDelegate(
    private val onResult: (String, ByteArray) -> Unit
) : NSObject(), UIDocumentPickerDelegateProtocol {

    override fun documentPicker(
        controller: UIDocumentPickerViewController,
        didPickDocumentsAtURLs: List<*>
    ) {
        delegateRetainSet.remove(this)
        val url = didPickDocumentsAtURLs.firstOrNull() as? NSURL ?: return
        val fileName = url.lastPathComponent ?: "document"
        val data = NSData.dataWithContentsOfURL(url) ?: return
        val length = data.length.toInt()
        if (length == 0) return
        val bytes = ByteArray(length)
        bytes.usePinned { pinned ->
            memcpy(pinned.addressOf(0), data.bytes, data.length)
        }
        onResult(fileName, bytes)
    }

    override fun documentPickerWasCancelled(controller: UIDocumentPickerViewController) {
        delegateRetainSet.remove(this)
    }

}
