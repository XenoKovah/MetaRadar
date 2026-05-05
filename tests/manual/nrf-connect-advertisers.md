# nRF Connect Advertiser Regression Suite for DM BT

A set of 12 BLE advertiser configurations that exercise every advertising-record
parser path in DM BT (`ParseBleAdRecord`, `BleScannerHelper`, `inferAdvType`,
iBeacon detection, BTIDES export). Each test below specifies:

- **Purpose** — what code path it covers and which past regressions it guards.
- **nRF Connect inputs** — exactly what to enter in the Advertiser tab.
- **Raw bytes** — the on-air AD bytes, useful for cross-checking the saved
  config and as a canonical fixture for any future automated harness.
- **Expected DM BT output** — what should appear in the device list and the
  device-details screen.

## How to use

> nRF Connect for Mobile (Nordic) does **not** have a public file-import
> format for advertiser configs. The recommended workflow is: enter each
> config once via the Advertiser tab, hit the floppy/save icon next to the
> name (`DMBT-Tnn-…`), then for each regression test run, just toggle each
> saved entry's "play" switch in turn.

### One-time setup on the test phone (Blu View 5)

1. Install nRF Connect for Mobile from the Play Store.
2. Open it → side menu → **Advertiser**.
3. For each test below: tap **+** → fill in **Display name** (use the test ID,
   e.g. `DMBT-T01-Name`) → set the radio toggles (Connectable / Scannable /
   Legacy) per the test → add each AD Record listed under "Advertising data"
   → if the test has a "Scan response data" section, switch to that tab and
   add those records → tap **OK** → tap the floppy icon to save.
4. Leave **Tx power** = `MEDIUM` and **Advertising mode** = `LOW_LATENCY`
   (≈100 ms) for all tests unless the test says otherwise.
5. Don't set a Max advertising events / timeout — let DM BT control the scan
   duration on its end.

### Running a regression pass

1. On the test phone, open nRF Connect → Advertiser → flip on the row for
   the test you're verifying. Leave it advertising.
2. On the **device under test** (separate phone running DM BT), open DM BT →
   Devices tab → tap **Scan** (FAB).
3. Wait for the scan window to finish, then tap the row whose name matches
   the test's `DMBT-Tnn-…` prefix.
4. Compare the Device Details screen against the test's "Expected DM BT
   output" section. RSSI/BD_ADDR will vary — ignore those.
5. (Optional) For BTIDES coverage: export to BTIDES JSON and grep the file
   for the test's expected `AdvType` and decoded fields.

When all 12 tests pass on a given DM BT build, BLE discovery + advertising
parsing are non-regressed end-to-end.

### Convention for raw-byte hex blocks below

Each AD record is `length || type || value` where `length` is the byte count
of `type + value`. Multi-byte values use little-endian order **except**
iBeacon UUIDs (big-endian per Apple spec). Bytes are shown space-separated
for readability.

---

## T01 — Minimal connectable advertiser with Complete Local Name

**Purpose.** Smoke test. Verifies `parseFlags` (AD type 0x01) and
`parseLocalName` (AD type 0x09), and that `inferAdvType` reports `ADV_IND`
for a connectable + legacy advertisement.

**nRF Connect settings.**
- Display name: `DMBT-T01-Name`
- Connectable: **ON**
- Scannable: ON
- Legacy: ON

**Advertising data.**

| Type | nRF Connect record | Value |
|---|---|---|
| 0x01 | Flags | `LE General Discoverable Mode`, `BR/EDR Not Supported` (= 0x06) |
| 0x09 | Complete Local Name | `DMBT-T01` |

**Raw bytes.** `02 01 06   09 09 44 4D 42 54 2D 54 30 31`

**Expected DM BT output.**
- Device list: row labelled `DMBT-T01`.
- Details → AD parser:
  - **Flags**: `0x06`
  - **Bits set**: `LE General Discoverable Mode, BR/EDR Not Supported`
  - **Name**: `DMBT-T01`
- BTIDES export: `AdvType` = `0` (`ADV_IND`).

---

## T02 — Complete list of 16-bit Service UUIDs

**Purpose.** Verifies `parseUuids16` with multiple UUIDs and confirms LE
byte ordering. Uses three SIG-allocated values so any future SIG-name
lookup also has something to chew on.

