package com.darkmentor.bumblefixture

import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothClass
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import androidx.core.content.ContextCompat
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assume.assumeTrue
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

/**
 * Filtered BR/EDR inquiry harness for BumbleFixtureT13+ tests. Scans for one specific device
 * by *local name* (rather than BD_ADDR — the bumble fixture's BR/EDR address is the dongle's
 * hardware-assigned public address, which would be brittle to hardcode in the test).
 *
 * Why a separate helper from [BleFixtureScanner]: the two radios use different Android APIs
 * with non-overlapping shapes — BR/EDR is broadcast-receiver-driven against
 * [BluetoothAdapter.startDiscovery], the LE scanner is callback-driven against
 * [android.bluetooth.le.BluetoothLeScanner]. Sharing a single helper would require an
 * abstraction that obscures both call sites.
 *
 * The inquiry receiver matches what production [com.darkmentor.data.helpers.BrEdrDiscoveryHelper]
 * does, deliberately: this test must exercise the same broadcast plumbing the app relies on, so
 * regressions in the BroadcastReceiver registration / extras-extraction / leaked-LE-scan
 * filtering all show up here too. The test is therefore not "DM BT works" but "the BR/EDR
 * inquiry path exists and surfaces our fixture's name + CoD" — close enough to a true E2E
 * pin without spinning up the full Connect All loop.
 */
internal data class BrEdrInquiryHit(
    val address: String,
    val name: String?,
    val deviceType: Int,
    val deviceClass: Int?,
    val majorDeviceClass: Int?,
)

internal object BrEdrFixtureScanner {

    /**
     * @param expectedName   bumble's `Device.name` value, e.g. "DMBT-T13"
     * @param timeoutMs      how long to wait before giving up. BR/EDR inquiry takes ~10.24s by
     *                       spec; budget at least one full inquiry window plus a safety margin.
     *                       The default 25s also tolerates one STARTED-watchdog backoff cycle
     *                       (the BLU View 5's known leaked-LE-scan recovery path).
     * @return one [BrEdrInquiryHit] for the first matching device seen; never null on success.
     * @throws AssertionError when nothing matches in [timeoutMs]
     */
    @SuppressLint("MissingPermission")
    fun awaitNamedDevice(
        expectedName: String,
        timeoutMs: Long = DEFAULT_TIMEOUT_MS,
    ): BrEdrInquiryHit {
        val context: Context = InstrumentationRegistry.getInstrumentation().targetContext
        val adapter = adapter(context)
        assumeTrue("Bluetooth adapter not available on this device", adapter != null)
        assumeTrue("Bluetooth is off — enable it before running fixture tests", adapter!!.isEnabled)
        // Cancel any lingering inquiry from a previous test or app run; production
        // BrEdrDiscoveryHelper does the same defensively in cancel(). Without it,
        // startDiscovery() can no-op for ~12s waiting for the previous one to time out.
        runCatching { adapter.cancelDiscovery() }

        val latch = CountDownLatch(1)
        val seen = AtomicReference<BrEdrInquiryHit?>(null)

        val receiver = object : BroadcastReceiver() {
            @SuppressLint("MissingPermission")
            override fun onReceive(c: Context, intent: Intent) {
                if (intent.action != BluetoothDevice.ACTION_FOUND) return
                val device: BluetoothDevice = intent.getParcelableExtra(
                    BluetoothDevice.EXTRA_DEVICE,
                    BluetoothDevice::class.java,
                ) ?: return
                // Inquiry can fire ACTION_FOUND before the Extended Inquiry Response carrying
                // the local name arrives. The name then shows up via EXTRA_NAME on a later
                // broadcast or by querying device.name directly. Read both sources, prefer
                // EXTRA_NAME if present.
                val name = intent.getStringExtra(BluetoothDevice.EXTRA_NAME) ?: device.name
                if (name != expectedName) return

                val cls: BluetoothClass? = intent.getParcelableExtra(
                    BluetoothDevice.EXTRA_CLASS,
                    BluetoothClass::class.java,
                ) ?: device.bluetoothClass
                val hit = BrEdrInquiryHit(
                    address = device.address,
                    name = name,
                    deviceType = device.type,
                    deviceClass = cls?.deviceClass,
                    majorDeviceClass = cls?.majorDeviceClass,
                )
                if (seen.compareAndSet(null, hit)) {
                    latch.countDown()
                }
            }
        }

        // ACTION_FOUND is a system-protected broadcast (only the system process can send it).
        // RECEIVER_EXPORTED is the right flag for that — see the matching note in production
        // [BrEdrDiscoveryHelper] for the OEM-Android quirk that requires it.
        val filter = IntentFilter(BluetoothDevice.ACTION_FOUND)
        ContextCompat.registerReceiver(context, receiver, filter, ContextCompat.RECEIVER_EXPORTED)
        try {
            val started = adapter.startDiscovery()
            assumeTrue(
                "BluetoothAdapter.startDiscovery() returned false — likely a permission issue " +
                        "or the system is in a state that rejects new inquiries",
                started,
            )
            val arrived = latch.await(timeoutMs, TimeUnit.MILLISECONDS)
            if (!arrived) {
                throw AssertionError(
                    "BR/EDR inquiry did not surface a device named '$expectedName' within ${timeoutMs}ms. " +
                            "Check that `python tests/bumble-fixtures/run_fixture.py T13` is running on the host, " +
                            "the Realtek dongle is in BR/EDR-discoverable mode (the fixture log will say so), and " +
                            "the BLU View 5 isn't currently in its leaked-LE-scan recovery cycle.",
                )
            }
            return seen.get()!!
        } finally {
            runCatching { adapter.cancelDiscovery() }
            runCatching { context.unregisterReceiver(receiver) }
        }
    }

    private fun adapter(context: Context): BluetoothAdapter? =
        context.getSystemService(BluetoothManager::class.java)?.adapter

    private const val DEFAULT_TIMEOUT_MS: Long = 25_000
}
