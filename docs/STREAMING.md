# Game streaming from the computer to the phone (design, 2026-10-15)

Goal (owner's words): control the whole computer from the phone, **including games**: real video with low delay, sound, a game
controller, a pointer as smooth as a touchpad. All in Rust. Moonlight/Sunshine may be used as inspiration.

## Decision: our own stream over WebRTC, not the GameStream protocol
* Sunshine (the server) and Moonlight (the client) speak NVIDIA's GameStream protocol (RTSP + ENet + custom RTP + FEC). Writing a
  compatible server in Rust is months of work for little gain: we also control the phone app, so we do not need to be compatible.
* **WebRTC** already has what is hard: encrypted media (DTLS-SRTP), loss recovery (NACK/PLI), congestion control, NAT traversal
  (ICE: works on the home Wi-Fi and over Tailscale), data channels for input, and Android ships a hardware-accelerated client
  library. A LAN stream with a hardware encoder reaches roughly 40 to 80 ms; Moonlight can be a little lower, WebRTC is good enough
  for a phone as controller and for most games. If it is not, the encoder and capture parts stay and only the transport changes.

## Parts
```
KDE screen --(xdg-desktop-portal ScreenCast, asked once)--> PipeWire frames
   --> GStreamer: convert --> hardware H.264 (VA-API on the Intel chip, NVENC on NVIDIA, x264 as last resort)
   --> webrtcbin (GStreamer) ==== WebRTC (video, later audio, data channel "input") ====> phone (libwebrtc, hardware decoder)
Phone controller / touch / keys --(data channel)--> daemon --> /dev/uinput (virtual mouse, keyboard, Xbox 360 pad; already built)
```
* `zohara-linkd` (the daemon) stays small and does not link GStreamer. The stream runs in its own program, **`zohara-linkd-stream`**
  (`linux-daemon/src/bin/stream.rs`), started by the daemon when a phone asks and stopped when it leaves or disconnects. If
  GStreamer is missing only streaming fails, never the daemon. The two talk with JSON lines over stdin/stdout.
* Signalling (offer, answer, ICE candidates) travels over the already paired, encrypted connection between phone and daemon:
  `STREAM_START`, `STREAM_OFFER`, `STREAM_ANSWER`, `STREAM_ICE`, `STREAM_STOP`. No extra server, nothing exposed.
* Permissions: watching needs the phone's "show the screen" permission, input over the data channel needs "control the mouse"
  (the same two switches in Settings > Zohara Link). Both off by default.
* Capture needs the desktop's own permission dialog once (screen sharing). The token is kept so it is not asked again.
* Encoder choice respects the laptop's two graphics chips: the Intel chip's encoder first (does not wake the NVIDIA chip), NVENC
  when the NVIDIA chip is already running the game, software x264 as a fallback.

## Phases
1. **Daemon side** (this document's first commit): the stream program, offer/answer/ICE, data channel input, tests with a
   test picture and a receiver in the same test. Status below.
2. **Settings**: a button "Set up game streaming" (installs the GStreamer plugins: pipewire, va, webrtc; asks for the password) and
   a button that triggers the screen-sharing permission once.
3. **Phone app**: libwebrtc client, full-screen game view with the controller on top, quality choice (resolution, frame rate, bitrate).
4. **Sound** (the computer's output as Opus), adaptive bitrate, 60 and 120 frames per second, latency numbers on screen.

## Status
See the end of this file; it is updated with every step.

### 2026-10-15: phase 1 (daemon side) built
* `linux-daemon/src/bin/stream.rs` (`zohara-linkd-stream`): GStreamer pipeline (test picture or PipeWire screen from the portal via
  `ashpd`) -> encoder (VA-API / NVENC / x264, picked at run time, Intel first) -> H.264 RTP -> `webrtcbin`; offer/answer/ICE; a data
  channel `input` whose messages come back to the daemon; stdin/stdout JSON lines (format at the top of the file).
* `daemon.rs`: `STREAM_START` (needs the "show the screen" permission) starts the program, relays `STREAM_OFFER`, `STREAM_ICE`,
  `STREAM_STATE`, `STREAM_ERROR` to the phone and `STREAM_ANSWER`, `STREAM_ICE`, `STREAM_STOP` to the program. Input from the data channel
  needs "control the mouse" and is checked for every message; it goes through the same `/dev/uinput` code as the touchpad and controller.
  The stream ends with the connection or when a permission is taken away. A missing program or GStreamer gives the phone
  `STREAM_ERROR` ("not installed ... Set up game streaming").
* **Tested** (`zs-gst` container, GStreamer 1.28 with x264): `cargo test` runs a whole stream, sender and receiver in one process: the
  receiver gets more than 30 H.264 video packets, the data channel message comes back, the connected state is reported.
  `tests/e2e_stream.py` against the real daemon over TLS: refused without permission, a real offer (video + data channel, H264) and
  candidates reach the phone, STREAM_STOP and a vanished phone both end the program. On the owner's laptop (no WebRTC plugin installed
  yet): `EXPECT=missing` passes, i.e. a clear error and an unharmed daemon.
* **Not tested yet**: the real screen (portal dialog, PipeWire), hardware encoders (vah264enc was not available in the test container,
  nvh264enc encodes on the laptop), video on a real phone, latency. Those need the GStreamer plugins on the laptop and the phone client.
* Package: `zohara-linkd` now installs the stream program and depends on `gstreamer`, `gst-plugins-base-libs`, `gst-plugins-bad-libs`;
  the plugins are optional and installed by Settings.
