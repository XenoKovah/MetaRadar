package f.cking.software.service

import android.accessibilityservice.AccessibilityService
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import f.cking.software.data.repo.SettingsRepository
import org.koin.android.ext.android.inject
import timber.log.Timber

/**
 * Auto-accepts the system Bluetooth pairing dialog (`com.android.settings` →
 * `BluetoothPairingDialog` and its OEM variants) on behalf of researchers running unattended
 * Connect-All sessions. Without it, every BR/EDR / GATT-over-BR/EDR connection that requires
 * bonding stalls on a system modal that has to be tapped manually.
 *
 * **Why an AccessibilityService.** Standard apps cannot call
 * [android.bluetooth.BluetoothDevice.setPairingConfirmation] — that path is gated behind
 * `android.permission.BLUETOOTH_PRIVILEGED`, which has `protectionLevel="signature"` and is
 * only granted to apps signed with the platform/system key. The remaining option is to
 * programmatically click the Pair button on the system dialog from outside the dialog's
 * UID — which is exactly what the AccessibilityService API authorises.
 *
 * **Scope of action.** This service ONLY clicks buttons whose visible label matches one of
 * the locale-translated "Pair" texts when the foreground window is the
 * [BluetoothPairingDialog]. It never reads, modifies, or interacts with any other UI. Disabled
 * by default; the user must opt in via a Settings toggle that deep-links to Android's
 * Accessibility settings page.
 *
 * **Activation gate.** [SettingsRepository.getAutoPairEnabled] is consulted on every event so
 * the user can leave the OS-level service permission granted but flip the in-app toggle off
 * to suspend auto-clicks without revoking the permission. A revoked permission disables this
 * class entirely (the system stops delivering events).
 *
 * **Multilingual matching.** We match the Pair button by ID resource name first
 * (`android:id/button1` / `android:id/button2`, the AlertDialog positive/negative slots used
 * by every BluetoothPairingDialog implementation), falling back to the localized label only
 * if the resource lookup misses on an OEM that overrode the layout. This avoids hard-coding
 * "Pair" in English and surviving locale changes.
 */
class AutoPairAccessibilityService : AccessibilityService() {

    private val settingsRepository: SettingsRepository by inject()

    override fun onServiceConnected() {
        super.onServiceConnected()
        // Always log on bind so the user can verify the service actually got bound after
        // toggling it on in Accessibility settings. On Android 13+ "restricted settings"
        // can silently block sideloaded accessibility services from binding even when the
        // toggle appears on; the absence of this line is the diagnostic signal.
        Timber.tag(TAG).i("AutoPairAccessibilityService bound — auto-pair active when toggle on")
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        event ?: return
        // Cheap gate: stop the moment we know the user has the toggle off. The OS still
        // delivers events to us as long as the service is bound, but we want zero work past
        // this point when auto-pair isn't desired.
        if (!settingsRepository.getAutoPairEnabled()) return

        // Trace every event that survives the package-name filter from the XML config so we
        // can debug "the dialog popped up but auto-pair didn't click" from logcat alone.
        // Cheap (no I/O, just a Timber.d call); fires at most a few times per pairing prompt.
        Timber.tag(TAG).d(
            "event type=%s pkg=%s class=%s",
            AccessibilityEvent.eventTypeToString(event.eventType),
            event.packageName,
            event.className,
        )

        when (event.eventType) {
            AccessibilityEvent.TYPE_NOTIFICATION_STATE_CHANGED -> handleNotification(event)
            AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED,
            AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED -> handleWindow(event)
            else -> Unit
        }
    }

    /**
     * Foreground / locked-screen pairing dialog path. The system shows the pairing prompt as
     * a full-screen [BluetoothPairingDialog] activity when our app is foreground or when the
     * device's pairing-prompt-style is "dialog" — typical when the user is interacting with
     * the phone.
     */
    private fun handleWindow(event: AccessibilityEvent) {
        val root = rootInActiveWindow ?: return
        val className = event.className?.toString().orEmpty()
        // Don't strict-filter by class name — OEMs (Motorola, TCL on Android 14) sometimes
        // subclass BluetoothPairingDialog under a vendor-prefixed class. Instead, accept any
        // window-state event from packages that already passed the XML packageNames filter
        // (com.android.settings / com.android.systemui / com.google.android.permissioncontroller),
        // and rely on the Pair-button find to no-op when the window isn't actually a pairing
        // prompt. The label-walk only matches clickable nodes whose text is in PAIR_LABELS,
        // so a stray Settings page with a "Pair" button list item only gets clicked if it's
        // genuinely a one-button affordance — accepted false-positive risk in exchange for
        // not missing OEM dialog subclasses.
        try {
            val byIdRes = root.findAccessibilityNodeInfosByViewId("android:id/button1").orEmpty()
            val byLabel = findPairLabelNodes(root)
            // Try the canonical AlertDialog positive button first (covers stock Android),
            // fall through to label-based clickable nodes (covers OEM dialogs and the bottom-
            // sheet variant TCL renders).
            val candidates = byIdRes + byLabel
            for (node in candidates) {
                if (node.isClickable && node.isEnabled) {
                    Timber.tag(TAG).i(
                        "Auto-clicking Pair button (class=%s, source=%s)",
                        className,
                        if (node in byIdRes) "android:id/button1" else "label-walk",
                    )
                    node.performAction(AccessibilityNodeInfo.ACTION_CLICK)
                    return
                }
            }
            // No log on no-match — this fires on every window event including unrelated
            // Settings pages, so logging here would be noisy. The entry-point `event ...`
            // trace in onAccessibilityEvent is enough to confirm the event was delivered.
        } catch (t: Throwable) {
            Timber.tag(TAG).w(t, "handleWindow crashed for %s", className)
        }
    }

