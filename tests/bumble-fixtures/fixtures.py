"""Canonical fixture definitions for DM BT BLE-capture regression tests.

Each fixture pairs a bumble advertiser configuration (used by
[run_fixture.py]) with the parser invariants asserted by the matching
on-device test class in
`app/src/androidTest/java/f/cking/software/bumblefixture/BumbleFixtureTNN…`.

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
}


def get(fixture_id: str) -> dict:
    if fixture_id not in FIXTURES:
        raise SystemExit(
            f"Unknown fixture {fixture_id!r}. Known: {sorted(FIXTURES.keys())}"
        )
    return FIXTURES[fixture_id]
