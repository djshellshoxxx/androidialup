# I2 USB DTE transport specification

Status: implementation foundation

## 1. Goal

Phase I2 gives a computer a modem-style DTE connection to AndroidDialup without relying on the I1 developer TCP listener. The DTE presents the same AT command and online-data semantics already implemented by `ModemController`; only the byte transport changes.

The preferred end state is a standard USB serial device that a PC can open as a COM/tty port. Stock Android public APIs do not let a normal third-party application create an arbitrary USB CDC-ACM gadget, so I2 deliberately separates the modem byte engine from the USB attachment mechanism.

## 2. Platform findings

### 2.1 Public Android USB API

`android.hardware.usb.UsbManager` exposes USB host and USB accessory communication. In accessory mode the external accessory is the USB host and the Android device is the USB device. An Android application can open the accessory and exchange bytes through a file descriptor or streams.

The public API does not provide an application API for creating a CDC-ACM serial gadget. Therefore a normal Play-style application cannot promise to appear as a standard COM port merely by using `UsbManager`.

Primary references:

- https://developer.android.com/develop/connectivity/usb
- https://developer.android.com/reference/android/hardware/usb/UsbManager
- https://source.android.com/docs/core/interaction/accessories/protocol

### 2.2 Linux USB gadget path

Linux configfs USB gadget support includes the ACM function. A configured ACM function exposes `/dev/ttyGS<n>` on the gadget device and a CDC-ACM serial port on the USB host.

Primary references:

- https://www.kernel.org/doc/html/next/admin-guide/abi-testing-files.html
- https://www.kernel.org/doc/html/next/usb/gadget-testing.html
- https://www.kernel.org/doc/html/latest/usb/gadget_configfs.html

On Android this requires device/kernel support plus privileged control of gadget configuration and permission to open the gadget tty. That may mean root, a system application, vendor integration, or a custom Android image. SELinux policy is part of the device-specific implementation and must not be bypassed by the unprivileged build.

## 3. I2 transport modes

### 3.1 `USB_CDC_ACM_PRIVILEGED`

Target user experience: AndroidDialup enumerates as a CDC-ACM serial modem. Windows receives a COM port; Linux/macOS receive a tty-style device according to host driver support.

Android side:

1. A privileged component provisions or selects an ACM gadget function.
2. The resulting `/dev/ttyGS<n>` endpoint is opened read/write.
3. The file streams are handed to the shared stream DTE session.
4. The shared stream session owns DTE read, modem serialization, idle timer service and bounded modem-to-DTE writes.
5. USB disconnect closes the stream and calls `ModemController.onDteDisconnected()` exactly once.

The application must not contain device-specific configfs shell commands in the transport-neutral layer. Gadget provisioning belongs behind a small platform/provider interface because OEM layouts, UDC names and Android USB HAL ownership vary.

### 3.2 `USB_AOA`

Unprivileged fallback. The PC acts as the USB host/accessory and switches Android into Android Open Accessory mode. The Android application opens the `UsbAccessory` and hands its input/output streams to the same shared stream DTE session.

AOA does not itself create a standard serial port. A PC-side bridge may expose a pseudo-terminal/COM-style endpoint if needed. That helper is an explicit component, not something to describe as native CDC-ACM.

### 3.3 `BLUETOOTH_SPP`

Optional fallback after USB. Bluetooth RFCOMM/SPP can use the same shared stream DTE session when a target Android build/device supports the required Bluetooth profile and the host provides a serial-port mapping. It is not part of the first I2 acceptance gate.

## 4. Shared stream DTE contract

`platform-android.dte.StreamDteSession` is intentionally Android-free so JVM tests can exercise the byte path.

Inputs:

- one blocking `InputStream` from DTE to modem;
- one blocking `OutputStream` from modem to DTE;
- a controller factory receiving the serialized modem executor and a bounded `DteWriter`;
- a monotonic nanosecond clock;
- timer tick and maximum queued output bytes.

Thread ownership:

- `stream-dte-read`: sole owner of blocking input reads;
- `stream-dte-modem`: sole owner of `ModemController`; all DTE input, timer ticks, disconnect notification and asynchronous session callbacks are serialized here;
- `stream-dte-write`: sole owner of blocking output writes.