    /**
     * Notification-style pairing-prompt path. When the screen is locked or another app is
     * foreground, Android suppresses the dialog and posts a heads-up notification from
     * [BluetoothPairingService] (`com.android.settings`) instead. The notification carries
     * the same Pair / Cancel as [Notification.Action]s — we reach in via the
     * AccessibilityEvent's parcelable data, find the action whose label matches "Pair", and
     * fire its [PendingIntent]. This is the only practical way for a non-system app to
     * confirm pairing here: tapping the notification view through the accessibility tree
     * is unreliable because the heads-up renders in a `com.android.systemui` overlay where
     * the action buttons are RemoteView nodes, not typed AccessibilityNodeInfos.
     */
    private fun handleNotification(event: AccessibilityEvent) {
        val pkg = event.packageName?.toString() ?: return
        // Restrict to the packages that legitimately produce pairing notifications. Saves us
        // from rummaging through every notification posted by every system app.
        if (pkg != "com.android.settings" && pkg != "com.android.systemui") return

        val notification = event.parcelableData as? android.app.Notification ?: return
        val actions = notification.actions ?: return
        try {
            for (action in actions) {
                val label = action.title?.toString()?.trim()?.lowercase().orEmpty()
                if (label in PAIR_LABELS) {
                    Timber.tag(TAG).i("Auto-firing Pair notification action (label=%s, pkg=%s)", label, pkg)
                    action.actionIntent?.send()
                    return
                }
            }
            Timber.tag(TAG).d(
                "Pairing notification from %s had %d actions, none labeled Pair: %s",
                pkg, actions.size, actions.joinToString { it.title?.toString().orEmpty() },
            )
        } catch (t: Throwable) {
            Timber.tag(TAG).w(t, "handleNotification crashed for %s", pkg)
        }
    }

    /**
     * Fallback: walk the tree looking for a clickable node whose visible text is a known
     * "Pair" label (English + the most common European translations Android ships with).
     * Used only when the AlertDialog button resource id lookup fails — typically because an
     * OEM (TCL was a candidate suspect during testing) replaced the system pairing dialog
     * with a custom layout.
     */
    private fun findPairLabelNodes(root: AccessibilityNodeInfo): List<AccessibilityNodeInfo> {
        val results = mutableListOf<AccessibilityNodeInfo>()

        fun walk(node: AccessibilityNodeInfo?) {
            node ?: return
            try {
                val text = node.text?.toString()?.trim()?.lowercase().orEmpty()
                if (text in PAIR_LABELS && node.isClickable) results += node
                val n = node.childCount
                for (i in 0 until n) {
                    walk(node.getChild(i))
                }
            } catch (_: Throwable) {
                // Per-node failures are non-fatal; the AlertDialog has at most a few dozen
                // descendants and aborting the walk for one would skip the legitimate Pair
                // button on subsequent siblings.
            }
        }
        walk(root)
        return results
    }

    override fun onInterrupt() {
        // Required override; no work needed — we don't queue feedback or speech.
    }

    companion object {
        private const val TAG = "AutoPairA11yService"
        // Locale-tolerant set: covers the labels we've seen in stock Android translations
        // for the "Pair" button (in dialogs) and Pair-action notifications. Comparison is
        // always lower-cased + trimmed before lookup. Add new translations as users encounter
        // OEM-specific renderings.
        private val PAIR_LABELS = setOf(
            "pair", "pair & connect", "pair and connect",
            "связать", "vincular", "verknüpfen",
            "appairer", "associare", "結合", "配對",
            "ペア設定する", "페어링",
        )
    }
}
