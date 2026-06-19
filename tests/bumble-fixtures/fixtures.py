"""Canonical fixture definitions for DM BT BLE-capture regression tests.

Each fixture pairs a bumble advertiser configuration (used by
[run_fixture.py]) with the parser invariants asserted by the matching
on-device test class in
`app/src/androidTest/java/com/darkmentor/bumblefixture/BumbleFixtureTNN…`.

When a fixture is added/changed here, the matching Kotlin test must be
updated in lockstep — they share an MAC + adv-bytes contract, not code.

Address scheme: every fixture uses `F0:F1:F2:F3:F4:NN` where NN is the
test number in hex. The 0xF0 prefix has top 2 bits = 11, which the
Bluetooth Core Spec assigns to *random static* addresses — that's what
DM BT's `inferBdaddrRand` heuristic should classify them as. Distinct
last-byte per fixture means the phone's recent-device cache won't merge
two consecutive fixture runs into a single row.
"""

# AD types — Bluetooth Core Specification Supplement, Part A, Section 1.
AD_FLAGS = 0x01
AD_COMPLETE_16_UUIDS = 0x03
AD_COMPLETE_LOCAL_NAME = 0x09

# Flags value: LE General Discoverable Mode + BR/EDR Not Supported.
FLAGS_LE_GEN_DISC_BR_EDR_NOT_SUPP = 0x06


def _record(ad_type: int, value: bytes) -> bytes:
    """Encode one AD record: 1 byte length || 1 byte type || value."""
    if len(value) > 254:
        raise ValueError(f"AD record value too long: {len(value)} > 254")
    return bytes([1 + len(value), ad_type]) + value


def _flags(value: int) -> bytes:
    return _record(AD_FLAGS, bytes([value & 0xFF]))


def _name(name: str) -> bytes:
    return _record(AD_COMPLETE_LOCAL_NAME, name.encode("utf-8"))


def _uuid16_list_complete(uuids: list[int]) -> bytes:
    """Encode a Complete List of 16-bit Service Class UUIDs (AD type 0x03)."""
    payload = b"".join(u.to_bytes(2, "little") for u in uuids)
    return _record(AD_COMPLETE_16_UUIDS, payload)


FIXTURES = {
    "T01": {
        "description": "Minimal connectable advertiser: Flags + Complete Local Name",
        "address": "F0:F1:F2:F3:F4:01",
        "local_name": "DMBT-T01",
        "connectable": True,
        "adv_data": _flags(FLAGS_LE_GEN_DISC_BR_EDR_NOT_SUPP) + _name("DMBT-T01"),
        "scan_response_data": None,
        # Mirrored expectations for the Kotlin assertion side. Keep these
        # copy-pasted in the matching .kt — Python isn't visible to the
        # JVM so duplication is the price of a single source of intent.
        "expected": {
            "name": "DMBT-T01",
            "is_connectable": True,
            "is_legacy": True,
            "service_uuids": [],
        },
    },
    "T02": {
        "description": "Complete list of 16-bit Service UUIDs (HRS + BAS + HTS)",
        "address": "F0:F1:F2:F3:F4:02",
        "local_name": "DMBT-T02",
        "connectable": True,
        "adv_data": (
            _flags(FLAGS_LE_GEN_DISC_BR_EDR_NOT_SUPP)
            + _name("DMBT-T02")
            + _uuid16_list_complete([0x180D, 0x180F, 0x1809])
        ),
        "scan_response_data": None,
        "expected": {
            "name": "DMBT-T02",
            "is_connectable": True,
            "is_legacy": True,
            "service_uuids": [
                # Canonical 128-bit form Android exposes via
                # ScanRecord.getServiceUuids() for SIG-allocated 16-bit
                # UUIDs (BLUETOOTH_BASE_UUID).
                "0000180d-0000-1000-8000-00805f9b34fb",
                "0000180f-0000-1000-8000-00805f9b34fb",
                "00001809-0000-1000-8000-00805f9b34fb",
            ],
        },
    },
    # ------------------------------------------------------------------
    # BR/EDR fixtures
    # ------------------------------------------------------------------
    # `kind = "classic"` switches the harness from LE advertising to
    # BR/EDR discoverable + connectable. There is no `adv_data` (LE-only
    # concept); the fixture instead specifies a Class of Device and
    # relies on the controller's hardware-assigned public BD_ADDR.
    #
    # The matching Kotlin test filters its scan by *local name* rather
    # than BD_ADDR so it stays portable across dongles — the bumble
    # `Device.with_hci(name=..., address=...)` argument we pass is
    # ignored for the BR/EDR public address (the controller assigns
    # that), but `name` controls the value bumble writes via
    # `HCI_Write_Local_Name_Command`.
    "T13": {
        "kind": "classic",
        "description": "BR/EDR-discoverable device with known local name + Class of Device (Phone / Smart phone)",
        # bumble's Device.with_hci still requires *some* Address argument
        # but it's only used for LE; BR/EDR inquiry responders to the
        # phone with the controller's public BD_ADDR. We pass a
        # placeholder so the constructor doesn't choke; the Kotlin
        # assertion side filters by name instead of MAC.
        "address": "F0:F1:F2:F3:F4:13",
        "local_name": "DMBT-T13",
        # 0x00020C = Major device class 0x02 (Phone, bits 12..8 = 2) +
        # minor device class 0x03 (Smart phone, bits 7..2 = 3) + format
        # 00 + no service classes. Decoded:
        #   bits 23..13 (service classes)   = 0       — none set
        #   bits 12..8  (major device class)= 0b00010 = 2  → Phone
        #   bits 7..2   (minor device class)= 0b000011 = 3 → Smart phone
        #   bits 1..0   (format)            = 00
        # Matches Android's BluetoothClass.Device.PHONE_SMART = 0x020C
        # (which is Android's `getDeviceClass()` view = bits 12..2),
        # so the test can pin `getMajorDeviceClass() == PHONE` (0x0200)
        # without depending on minor-class detail. Distinct from any
        # default an empty-CoD controller might emit (0 = Miscellaneous).
        "class_of_device": 0x00020C,
        "expected": {
            "name": "DMBT-T13",
            # Android's BluetoothDevice.DEVICE_TYPE_CLASSIC. Some
            # dual-mode dongles report DEVICE_TYPE_DUAL=3 even though
            # the LE radio isn't advertising — the test accepts either.
            "device_type_classic_or_dual": True,
            "major_device_class": 0x0200,  # PHONE
        },
    },
}


def get(fixture_id: str) -> dict:
    if fixture_id not in FIXTURES:
        raise SystemExit(
            f"Unknown fixture {fixture_id!r}. Known: {sorted(FIXTURES.keys())}"
        )
    return FIXTURES[fixture_id]