**nRF Connect settings.** Connectable ON, Scannable ON, Legacy ON.
Display name: `DMBT-T02-UUID16`.

**Advertising data.**

| Type | nRF Connect record | Value |
|---|---|---|
| 0x01 | Flags | `0x06` |
| 0x09 | Complete Local Name | `DMBT-T02` |
| 0x03 | Complete List of 16-bit UUIDs | `0x180D, 0x180F, 0x1809` |

**Raw bytes.**
`02 01 06   09 09 44 4D 42 54 2D 54 30 32   07 03 0D 18 0F 18 09 18`

**Expected DM BT output.**
- Three Service UUID (16-bit) entries: `0x180D`, `0x180F`, `0x1809`
  (in that order; the parser preserves on-air order).
- The device row's "Service UUIDs" cache (`previouslyNoticedServicesUUIDs`)
  picks up the canonical 128-bit forms (`0000180d-0000-1000-8000-00805f9b34fb`
  etc.) for use in subsequent background-filter rebuilds.

---

## T03 — Complete list of 128-bit Service UUIDs (Nordic UART)

**Purpose.** Verifies `parseUuids128` and the LE-to-canonical reversal in
`bytesToUuid128`. NUS is a real-world UUID that's commonly miscoded by
hand — good canary for endianness mistakes.

**nRF Connect settings.** Connectable ON, Scannable ON, Legacy ON.
Display name: `DMBT-T03-UUID128`.

**Advertising data.**

| Type | nRF Connect record | Value |
|---|---|---|
| 0x01 | Flags | `0x06` |
| 0x09 | Complete Local Name | `DMBT-T03` |
| 0x07 | Complete List of 128-bit UUIDs | `6E400001-B5A3-F393-E0A9-E50E24DCCA9E` |

**Raw bytes.**
`02 01 06   09 09 44 4D 42 54 2D 54 30 33   11 07 9E CA DC 24 0E E5 A9 E0 93 F3 A3 B5 01 00 40 6E`

**Expected DM BT output.**
- One Service UUID (128-bit) entry: `6e400001-b5a3-f393-e0a9-e50e24dcca9e`
  (the parser lower-cases via `UUID.toString()`).

---

## T04 — TX Power Level (signed)

**Purpose.** Verifies `parseTxPower` interprets the byte as **signed** int8
(`data[0].toInt().toByte().toInt()`), not unsigned. -12 dBm = `0xF4` is
the classic test value that catches a missing sign-extension.

**nRF Connect settings.** Connectable ON, Scannable ON, Legacy ON.
Display name: `DMBT-T04-TxPwr`.

**Advertising data.**

| Type | nRF Connect record | Value |
|---|---|---|
| 0x01 | Flags | `0x06` |
| 0x09 | Complete Local Name | `DMBT-T04` |
| 0x0A | Tx Power Level | `-12` |

**Raw bytes.** `02 01 06   09 09 44 4D 42 54 2D 54 30 34   02 0A F4`

**Expected DM BT output.**
- **TX Power**: `-12 dBm`. Must NOT show `244 dBm` — that would mean the
  sign bit was dropped.

---

## T05 — Appearance

**Purpose.** Verifies `parseAppearance` reads two bytes little-endian.
0x0341 = "Heart Rate Sensor: Heart Rate Belt" in the BT SIG appearance
table.

**nRF Connect settings.** Connectable ON, Scannable ON, Legacy ON.
Display name: `DMBT-T05-Appear`.

**Advertising data.**

| Type | nRF Connect record | Value |
|---|---|---|
| 0x01 | Flags | `0x06` |
| 0x09 | Complete Local Name | `DMBT-T05` |
| 0x19 | Appearance | `0x0341` |

**Raw bytes.** `02 01 06   09 09 44 4D 42 54 2D 54 30 35   03 19 41 03`

**Expected DM BT output.**
- **Appearance**: `0x0341`. Must NOT show `0x4103` (= LE order printed raw).

---

## T06 — Service Data (16-bit UUID) with printable payload

**Purpose.** Verifies `parseServiceData16`: extracts UUID from first 2 bytes
LE, hex-prints remaining bytes, and runs `interpretAsString` over the
payload (which only succeeds for fully-printable UTF-8).

