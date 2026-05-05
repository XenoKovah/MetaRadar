# Bumble fixtures + DM BT instrumented tests

Paired host-runs-bumble + phone-runs-test regression suite. Each fixture
ID corresponds to one bumble advertiser configuration ([fixtures.py](fixtures.py))
and one on-device JUnit test class
(`app/src/androidTest/java/f/cking/software/bumblefixture/BumbleFixtureTNN…`).

The fixture advertises a known set of bytes from a known BD_ADDR; the
on-device test scans for that BD_ADDR and asserts that every field the
DM BT app captured matches what the fixture sent.

## Prerequisites

1. macOS / Linux host with bumble installed (the existing repo venv at
   `/tmp/bumble-test-venv` works) and a USB Bluetooth controller
   attached. macOS-specific gotchas:
   - Realtek RTL8761BU dongles need their patch firmware loaded once:
     `bumble-rtk-fw-download --single rtl8761bu`. Without it the radio
     won't actually transmit (HCI commands "succeed" but the air is
     silent).
   - If `usb:0` returns `LIBUSB_ERROR_ACCESS`, an orphan Python process
     from a previous run is still holding the device. Find it with
     `ioreg -p IOUSB -l | grep -A1 "Bluetooth Radio@" | grep ExclusiveOwner`,
     then kill that PID.
2. The Blu View 5 (or any DM BT-running test phone) connected via ADB
   with USB debugging authorised.

## Running one test

```sh
# Terminal 1 — bring up the fixture on the air
source /tmp/bumble-test-venv/bin/activate
python tests/bumble-fixtures/run_fixture.py T01

# Terminal 2 — build + install the test APK if you haven't already
JAVA_HOME=/opt/homebrew/opt/openjdk@21/libexec/openjdk.jdk/Contents/Home \
  ./gradlew :app:assembleGithubDebugAndroidTest

# Terminal 2 — run only the matching test class on the phone
JAVA_HOME=/opt/homebrew/opt/openjdk@21/libexec/openjdk.jdk/Contents/Home \
  ./gradlew :app:connectedGithubDebugAndroidTest \
    -Pandroid.testInstrumentationRunnerArguments.class=f.cking.software.bumblefixture.BumbleFixtureT01InstrumentedTest
```

When the test finishes, **Ctrl-C** the bumble process (don't `kill -9`
it; SIGINT lets it release the USB transport cleanly), then advance to
the next fixture and repeat:

```sh
python tests/bumble-fixtures/run_fixture.py T02
# ...and run BumbleFixtureT02InstrumentedTest
```

## Why one-fixture-at-a-time?

A single bumble Device instance broadcasts one advertising set. Running
multiple fixtures concurrently would need either multiple HCI dongles
(N controllers) or extended-advertising sets (one controller, multiple
adv handles) — both are more complexity than the regression suite
needs. The phone-side tests also fail-fast on missing fixtures, so
running them in a batch without the fixture up just gives you a fast
red — there's no hidden cost to one-at-a-time.

## Adding a new fixture

1. Add an entry to `FIXTURES` in [fixtures.py](fixtures.py) with a
   unique BD_ADDR last-byte so the phone's recent-device cache won't
   collide with adjacent fixtures.
2. Mirror its `expected` block into a new
   `BumbleFixtureT??InstrumentedTest.kt` under
   `app/src/androidTest/java/f/cking/software/bumblefixture/`.
3. Reference the AD type / parser branch the test pins in the test
   class's KDoc, so future readers know what regression each fixture
   guards against.

## Relationship to the manual nRF Connect suite

[`tests/manual/nrf-connect-advertisers.md`](../manual/nrf-connect-advertisers.md)
exists as a manual fallback: same advertising packets, but configured
through nRF Connect's UI on a separate phone. Use it when no host with
bumble + a USB BT dongle is available, or for ad-hoc spot-checking
without rebuilding the test APK. The fixtures defined here are the
authoritative byte-level reference; the manual doc is descriptive,
not normative.