The same ownership rule as `TcpDteServer` applies: `ModemController` is never called concurrently.

## 5. Framing semantics

The DTE transport is a byte stream. It adds no framing, escaping or packet headers. Fragmentation and coalescing are transport artifacts and must not change AT parsing or online data.

Each successful input read is delivered as one `feedDte` call. The controller is responsible for handling command boundaries and online escape detection across arbitrary read boundaries.

Modem output is queued in-order and written byte-for-byte. No output bytes may be silently dropped.

## 6. Backpressure and failure

The modem-to-DTE writer has a fixed byte limit. If a stalled host causes the bound to be exceeded, the DTE session closes and reports DTE disconnect to the controller. This matches I1's rule that a blocked DTE is disconnected rather than dropping modem output.

Input EOF, input I/O failure, output I/O failure, explicit close and queue overflow all converge on one idempotent shutdown path.

Shutdown requirements:

- close both streams;
- cancel the idle timer;
- call `ModemController.onDteDisconnected()` once on the modem executor;
- stop read/write/modem worker ownership;
- ignore late writes after close.

## 7. Modem control signals

Byte transport is sufficient for I2 command/data acceptance, but CDC-ACM control-line support is a separate sub-track.

Desired mapping where the gadget/provider exposes it:

- host DTR -> DTE presence / policy input;
- modem DCD -> `ModemController.signals().dcd()`;
- RTS/CTS -> later flow-control integration;
- optional DSR/RI according to the final V.250 profile.

No code should fake hardware control-line support. Providers advertise which signals they can observe/set.

## 8. Security and permissions

- AOA follows Android's accessory permission model.
- Privileged ACM mode is disabled unless an installed provider proves it can open the configured gadget tty.
- DTE bytes are local transport data and must never be copied to diagnostics logs.
- Device secrets, relay proofs and authentication nonces remain outside the DTE transport.
- TCP DTE remains developer-only and is not automatically exposed on LAN as a fallback.

## 9. Acceptance tests

### Gate I2-A: shared stream engine

JVM tests must prove:

1. fragmented and coalesced AT input reaches one controller correctly;
2. arbitrary binary online data is preserved byte-for-byte;
3. modem output ordering is preserved;
4. timer ticks permit guarded `+++` handling while the input stream is idle;
5. EOF invokes one DTE disconnect;
6. read failure invokes one DTE disconnect;
7. write failure invokes one DTE disconnect;
8. bounded output overflow closes the session instead of dropping bytes;
9. explicit close is idempotent;
10. all controller entry points execute on the single modem owner.

### Gate I2-B: AOA device acceptance

On a physical Android device and PC/accessory host:

- permission grant succeeds;
- accessory attach opens the byte streams;
- `AT` -> `OK`;
- `ATD` reaches the existing relay session path;
- 64 KiB binary transfer is byte exact;
- unplug produces one DTE disconnect and clean call teardown.

### Gate I2-C: privileged CDC-ACM acceptance

On a supported rooted/system/custom-image device:

- gadget enumerates as CDC-ACM on the host;
- host terminal opens the COM/tty endpoint without adb;
- `AT` -> `OK`;
- `ATD` and online transfer work through the existing relay path;
- unplug/replug is recoverable;
- where supported, DCD reflects call state.

## 10. Implementation order

1. Implement and JVM-test `StreamDteSession`.
2. Add Android AOA adapter using `UsbManager` and `UsbAccessory`.
3. Add a provider interface for privileged tty-backed CDC-ACM.
4. Add a reference rooted/configfs provider only after testing on a concrete device family.
5. Add control-line support only where the selected provider exposes a reliable API.
6. Run I2-B and I2-C physical-device acceptance and record device/kernel/build identifiers.

## 11. Non-goals for this stage

- no claim that stock Android can create CDC-ACM through public APIs;
- no automatic root acquisition;
- no device-specific SELinux disabling;
- no modem DSP or PSTN waveform work in the DTE layer;
- no changes to ADUP relay framing.