**nRF Connect settings.** Connectable ON, Scannable ON, Legacy ON.
Display name: `DMBT-T06-SvcData`.

**Advertising data.**

| Type | nRF Connect record | Value |
|---|---|---|
| 0x01 | Flags | `0x06` |
| 0x16 | Service Data - 16-bit UUID | UUID `0x180F`, data bytes `42 41 54 38 35 25` (= "BAT85%") |

**Raw bytes.** `02 01 06   09 16 0F 18 42 41 54 38 35 25`

**Expected DM BT output.**
- **Service UUID (16-bit)**: `0x180F`
- **Service data**: `0x424154383525`
- **String interpretation**: `BAT85%`

(If the payload had any C0 control bytes, the string interpretation must
read `None` — that's covered implicitly by Test T08, whose bytes are not
fully printable across the entire payload.)

---

## T07 — URI (regression: single-byte scheme code)

**Purpose.** Direct guard for the URI parser fix called out in
`ParseBleAdRecord.parseUri`'s comment block. The earlier implementation
read the scheme code as **little-endian uint16**, which both consumed an
extra body byte and produced a garbage scheme label. Spec says single-byte
index. This test fails loudly if that regresses.

**nRF Connect settings.** Connectable ON, Scannable ON, Legacy ON.
Display name: `DMBT-T07-URI`.

**Advertising data.**

| Type | nRF Connect record | Value |
|---|---|---|
| 0x01 | Flags | `0x06` |
| 0x24 | URI | scheme `0x16` (https:), body `//DarkMentor.com` |

If nRF Connect's "URI" record type isn't surfaced directly, add a **Custom
Data** record with type byte `0x24` and the value bytes
`16 2F 2F 44 61 72 6B 4D 65 6E 74 6F 72 2E 63 6F 6D`.

**Raw bytes.**
`02 01 06   12 24 16 2F 2F 44 61 72 6B 4D 65 6E 74 6F 72 2E 63 6F 6D`

**Expected DM BT output.**
- **URI scheme**: `0x16 (https:)`
- **URI**: `https://DarkMentor.com`

If you see `URI scheme: 0x2F16 (unknown ...)` or the URI body shifted by
one character, the regression is back.

---

## T08 — Manufacturer Specific Data (generic, with company ID lookup)

**Purpose.** Verifies `parseManufacturerSpecific` company-ID extraction
(little-endian uint16) and `BluetoothSIG.bluetoothSIG` lookup. Nordic
(0x0059) is a stable choice — guaranteed to be present in any reasonable
BT SIG mirror.

**nRF Connect settings.** Connectable ON, Scannable ON, Legacy ON.
Display name: `DMBT-T08-Mfr`.

**Advertising data.**

| Type | nRF Connect record | Value |
|---|---|---|
| 0x01 | Flags | `0x06` |
| 0x09 | Complete Local Name | `DMBT-T08` |
| 0xFF | Manufacturer Specific Data | Company `0x0059`, payload `48 45 4C 4C 4F` (= "HELLO") |

**Raw bytes.**
`02 01 06   09 09 44 4D 42 54 2D 54 30 38   08 FF 59 00 48 45 4C 4C 4F`

**Expected DM BT output.**
- **Company ID**: `0x0059 (Nordic Semiconductor ASA)`
- **String interpretation**: `HELLO`

Also verify the device-details "Manufacturer (resolved)" field shows the
SIG company name pulled from the same lookup.

---

## T09 — iBeacon (Apple format detection)

**Purpose.** Verifies the Apple-iBeacon branch in
`parseManufacturerSpecific`: company id 0x004C + subtype 0x02 + length
0x15 + 16-byte big-endian UUID + 2-byte big-endian Major + 2-byte
big-endian Minor + signed int8 calibrated TX power. Catches regressions
where the UUID gets mistakenly LE-reversed (it's BE per Apple's spec).

**nRF Connect settings.** Connectable ON, Scannable ON, Legacy ON.
Display name: `DMBT-T09-iBeacon`.

**Advertising data.**

| Type | nRF Connect record | Value |
|---|---|---|
| 0x01 | Flags | `0x06` |
| 0xFF | Manufacturer Specific Data | Company `0x004C`, payload `02 15 E2 C5 6D B5 DF FB 48 D2 B0 60 D0 F5 A7 10 96 E0 00 01 00 02 C5` |

