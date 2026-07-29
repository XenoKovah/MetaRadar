# Task brief: real-time, per-device GPS for XenoMetaRadar

You are working in the **XenoMetaRadar** Android app (Kotlin, package `com.darkmentor`;
the installed debug build is `com.darkmentor.debug`). Make three related changes so GPS
coordinates track *where/when each device was actually seen or connected to*, instead of
many devices sharing one coordinate per scan batch.

## The three changes (what the product owner asked for)

1. **Poll GPS every 1 second** (currently 10 s) in the `DEFAULT` and `DEFAULT_RESTRICTED`
   power modes. Leave `POWER_SAVING` alone.
2. **On every successful GATT connection, immediately capture the current GPS fix and
   record it for that device** (so the device's plotted location matches where we connected
   to it).
3. **Stop tagging every device in a scan batch with one shared coordinate keyed by the
   batch's start time.** Each device should be associated with the GPS fix nearest in time
   to when *that* device was seen.

---

## Orientation (assume no prior context)

- **Build env:** `JAVA_HOME` must point at a **JDK 21**; `ANDROID_HOME` must be set. Build
  with `./gradlew :app:assembleDebug`; unit tests with `./gradlew :app:testDebugUnitTest`.
- **DI:** Koin. Wiring lives in `app/src/main/java/com/darkmentor/data/DataModule.kt`.
- **Data model (Room DB `app-database`):**
  - `device` — one row per device, PK `address` (MAC). Has `last_seen_rssi`, etc.
  - `location` — PK **`time`** (epoch ms), `lat`, `lng`. (Insert is
    `OnConflictStrategy.REPLACE`, so reusing a `time` overwrites.)
  - `device_to_location` — links a device to a location: `device_address`, `location_time`
    (FK → `location.time`), `rssi`. **A device's plotted points = this table joined to
    `location`.** This is the table that must gain better rows.
- **Clock note (important for change 3):** scan timestamps (`BleScanDevice.scanTimeMs`) and
  the location provider's `emitTime` are **both** `System.currentTimeMillis()` (wall-clock
  ms), so they are directly comparable. Don't mix in `SystemClock.elapsedRealtime()`.

---

## Change 1 — GPS cadence 10 s → 1 s

**File:** `app/src/main/java/com/darkmentor/data/helpers/PowerModeHelper.kt` (the
`PowerMode` enum, ~lines 101–128).

- `DEFAULT`: `locationUpdateInterval = 10_000L` → **`1_000L`** (~line 105).
- `DEFAULT_RESTRICTED`: `locationUpdateInterval = 10_000L` → **`1_000L`** (~line 114).
- `POWER_SAVING`: **leave unchanged** (`60_000L`, and `useLocation = false`).

**How it takes effect (no other change needed for cadence):**
`LocationProvider` (`app/src/main/java/com/darkmentor/data/helpers/LocationProvider.kt`) runs
a self-rescheduling poll: the result `consumer` calls `scheduleNextRequest()`, which does
`handler.postDelayed(nextLocationRequest, powerMode().locationUpdateInterval)` (~line 194),
and the interval is also passed to `LocationRequest.Builder(...)` for `getCurrentLocation`
(~line 157). Lowering the constant lowers the poll period.

**Caveats / keep in mind:**
- 1 s GPS polling is **power-hungry** (continuous `getCurrentLocation`). This is the explicit
  product decision — implement it, but don't also "optimize" it back up.
- The freshness/accuracy gates still apply and are fine at 1 s: `MAX_ALLOWED_ACCURACY_METERS
  = 100`, `ALLOWED_LOCATION_LIVETIME_MS = 2 min`, and `isRelevant()` only emits a new fix
  when the position actually changed.
- **Faster polling alone does NOT add more `location`/`device_to_location` rows.** Rows are
  written per scan batch and (after change 2) per connection. Change 1 just makes a *fresher*
  fix available to those writes. Changes 2 and 3 are what actually improve the plotted data.

---

## Change 2 — record GPS on every successful connection

**Definition of "successful connection":** GATT reaches `STATE_CONNECTED`.

**Where it happens:** `app/src/main/java/com/darkmentor/data/helpers/BleScannerHelper.kt`,
`onConnectionStateChange` → `checkStatus(...)`, the `BluetoothProfile.STATE_CONNECTED` branch
(~line 413) which emits `DeviceConnectResult.Connected(gatt)`. `address` is in scope there.
**This single point covers both Connect All and the Device-Details single-connect** (both
connect through this helper).

