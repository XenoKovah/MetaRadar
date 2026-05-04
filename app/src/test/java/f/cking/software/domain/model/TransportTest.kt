package f.cking.software.domain.model

import junit.framework.TestCase.assertEquals
import org.junit.Test

class TransportTest {

    // ---- fromAndroidDeviceType: maps the raw android.bluetooth.BluetoothDevice.DEVICE_TYPE_*
    // constants without leaking those imports into the domain layer. Stable per the platform.

    @Test
    fun `device_type_classic maps to BREDR`() {
        // BluetoothDevice.DEVICE_TYPE_CLASSIC == 1
        assertEquals(Transport.BREDR, Transport.fromAndroidDeviceType(1))
    }

    @Test
    fun `device_type_le maps to LE`() {
        // BluetoothDevice.DEVICE_TYPE_LE == 2
        assertEquals(Transport.LE, Transport.fromAndroidDeviceType(2))
    }

    @Test
    fun `device_type_dual maps to DUAL`() {
        // BluetoothDevice.DEVICE_TYPE_DUAL == 3
        assertEquals(Transport.DUAL, Transport.fromAndroidDeviceType(3))
    }

    @Test
    fun `device_type_unknown and null map to UNKNOWN`() {
        // BluetoothDevice.DEVICE_TYPE_UNKNOWN == 0
        assertEquals(Transport.UNKNOWN, Transport.fromAndroidDeviceType(0))
        assertEquals(Transport.UNKNOWN, Transport.fromAndroidDeviceType(null))
        // Unrecognized future value defaults to UNKNOWN rather than throwing.
        assertEquals(Transport.UNKNOWN, Transport.fromAndroidDeviceType(99))
    }

    // ---- merge: combines a stored Transport with a freshly-observed one when the same address
    // is re-detected. UNKNOWN is treated as "no information" — anything specific overrides it.

    @Test
    fun `merge identical transports returns same`() {
        for (t in Transport.entries) {
            assertEquals(t, Transport.merge(t, t))
        }
    }

    @Test
    fun `merge UNKNOWN with anything yields the other`() {
        assertEquals(Transport.LE, Transport.merge(Transport.UNKNOWN, Transport.LE))
        assertEquals(Transport.LE, Transport.merge(Transport.LE, Transport.UNKNOWN))
        assertEquals(Transport.BREDR, Transport.merge(Transport.UNKNOWN, Transport.BREDR))
        assertEquals(Transport.BREDR, Transport.merge(Transport.BREDR, Transport.UNKNOWN))
        assertEquals(Transport.DUAL, Transport.merge(Transport.UNKNOWN, Transport.DUAL))
        assertEquals(Transport.DUAL, Transport.merge(Transport.DUAL, Transport.UNKNOWN))
    }

    @Test
    fun `merge LE with BREDR yields DUAL`() {
        assertEquals(Transport.DUAL, Transport.merge(Transport.LE, Transport.BREDR))
        assertEquals(Transport.DUAL, Transport.merge(Transport.BREDR, Transport.LE))
    }

    @Test
    fun `merge with DUAL stays DUAL`() {
        assertEquals(Transport.DUAL, Transport.merge(Transport.DUAL, Transport.LE))
        assertEquals(Transport.DUAL, Transport.merge(Transport.LE, Transport.DUAL))
        assertEquals(Transport.DUAL, Transport.merge(Transport.DUAL, Transport.BREDR))
        assertEquals(Transport.DUAL, Transport.merge(Transport.BREDR, Transport.DUAL))
    }

    // ---- Capability predicates

    @Test
    fun `supportsGattOverLe is true for LE and DUAL only`() {
        assertEquals(true, Transport.LE.supportsGattOverLe())
        assertEquals(true, Transport.DUAL.supportsGattOverLe())
        assertEquals(false, Transport.BREDR.supportsGattOverLe())
        assertEquals(false, Transport.UNKNOWN.supportsGattOverLe())
    }

    @Test
    fun `isBrEdrOnly is true only for BREDR`() {
        assertEquals(true, Transport.BREDR.isBrEdrOnly())
        assertEquals(false, Transport.LE.isBrEdrOnly())
        assertEquals(false, Transport.DUAL.isBrEdrOnly())
        assertEquals(false, Transport.UNKNOWN.isBrEdrOnly())
    }

    // ---- Ordinal stability — these ordinals back the persisted DeviceEntity.transport column;
    // reordering would corrupt the database. Pin them with explicit asserts.

    @Test
    fun `ordinals are stable for persistence`() {
        assertEquals(0, Transport.UNKNOWN.ordinal)
        assertEquals(1, Transport.LE.ordinal)
        assertEquals(2, Transport.BREDR.ordinal)
        assertEquals(3, Transport.DUAL.ordinal)
    }

    @Test
    fun `short labels match expected UI badge text`() {
        assertEquals("LE", Transport.LE.shortLabel())
        assertEquals("BR", Transport.BREDR.shortLabel())
        assertEquals("Dual", Transport.DUAL.shortLabel())
        assertEquals("", Transport.UNKNOWN.shortLabel())
    }
}
