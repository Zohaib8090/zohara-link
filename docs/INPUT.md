# Phone as mouse, keyboard and game controller (daemon side, 2026-10-15)

The owner wants full control from the phone: a pointer as smooth as a touchpad, the whole screen, and a game controller.

## What was wrong
Every mouse move started a `ydotool` or `xdotool` process. Starting a process takes several milliseconds, so at 60 moves a
second the pointer moved in steps and lagged behind the finger.

## What the daemon does now (`linux-daemon/src/uinput.rs`)
It opens `/dev/uinput` once and writes the events itself (2000 moves took 1.35 ms on the owner's laptop). Devices are made when
first used and removed when the daemon stops:
* **Zohara Link Mouse**: `REL_X/Y`, wheel (with high-resolution units, so a finger drag scrolls smoothly), three buttons. The
  part of a move under one pixel is carried to the next move.
* **Zohara Link Pointer**: absolute position 0..65535 for "tap here on the shown screen".
* **Zohara Link Keyboard**: key down / key up by Linux code 1..248 (power, sleep and wake keys are refused).
* **Microsoft X-Box 360 pad** (vendor 045e, product 028e): looks like the wired Xbox 360 controller every game and Steam know:
  two sticks, two triggers (0..255), a direction pad (hat), 11 buttons. Appears on the first `GAMEPAD` message.
When `/dev/uinput` cannot be opened the old tools are used, so nothing stops working. The udev rule that gives the signed-in
user access ships with `zohara-settings` (`70-zohara-uinput.rules`).

## Messages (all `INPUT_EVENT`, all need the phone's "control the mouse" permission)
| action | fields |
|---|---|
| `MOVE` | `dx`, `dy` (floats, pixels) |
| `CLICK` | `button` LEFT / RIGHT / MIDDLE |
| `BUTTON` | `button`, `down` (true/false): hold and release, for dragging |
| `MOVE_TO` | `x`, `y` fractions 0..1 of the shown screen |
| `SCROLL` | `dy` / `dx` in lines, fractions allowed (old `lines` still works) |
| `KEY` | `key` a listed name (ENTER, ESCAPE, arrows ...) |
| `KEY_DOWN` / `KEY_UP` | `code` Linux key code |
| `GAMEPAD` | `buttons` bit mask (bit 0 A, 1 B, 2 X, 3 Y, 4 LB, 5 RB, 6 Back, 7 Start, 8 Guide, 9 left stick press, 10 right stick press), `lx ly rx ry` -1..1 (up = negative), `lt rt` 0..1, `hatX hatY` -1/0/1 |
| `GAMEPAD_OFF` | removes the controller |
Everything is clamped. When a phone's connection ends, every held key, button and the controller's state are released.

## Tested
`cargo test` (encoding, carry, scroll units, controller diffs, struct sizes); `tests/e2e_input.py` against the real daemon over TLS on
the owner's laptop: refused without the permission, a controller appears in `/proc/bus/input/devices` (with a `js` handler) and
is removed again, wild values do not stop the daemon. Not tested: a game reading the controller, the feel of the pointer on a real
phone, how KWin treats the absolute pointer.