**Required behavior:** at that moment, grab the freshest GPS fix and write a `location` +
`device_to_location` row for `address`, keyed by the connection time.

**Recommended implementation:**
- Inject `LocationProvider` and `LocationRepository` into `BleScannerHelper`. It already
  depends on repositories (e.g. `btidesRepository`), so this fits the existing style. Update
  the Koin factory in `DataModule.kt` (~line 52, currently `BleScannerHelper(get(), get(),
  get(), get(), get(), get())`) to pass the two new `get()`s.
- In the `STATE_CONNECTED` branch, do the DB work **off the binder thread** (the callback is
  not a coroutine). There is already `private val btidesScope = CoroutineScope(SupervisorJob()
  + Dispatchers.IO)` — reuse it:

  ```kotlin
  btidesScope.launch {
      val now = System.currentTimeMillis()
      val fix = locationProvider.getFreshLocation() ?: locationProvider.lastKnownLocation()
      if (fix != null) {
          locationRepository.saveLocation(
              LocationModel(lat = fix.latitude, lng = fix.longitude, time = now),
              mapOf(address to /* last known rssi for this device, or null */ null),
          )
      }
  }
  ```
  - `LocationModel`'s constructor order is `(lat, lng, time)` — use named args.
  - `LocationRepository.saveLocation(location, Map<String, Int?>)` already writes the
    `location` row and the per-device `device_to_location` row (see
    `app/src/main/java/com/darkmentor/data/repo/LocationRepository.kt`, ~line 30).
  - Keying `location.time = now` makes the row unique per connection (REPLACE on conflict).
- **"Immediately updated":** with change 1, `getFreshLocation()` is at most ~1–2 s old, which
  satisfies this. If you want a brand-new fix at the exact connect instant, optionally call
  `locationProvider.fetchOnce()` as well, and/or add a small awaitable single-shot helper to
  `LocationProvider` — optional, not required.

**Alternative (only if injecting into the helper is undesirable):** hook wherever
`DeviceConnectResult.Connected` is collected in the domain layer (the Connect-All flow in
`BulkEnumerateGattInteractor` / `ConnectAllSession`, and `DeviceDetailsViewModel` for single
connects). This is more places and easier to miss one — prefer the single chokepoint above.

**Scope note:** if the owner later means "successful *enumeration*" rather than "connected,"
move the write to the `onServicesDiscovered` `GATT_SUCCESS` path instead. As written the ask
is "when we connected," so `STATE_CONNECTED` is correct.

---

## Change 3 — per-device coordinate instead of one-per-batch

**File:** `app/src/main/java/com/darkmentor/domain/interactor/SaveOrMergeBatchInteractor.kt`,
~lines 62–73. It already injects `locationProvider` and `locationRepository` (no DI change).

**Current code (the root cause of the pile-ups):**
```kotlin
val location = locationProvider.getFreshLocation()
val detectTime = batch.firstOrNull()?.scanTimeMs          // ONE time for the whole batch
if (location != null && detectTime != null) {
    val rssiByAddress = batch.groupBy { it.address }
        .mapValues { (_, s) -> s.mapNotNull { it.rssi }.maxOrNull() }
    locationRepository.saveLocation(location.toDomain(detectTime), rssiByAddress)  // ALL → 1 row
}
```
Every device in the batch is linked to a single `location` row keyed by the *first* device's
`scanTimeMs`. With ~hundreds of devices per scan window this collapses everything onto one
coordinate.

**Required behavior:** associate **each** device with the fix nearest in time to when that
device was seen, keyed by that device's own timestamp.

**Recommended implementation:**

1. **Add a short fix-history to `LocationProvider`.** In the result `consumer` (right after
   `locationState.tryEmit(...)`, ~line 60), append `(emitTime, Location)` to a bounded buffer
   (e.g. `ArrayDeque`); trim entries older than `ALLOWED_LOCATION_LIVETIME_MS` (2 min) or cap
   the size. Add:
   ```kotlin
   /** The buffered fix closest in time to [timestampMs], or null if none is within range. */
   fun getFreshLocationAt(timestampMs: Long): Location?
   ```
   Return the buffered entry minimizing `abs(emitTime - timestampMs)`, or null if the nearest
   is outside the freshness window. (`emitTime` and `scanTimeMs` are the same wall-clock base.)

