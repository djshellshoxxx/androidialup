# I2 Android Open Accessory DTE status

Status: SOFTWARE PATH IMPLEMENTED; physical-device acceptance pending.

## Implemented Android path

- `UsbDteLifecycle` owns at most one USB DTE and guarantees replacement/detach cleanup.
- `UsbAccessoryCoordinator` handles discovery, permission request/result, detach and unexpected stream closure without Android dependencies; JVM tests cover all state transitions.
- `UsbAccessoryDteTransport` opens an authorized `UsbAccessory` and feeds it into `StreamDteSession`.
- `ModemService` enumerates attached accessories at startup, dynamically handles attach/detach, requests permission with a package-scoped `PendingIntent`, and constructs the same `RelayModemSessionPort` + `ModemController` stack used by TCP DTE.
- `NetworkActivity` exposes live USB DTE status.
- `accessory_filter.xml` matches the PC bridge identity `manufacturer=CircuitDrift`, `model=AndroidDialup`; the dialer activity receives `USB_ACCESSORY_ATTACHED` so connecting the bridge can launch the app when it is not already running.

The Android Core Tests workflow compiles the application APK and all platform tests. No physical USB claim is made until Gate I2-B is run on hardware.

## Implemented PC host path

`prototype/python/androidialup_host` contains:

- `aoa.py`: Android Open Accessory control protocol 51/52/53 and the shared accessory identity;
- `pyusb_backend.py`: optional PyUSB/libusb control-transfer and bulk-endpoint backend;
- `bridge.py`: lossless bidirectional socket/USB stream bridge with TCP half-close handling;
- `cli.py`: switches an explicitly selected USB device into AOA when required, waits for re-enumeration, opens the AOA bulk pair, and exposes one local TCP DTE client.

PyUSB is optional and is not imported by the normal prototype suite. It is BSD-licensed. libusb is an external runtime dependency and is not vendored.

### Host setup

From `prototype/python`:

```text
python -m pip install pyusb
python -m androidialup_host --device 18d1:4ee7 --listen-port 2323
```

The value passed to `--device` is the phone's **current** VID:PID before AOA switching; it varies by phone/USB mode and should be obtained from Device Manager, `lsusb`, System Information or equivalent. If Android is already in AOA mode (`18d1:2d00` or `18d1:2d01`), `--device` is unnecessary.

After the bridge reports the local listener, connect a terminal or modem test program to `127.0.0.1:2323`. This is a TCP developer endpoint on the PC side, not a native COM port. A native host COM/tty presentation is a separate adapter; privileged Android CDC-ACM remains the preferred native-serial I2 path.

### Platform USB driver notes

- Linux: PyUSB requires a libusb backend and permission to claim the Android USB interface (udev/root policy may be needed during development).
- Windows: the selected Android/AOA USB interface must have a libusb-compatible driver such as WinUSB available to PyUSB. Driver installation is outside the Python process.
- macOS: PyUSB requires an installed libusb runtime.

## Automated verification

Pure tests cover:

- AOA protocol version parsing, identity ordering and malformed responses;
- USB accessory lifecycle replacement, detach, permission denial/grant and transport failure recovery;
- bulk endpoint selection and AOA control-transfer mapping with fake PyUSB devices;
- byte-exact bidirectional PC socket forwarding and half-close behavior;
- the existing modem/relay suites, Android adapter compile, instrumentation APK compile and app APK compile.

## Remaining I2 work

1. Run Gate I2-B on a physical Android phone and PC: permission, `AT` -> `OK`, `ATD`, 64 KiB binary transfer, unplug/replug.
2. Add a POSIX PTY host adapter for Linux/macOS convenience.
3. Decide whether Windows COM presentation should use a documented virtual-port driver or remain TCP for the unprivileged AOA helper.
4. Define the privileged gadget-provider interface and implement a concrete CDC-ACM provider only after choosing a rooted/custom Android test device.
5. Add DTR/DCD/RTS/CTS only where the selected CDC provider exposes real control-line state.
