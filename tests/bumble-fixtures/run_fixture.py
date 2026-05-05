"""Bumble advertiser harness for DM BT instrumented tests.

Usage:
    python run_fixture.py <FIXTURE_ID> [--transport TRANSPORT]

Each fixture corresponds to one instrumented test class in
`app/src/androidTest/java/f/cking/software/bumblefixture/`. Run the
fixture in one terminal, then run the matching test on the phone in
another terminal; the test scans for the fixture's BD_ADDR and asserts
that DM BT captured every field the fixture advertised.

Stop the advertiser with SIGINT (Ctrl-C) or `kill -INT <pid>`. SIGINT
shuts asyncio down cleanly so the USB device is released; SIGKILL
(`kill -9`) leaks the libusb claim and the next run will fail with
LIBUSB_ERROR_ACCESS until you find and kill the orphan process.
"""

import argparse
import asyncio
import logging
import signal
import sys

from bumble.device import Device, AdvertisingType
from bumble.hci import Address
from bumble.transport import open_transport

import fixtures


async def run(fixture_id: str, transport_name: str) -> None:
    f = fixtures.get(fixture_id)
    print(f"[*] Fixture {fixture_id}: {f['description']}")
    print(f"[*] BD_ADDR={f['address']}  Connectable={f['connectable']}")
    print(f"[*] adv_data ({len(f['adv_data'])} bytes): {f['adv_data'].hex()}")
    if f.get("scan_response_data"):
        print(
            f"[*] scan_response ({len(f['scan_response_data'])} bytes): "
            f"{f['scan_response_data'].hex()}"
        )

    async with await open_transport(transport_name) as (hci_source, hci_sink):
        device = Device.with_hci(
            f["local_name"],
            Address(f["address"]),
            hci_source,
            hci_sink,
        )
        adv_type = (
            AdvertisingType.UNDIRECTED_CONNECTABLE_SCANNABLE
            if f["connectable"]
            else AdvertisingType.UNDIRECTED
        )
        await device.power_on()
        await device.start_advertising(
            advertising_type=adv_type,
            advertising_data=f["adv_data"],
            scan_response_data=f.get("scan_response_data"),
            auto_restart=True,
        )

        # Cooperative shutdown: signal handlers set this Event so the
        # async-with blocks above run their __aexit__ (which closes the
        # USB transport cleanly). Without this, Ctrl-C bubbles up as
        # KeyboardInterrupt at an arbitrary await point and asyncio
        # cancellation racing with libusb teardown can leave the device
        # in an "exclusively owned" state.
        stop_event = asyncio.Event()
        loop = asyncio.get_running_loop()
        for sig in (signal.SIGINT, signal.SIGTERM):
            loop.add_signal_handler(sig, stop_event.set)

        print(f"[*] Advertising as '{f['local_name']}' — Ctrl-C to stop")
        await stop_event.wait()
        print("[*] Shutting down")
        await device.stop_advertising()


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__.split("\n\n")[0])
    parser.add_argument("fixture_id", help="Fixture key, e.g. T01, T02")
    parser.add_argument(
        "--transport",
        default="usb:0",
        help="Bumble transport name (default: usb:0)",
    )
    parser.add_argument(
        "--verbose",
        action="store_true",
        help="Enable bumble debug logging",
    )
    args = parser.parse_args()

    logging.basicConfig(
        level=logging.DEBUG if args.verbose else logging.WARNING,
        format="%(asctime)s %(levelname)s %(name)s: %(message)s",
    )

    try:
        asyncio.run(run(args.fixture_id, args.transport))
    except KeyboardInterrupt:
        # asyncio.run already drained the loop; this is just to suppress
        # the noisy traceback when SIGINT arrives between asyncio.run's
        # signal-handler setup and ours.
        pass


if __name__ == "__main__":
    main()