nRF Connect ships an **iBeacon** preset on the Advertiser screen which
fills the same bytes; either path is fine. If using the preset, set
UUID = `E2C56DB5-DFFB-48D2-B060-D0F5A71096E0`, Major = `1`, Minor = `2`,
TX power = `-59`.

**Raw bytes.**
`02 01 06   1A FF 4C 00 02 15 E2 C5 6D B5 DF FB 48 D2 B0 60 D0 F5 A7 10 96 E0 00 01 00 02 C5`

**Expected DM BT output.**
- **Company ID**: `0x004C (Apple, Inc.)`
- **Format**: `iBeacon`
- **UUID**: `E2C56DB5-DFFB-48D2-B060-D0F5A71096E0` (uppercase, BE order)
- **Major**: `1 (0x0001)`
- **Minor**: `2 (0x0002)`
- **TX Power (1m)**: `-59 dBm`

---

## T10 — Non-connectable beacon (advType regression: ADV_NONCONN_IND)

**Purpose.** Verifies `inferAdvType` returns `ADV_NONCONN_IND` (BTIDES
adv-type code `2`) when `isLegacy=true && isConnectable=false`. The
mapping is:

| Legacy | Connectable | adv_type / str |
|---|---|---|
| true | true | 0 / `ADV_IND` |
| true | false | 2 / `ADV_NONCONN_IND` |
| false | (any) | 10 / `AUX_ADV_IND` |

This test pins the middle row; T01 pins the top; T12 pins the top.

**nRF Connect settings.**
- Display name: `DMBT-T10-NonConn`
- **Connectable: OFF**
- Scannable: OFF
- Legacy: ON

**Advertising data.**

| Type | nRF Connect record | Value |
|---|---|---|
| 0x01 | Flags | `BR/EDR Not Supported` only (= 0x04) |
| 0x09 | Complete Local Name | `DMBT-T10` |
| 0xFF | Manufacturer Specific Data | Company `0x0059`, payload `48 45 4C 4C 4F` |

**Raw bytes.**
`02 01 04   09 09 44 4D 42 54 2D 54 31 30   08 FF 59 00 48 45 4C 4C 4F`

**Expected DM BT output.**
- Device list: row labelled `DMBT-T10`. Connect All must **skip** this row
  (no GATT enumeration attempt), since the scan result is non-connectable.
- Details → **Connectable**: `false`.
- BTIDES export: `AdvType` = `2`, `AdvTypeStr` = `ADV_NONCONN_IND`.

---

## T11 — Shortened Local Name (AD type 0x08)

**Purpose.** Verifies that the `0x08` branch of `parseLocalName` runs and
the device list resolves a name from the Shortened form (rather than
falling back to BD_ADDR). Some peripherals only ever advertise 0x08 and
never 0x09; this test guards that path.

**nRF Connect settings.** Connectable ON, Scannable ON, Legacy ON.
Display name: `DMBT-T11-ShortName`.

**Advertising data.**

| Type | nRF Connect record | Value |
|---|---|---|
| 0x01 | Flags | `0x06` |
| 0x08 | Shortened Local Name | `DMBT-T11` |

**Raw bytes.** `02 01 06   09 08 44 4D 42 54 2D 54 31 31`

**Expected DM BT output.**
- Device list: row labelled `DMBT-T11` (NOT the BD_ADDR; that would
  indicate the name-resolution path missed the 0x08 record).
- AD parser **Name**: `DMBT-T11`.

---

## T12 — Comprehensive packet + scan response (multi-AD)

**Purpose.** End-to-end: every common AD type in a single packet, plus a
scan response carrying overflow data. Verifies that DM BT parses each
sub-record independently without dropping any when packed densely, and
that the scan-response merge (Android merges adv data + scan response in
the `ScanRecord` it hands us) is preserved through to the parsed-fields
list.

**nRF Connect settings.**
- Display name: `DMBT-T12-Full`
- Connectable: ON
- Scannable: **ON** (required so the scan response is included)
- Legacy: ON

**Advertising data.**