2. **Rewrite the batch write to be per-device:**
   ```kotlin
   batch.forEach { d ->
       val fix = locationProvider.getFreshLocationAt(d.scanTimeMs) ?: return@forEach
       locationRepository.saveLocation(
           LocationModel(lat = fix.latitude, lng = fix.longitude, time = d.scanTimeMs),
           mapOf(d.address to d.rssi),
       )
   }
   ```
   One `location` row per device keyed by the device's own `scanTimeMs`, linked only to that
   device, using the nearest-in-time fix. (Devices that share an identical `scanTimeMs` + fix
   simply REPLACE the same row — harmless. You may de-dupe for efficiency, but correctness
   doesn't require it.) Keep the existing "strongest RSSI per device" intent.

**Nuance to respect:** `BleScanDevice.scanTimeMs` is per-result in some scan paths
(`raw.scanTimeMs` / `callbackTimeMs`, `BleScannerHelper.kt` ~lines 171/215) but equals the
scan-cycle start (`currentScanTimeMs`, ~line 279) in another, so within one cycle some devices
still share a timestamp. That's acceptable — use the most per-result timestamp the path
provides; combined with 1 s GPS (change 1) and connection-time writes (change 2), coordinates
will track each device closely. The key requirement is simply: **do not collapse to
`batch.first().scanTimeMs` anymore.**

---

## Why (so you keep the intent straight)

Today the app takes one GPS fix per ~5 s scan batch and links **every** device in that batch
to it, keyed by the batch's start time — so 100+ devices land on one identical coordinate and
the map shows giant stacks. These three changes make each plotted point reflect where/when the
device was actually heard (scan) or connected (GATT), with GPS refreshed every second.

---

## Testing — and a HARD data-safety rule

**The test phones contain irreplaceable scan data. Do not wipe it.**
- **Back up first**, to the Mac:
  ```
  adb -s <serial> exec-out run-as com.darkmentor.debug cat databases/app-database     > backup-app-database
  adb -s <serial> exec-out run-as com.darkmentor.debug cat databases/app-database-wal > backup-app-database-wal
  adb -s <serial> exec-out run-as com.darkmentor.debug cat databases/app-database-shm > backup-app-database-shm
  ```
- **Install with `adb install -r`** (preserves app data). **NEVER** `adb uninstall`, **never**
  `./gradlew connectedAndroidTest`, never "clear data" — on this Android version those wipe the
  Room DB irrecoverably.
- Confirm the serial each session (`adb devices -l`) and always pass `-s <serial>`.

**Functional checks** after a short live scan + a Connect All run:
- `location` rows now arrive ~1/second while moving (consecutive `time` deltas ≈ 1000 ms in
  `DEFAULT`).
- `device_to_location` has rows whose `location_time` equals a connection moment for devices
  you connected to.
- Devices no longer all share one coordinate per batch; distinct coordinates per device go up.
- Unit tests pass (`./gradlew :app:testDebugUnitTest`) — there are existing `LocationProvider`
  tests (e.g. `locationPositionsDiffer`); add coverage for `getFreshLocationAt(...)`.

There is a read-only inspection tool at the repo root, **`visualize-phone-data`** (a Python
script that pulls the DB and plots `device_to_location` on OpenStreetMap). It needs **no
changes** — it will automatically reflect the new per-device and per-connection points.

---

## Acceptance criteria

- [ ] `DEFAULT` and `DEFAULT_RESTRICTED` poll GPS every 1 s; `POWER_SAVING` unchanged.
- [ ] Every successful GATT connection writes a `location` + `device_to_location` row for that
      device, keyed by the connect time, for **both** Connect All and Device-Details connects.
- [ ] Scan batches write **per-device** location rows keyed by each device's own time using
      the nearest fix — no longer one shared row keyed by the batch start time.
- [ ] Project builds; unit tests pass; **no app data wiped during testing**.

## Files you will likely touch

- `data/helpers/PowerModeHelper.kt` — change 1.
- `data/helpers/LocationProvider.kt` — fix-history + `getFreshLocationAt` (change 3); maybe a
  single-shot helper (change 2, optional).
- `data/helpers/BleScannerHelper.kt` — write location on `STATE_CONNECTED` (change 2).
- `data/DataModule.kt` — inject `LocationProvider` + `LocationRepository` into
  `BleScannerHelper` (change 2).
- `domain/interactor/SaveOrMergeBatchInteractor.kt` — per-device writes (change 3).
