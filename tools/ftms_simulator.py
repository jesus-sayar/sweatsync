#!/usr/bin/env python3
"""Virtual FTMS treadmill or indoor bike for the Android emulator.

It joins the emulator's virtual Bluetooth radio (netsim) through Bumble and behaves like a real machine:
it advertises the Fitness Machine service (0x1826) with its machine type, accepts a connection and sends
Treadmill Data (0x2ACD) or Indoor Bike Data (0x2AD2) notifications once per second. The app's real scanning
and GATT code run against it, so detection, connection and recording can be tested without a phone.

Setup (once):  python3 -m venv ~/.venvs/sweatsync-sim && ~/.venvs/sweatsync-sim/bin/pip install bumble
Run (emulator already booted):
    ~/.venvs/sweatsync-sim/bin/python tools/ftms_simulator.py treadmill
    ~/.venvs/sweatsync-sim/bin/python tools/ftms_simulator.py bike --name "SIM Bike"
Stop it with Ctrl+C: the machine stops advertising, as when a real one is switched off.
"""

import argparse
import asyncio
import random
import struct

from bumble.core import UUID, AdvertisingData
from bumble.device import Device
from bumble.gatt import Characteristic, CharacteristicValue, Service
from bumble.hci import Address
from bumble.transport import open_transport

FTMS = UUID.from_16_bits(0x1826)
FEATURE = UUID.from_16_bits(0x2ACC)
TREADMILL_DATA = UUID.from_16_bits(0x2ACD)
BIKE_DATA = UUID.from_16_bits(0x2AD2)

# FTMS service data: "machine available" flag + machine type bit field (spec section 3.1.1).
MACHINE_TYPE_BITS = {"treadmill": 0x0001, "bike": 0x0020}
ADDRESSES = {"treadmill": "F0:5A:00:00:00:01", "bike": "F0:5A:00:00:00:02"}
DEFAULT_SPEED = {"treadmill": 10.0, "bike": 25.0}  # km/h


class Machine:
    """Integrates speed into distance and builds the FTMS data packets."""

    def __init__(self, kind: str, speed_kmh: float):
        self.kind = kind
        self.speed_kmh = speed_kmh
        self.distance_m = 0.0

    def step(self) -> bytes:
        speed = max(0.0, self.speed_kmh + random.uniform(-0.3, 0.3))
        self.distance_m += speed / 3.6
        heart_rate = 120 + random.randint(0, 25)
        if self.kind == "treadmill":
            flags = 0x0004 | 0x0100  # total distance + heart rate (speed is implicit)
            return struct.pack("<HH", flags, round(speed * 100)) + int(self.distance_m).to_bytes(3, "little") + bytes([heart_rate])
        cadence = 80 + random.randint(0, 10)
        power = 150 + random.randint(-15, 15)
        flags = 0x0004 | 0x0010 | 0x0040 | 0x0200  # cadence + distance + power + heart rate
        return (
            struct.pack("<HHH", flags, round(speed * 100), cadence * 2)
            + int(self.distance_m).to_bytes(3, "little")
            + struct.pack("<hB", power, heart_rate)
        )


def advertising_data(kind: str) -> bytes:
    return bytes(AdvertisingData(
        [
            (AdvertisingData.FLAGS, bytes([0x06])),
            (AdvertisingData.COMPLETE_LIST_OF_16_BIT_SERVICE_CLASS_UUIDS, bytes(FTMS)),
            (AdvertisingData.SERVICE_DATA_16_BIT_UUID, bytes(FTMS) + struct.pack("<BH", 0x01, MACHINE_TYPE_BITS[kind])),
        ]
    ))


async def run(kind: str, name: str, speed: float, transport: str) -> None:
    machine = Machine(kind, speed)
    async with await open_transport(transport) as hci:
        device = Device.with_hci(name, Address(ADDRESSES[kind]), hci.source, hci.sink)
        data_uuid = TREADMILL_DATA if kind == "treadmill" else BIKE_DATA
        data = Characteristic(data_uuid, Characteristic.Properties.NOTIFY, Characteristic.READABLE, bytes(2))
        feature = Characteristic(FEATURE, Characteristic.Properties.READ, Characteristic.READABLE, bytes(8))
        device.add_service(Service(FTMS, [feature, data]))

        device.advertising_data = advertising_data(kind)
        # The name travels in the scan response, as on many real machines: the app must cope with it arriving later.
        device.scan_response_data = bytes(AdvertisingData([(AdvertisingData.COMPLETE_LOCAL_NAME, name.encode())]))

        def on_connection(connection):
            print(f"connected: {connection.peer_address}", flush=True)
            connection.on("disconnection", lambda reason: print(f"disconnected (reason {reason})", flush=True))

        device.on("connection", on_connection)

        await device.power_on()
        await device.start_advertising(auto_restart=True)
        print(f"advertising {name} ({kind}) at {speed} km/h - Ctrl+C to switch it off", flush=True)

        while True:
            await asyncio.sleep(1)
            packet = machine.step()
            if device.connections:
                await device.notify_subscribers(data, packet)


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    parser.add_argument("kind", choices=["treadmill", "bike"])
    parser.add_argument("--name", help="advertised name (default: SIM Treadmill / SIM Bike)")
    parser.add_argument("--speed", type=float, help="speed in km/h")
    parser.add_argument("--transport", default="android-netsim", help="Bumble transport (default: android-netsim)")
    args = parser.parse_args()
    name = args.name or f"SIM {args.kind.capitalize()}"
    speed = args.speed if args.speed is not None else DEFAULT_SPEED[args.kind]
    try:
        asyncio.run(run(args.kind, name, speed, args.transport))
    except KeyboardInterrupt:
        print("switched off")


if __name__ == "__main__":
    main()
