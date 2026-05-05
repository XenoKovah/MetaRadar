package f.cking.software.bumblefixture

import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothManager
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanFilter
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.Context
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assume.assumeTrue
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

/**
 * Scans for one specific BLE device by MAC and returns the first
 * [ScanResult] for it. Shared by every BumbleFixture instrumented test.
 *
 * Why use the framework scanner directly instead of [f.cking.software.data.helpers.BleScannerHelper]?
 * The helper has a deep dependency tree (Koin DI, settings repo, BTIDES
 * repo, vendor identifier, power-mode helper). Wiring all of that up in
 * an instrumented test is much more code than the assertion surface
 * justifies. The contract we actually want to pin is "*the radio
 * delivered the bytes the fixture sent, addressed to the fixture's
 * BD_ADDR, with the right connectable/legacy flags*" — that's purely a
 * function of the Android BLE framework + Realtek dongle, and is what
 * regresses when something downstream changes about how DM BT consumes
 * scan results. Parser-level invariants on those raw bytes are tested
 * separately (and faster) by JVM unit tests over hardcoded byte arrays.
 */
internal object BleFixtureScanner {

    /**
     * @param bdAddr      target BD_ADDR (uppercase, colon-separated)
     * @param timeoutMs   how long to wait before giving up
     * @return the first [ScanResult] seen for [bdAddr]
     * @throws AssertionError when nothing matching arrives in [timeoutMs]
     *
     * Skips the test (via JUnit `Assume`) if Bluetooth is off or the
     * device is missing a BLE radio entirely — those mean the test
     * harness, not the app, is broken; failing would just spam red
     * across CI without telling anyone anything new.
     */
    @SuppressLint("MissingPermission")
    fun awaitFixture(bdAddr: String, timeoutMs: Long = DEFAULT_TIMEOUT_MS): ScanResult {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val adapter = bluetoothAdapter(context)
        assumeTrue("Bluetooth adapter not available on this device", adapter != null)
        assumeTrue("Bluetooth is off — enable it before running fixture tests", adapter!!.isEnabled)
        val scanner = adapter.bluetoothLeScanner
        assumeTrue("BluetoothLeScanner unavailable", scanner != null)

        val latch = CountDownLatch(1)
        val seen = AtomicReference<ScanResult?>(null)
        val callback = object : ScanCallback() {
            override fun onScanResult(callbackType: Int, result: ScanResult?) {
                if (result == null) return
                if (result.device?.address?.uppercase() == bdAddr.uppercase()) {
                    seen.compareAndSet(null, result)
                    latch.countDown()
                }
            }

            override fun onScanFailed(errorCode: Int) {
                throw AssertionError("BLE scan failed with errorCode=$errorCode")
            }
        }

        // SCAN_MODE_LOW_LATENCY matches what BleScannerHelper uses in production —
        // we want the same scan window cadence as the real app, not a debugging
        // back door that hides cadence-sensitive bugs.
        val settings = ScanSettings.Builder()
            .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
            .build()
        // Filter on MAC at the controller level. Cuts onScanResult traffic from
        // "every nearby BLE device" down to just our fixture, so the test isn't
        // racing against environmental noise on a busy desk.
        val filter = ScanFilter.Builder().setDeviceAddress(bdAddr).build()

        scanner.startScan(listOf(filter), settings, callback)
        try {
            val arrived = latch.await(timeoutMs, TimeUnit.MILLISECONDS)
            if (!arrived) {
                throw AssertionError(
                    "Fixture $bdAddr not seen within ${timeoutMs}ms. " +
                            "Is `python tests/bumble-fixtures/run_fixture.py …` running on the host?"
                )
            }
            return seen.get() ?: throw AssertionError("Latch released but no ScanResult captured")
        } finally {
            scanner.stopScan(callback)
        }
    }

    private fun bluetoothAdapter(context: Context): BluetoothAdapter? =
        context.getSystemService(BluetoothManager::class.java)?.adapter

    private const val DEFAULT_TIMEOUT_MS: Long = 15_000
}