| Type | nRF Connect record | Value |
|---|---|---|
| 0x01 | Flags | `0x06` |
| 0x09 | Complete Local Name | `DMBT-T12` |
| 0x03 | Complete List of 16-bit UUIDs | `0x180F` |
| 0x0A | Tx Power Level | `-8` |
| 0x19 | Appearance | `0x0341` |
| 0x16 | Service Data - 16-bit UUID | UUID `0x180F`, data `4F 4B` (= "OK") |

(Total = 30 bytes — under the 31-byte legacy adv limit.)

**Scan response data.**

| Type | nRF Connect record | Value |
|---|---|---|
| 0xFF | Manufacturer Specific Data | Company `0x0059`, payload `42 59 45` (= "BYE") |

**Raw bytes (adv).**
`02 01 06   09 09 44 4D 42 54 2D 54 31 32   03 03 0F 18   02 0A F8   03 19 41 03   05 16 0F 18 4F 4B`

**Raw bytes (scan response).**
`06 FF 59 00 42 59 45`

**Expected DM BT output.**
- Device list: row labelled `DMBT-T12`, RSSI populated.
- Details → AD parser block (in roughly this order; exact ordering follows
  on-air sequence + scan response append):
  - **Flags**: `0x06`; **Bits set** includes `LE General Discoverable Mode`
  - **Name**: `DMBT-T12`
  - **Service UUID (16-bit)**: `0x180F`
  - **TX Power**: `-8 dBm`
  - **Appearance**: `0x0341`
  - **Service UUID (16-bit)** (from Service Data record): `0x180F`
  - **Service data**: `0x4F4B`; **String interpretation**: `OK`
  - **Company ID**: `0x0059 (Nordic Semiconductor ASA)`
  - **String interpretation**: `BYE`
- BTIDES export: single record per scan window, `AdvType` = `0`
  (`ADV_IND`).

---

## Coverage matrix

| AD type / scenario | Test |
|---|---|
| 0x01 Flags                          | T01, T02-T08, T10, T11, T12 |
| 0x03 Complete 16-bit UUIDs          | T02, T12 |
| 0x07 Complete 128-bit UUIDs         | T03 |
| 0x08 Shortened Local Name           | T11 |
| 0x09 Complete Local Name            | T01, T02, T04-T05, T08, T10, T12 |
| 0x0A Tx Power (signed!)             | T04, T12 |
| 0x16 Service Data 16-bit            | T06, T12 |
| 0x19 Appearance                     | T05, T12 |
| 0x24 URI (single-byte scheme)       | T07 |
| 0xFF Manufacturer Specific (generic)| T08, T10, T12 |
| 0xFF Manufacturer Specific (iBeacon)| T09 |
| Connectable + Legacy → ADV_IND      | T01, T12 |
| Non-connectable + Legacy → ADV_NONCONN_IND | T10 |
| Scan-response merge                 | T12 |

A green pass on T01–T12 means: every parser branch in `ParseBleAdRecord`
that matters today fired with a known-good input, the advertising-type
inference is correct in the connectable / non-connectable cases that
ship today, and BTIDES capture is recording the right `AdvType` codes.
Add a row here whenever a new AD type starts being parsed.

## Out of scope

These are **not** covered by this suite (they need different tooling, or
a separate set of tests):

- GATT server enumeration (Connect All / characteristic & descriptor
  reads). nRF Connect has a separate "GATT Server" tab that can spin up
  fake services; build that out as `tests/manual/nrf-connect-gatt-server.md`
  if/when needed.
- BR/EDR ("BT Classic") inquiry, EIR parsing, SDP enumeration. The Blu
  View 5 phone can't be made to advertise as a discoverable BR/EDR
  device with custom EIR via nRF Connect — use a separate BR/EDR-capable
  fixture device for that.
- Extended (non-legacy) advertising. nRF Connect supports it on chipsets
  that do, but the DM BT side currently only distinguishes legacy vs.
  extended via `ScanResult.isLegacy`; T01 + T12 already cover the
  legacy/`ADV_IND` mapping. Add an extended-adv test if the AdvType
  inference logic ever grows additional branches.
- BD_ADDR / address-type parsing (`inferBdaddrRand`, public vs random,
  RPA detection). Driven by the radio's chosen address — nRF Connect
  doesn't let user code pin it. Cover via unit tests on the heuristic
  itself.
