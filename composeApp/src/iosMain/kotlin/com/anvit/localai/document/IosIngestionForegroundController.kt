package com.anvit.localai.document

import platform.UIKit.UIApplication
import platform.UIKit.UIBackgroundTaskIdentifier
import platform.UIKit.UIBackgroundTaskInvalid
import platform.UserNotifications.UNAuthorizationStatusAuthorized
import platform.UserNotifications.UNMutableNotificationContent
import platform.UserNotifications.UNNotificationRequest
import platform.UserNotifications.UNNotificationSound
import platform.UserNotifications.UNUserNotificationCenter

class IosIngestionForegroundController : IngestionForegroundController {

    private var bgTaskId: UIBackgroundTaskIdentifier = UIBackgroundTaskInvalid
    private var lastNotifiedMilestone = 0

    override fun start(initialStatus: String) {
        lastNotifiedMilestone = 0
        if (bgTaskId == UIBackgroundTaskInvalid) {
            bgTaskId = UIApplication.sharedApplication.beginBackgroundTaskWithExpirationHandler {
                // Time limit reached — release the token; ingestion coroutine may be suspended
                endBackgroundTask()
            }
        }
        showNotification("Indexing Document", initialStatus, "ingestion_start")
    }

    override fun update(fraction: Float, status: String) {
        val percent = (fraction * 100).toInt()
        val milestone = (percent / 25) * 25
        if (milestone > lastNotifiedMilestone && milestone < 100) {
            lastNotifiedMilestone = milestone
            showNotification("Indexing — $milestone%", status, "ingestion_progress")
        }
    }

    override fun stop() {
        showNotification("Indexing Complete", "Document is ready to search", "ingestion_done")
        endBackgroundTask()
        lastNotifiedMilestone = 0
    }

    private fun endBackgroundTask() {
        if (bgTaskId != UIBackgroundTaskInvalid) {
            UIApplication.sharedApplication.endBackgroundTask(bgTaskId)
            bgTaskId = UIBackgroundTaskInvalid
        }
    }

    private fun showNotification(title: String, body: String, identifier: String) {
        val center = UNUserNotificationCenter.currentNotificationCenter()
        center.getNotificationSettingsWithCompletionHandler { settings ->
            if (settings?.authorizationStatus == UNAuthorizationStatusAuthorized) {
                val content = UNMutableNotificationContent()
                content.setTitle(title)
                content.setBody(body)
                content.setSound(UNNotificationSound.defaultSound())
                val request = UNNotificationRequest.requestWithIdentifier(identifier, content, null)
                center.addNotificationRequest(request) { _ -> }
            }
        }
    }
}
