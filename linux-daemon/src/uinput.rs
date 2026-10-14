//! Smooth input for a phone that controls this computer: virtual mouse, absolute pointer, keyboard and game controller
//! made with the kernel's `/dev/uinput`, written directly from the daemon.
//!
//! Before this, every move started a `ydotool` or `xdotool` process. A process start takes several milliseconds, so a phone
//! sending sixty moves a second made the pointer jump in steps and lag behind the finger. Here one open file is written to
//! with the event itself: it takes microseconds, so the pointer follows the finger as smoothly as a touchpad.
//!
//! * **Mouse** (`REL_X/REL_Y`, wheels, three buttons): the touchpad on the phone. The part of a move that is less than one
//!   pixel is carried to the next move, so slow movement is not lost.
//! * **Absolute pointer** (`ABS_X/ABS_Y`, 0 to 65535): "tap here" on the shown screen; the desktop maps it over the whole screen.
//! * **Keyboard**: key down and key up by Linux key code, so a key can be held (games) and not only tapped.
//! * **Game controller**: made to look like a wired Xbox 360 controller (the one every game and Steam already know), with two
//!   sticks, two triggers, a direction pad and eleven buttons. It exists only after the phone first sends a controller event.
//!
//! If `/dev/uinput` cannot be opened the callers fall back to the old tools. Permission comes from the udev rule that
//! `zohara-settings` ships (the signed-in user gets access, as Steam Input needs too).

use serde_json::Value;
use std::fs::{File, OpenOptions};
use std::io::Write;
use std::os::fd::AsRawFd;
use std::sync::Mutex;

const EV_SYN: u16 = 0x00;
const EV_KEY: u16 = 0x01;
const EV_REL: u16 = 0x02;
const EV_ABS: u16 = 0x03;

const REL_X: u16 = 0x00;
const REL_Y: u16 = 0x01;
const REL_HWHEEL: u16 = 0x06;
const REL_WHEEL: u16 = 0x08;
const REL_WHEEL_HI_RES: u16 = 0x0b;
const REL_HWHEEL_HI_RES: u16 = 0x0c;

const ABS_X: u16 = 0x00;
const ABS_Y: u16 = 0x01;
const ABS_Z: u16 = 0x02;
const ABS_RX: u16 = 0x03;
const ABS_RY: u16 = 0x04;
const ABS_RZ: u16 = 0x05;
const ABS_HAT0X: u16 = 0x10;
const ABS_HAT0Y: u16 = 0x11;

const BTN_LEFT: u16 = 0x110;
const BTN_RIGHT: u16 = 0x111;
const BTN_MIDDLE: u16 = 0x112;

/// The controller's buttons in the order of the bits the phone sends: A, B, X, Y, LB, RB, Back, Start, Guide, left stick
/// press, right stick press. (Linux codes as the Xbox 360 driver uses them.)
pub const PAD_BUTTONS: [u16; 11] = [0x130, 0x131, 0x133, 0x134, 0x136, 0x137, 0x13a, 0x13b, 0x13c, 0x13d, 0x13e];

// ioctl numbers for /dev/uinput (linux/uinput.h)
const UI_DEV_CREATE: libc::c_ulong = 0x5501;
const UI_DEV_DESTROY: libc::c_ulong = 0x5502;
const UI_DEV_SETUP: libc::c_ulong = 0x405c_5503;
const UI_ABS_SETUP: libc::c_ulong = 0x401c_5504;
const UI_SET_EVBIT: libc::c_ulong = 0x4004_5564;
const UI_SET_KEYBIT: libc::c_ulong = 0x4004_5565;
const UI_SET_RELBIT: libc::c_ulong = 0x4004_5566;
const UI_SET_ABSBIT: libc::c_ulong = 0x4004_5567;

#[repr(C)]
struct InputId {
    bustype: u16,
    vendor: u16,
    product: u16,
    version: u16,
}

#[repr(C)]
struct UinputSetup {
    id: InputId,
    name: [u8; 80],
    ff_effects_max: u32,
}

#[repr(C)]
struct AbsSetup {
    code: u16,
    _pad: u16,
    value: i32,
    minimum: i32,
    maximum: i32,
    fuzz: i32,
    flat: i32,
    resolution: i32,
}

#[repr(C)]
struct InputEvent {
    sec: libc::time_t,
    usec: libc::suseconds_t,
    kind: u16,
    code: u16,
    value: i32,
}

/// (type, code, value) as the kernel wants it.
pub type Ev = (u16, u16, i32);

struct Device {
    file: File,
}

fn ioctl(file: &File, req: libc::c_ulong, arg: libc::c_ulong) -> std::io::Result<()> {
    // SAFETY: plain integer argument ioctls on a file we own.
    let r = unsafe { libc::ioctl(file.as_raw_fd(), req as _, arg) };
    if r < 0 { Err(std::io::Error::last_os_error()) } else { Ok(()) }
}

struct AbsAxis {
    code: u16,
    min: i32,
    max: i32,
    fuzz: i32,
    flat: i32,
}

impl Device {
    fn create(name: &str, vendor: u16, product: u16, keys: &[u16], rels: &[u16], abs: &[AbsAxis]) -> std::io::Result<Device> {
        let file = OpenOptions::new().write(true).open("/dev/uinput")?;
        for (set, list) in [(UI_SET_KEYBIT, keys), (UI_SET_RELBIT, rels)] {
            if !list.is_empty() {
                ioctl(&file, UI_SET_EVBIT, if set == UI_SET_KEYBIT { EV_KEY } else { EV_REL } as libc::c_ulong)?;
                for c in list {
                    ioctl(&file, set, *c as libc::c_ulong)?;
                }
            }
        }
        if !abs.is_empty() {
            ioctl(&file, UI_SET_EVBIT, EV_ABS as libc::c_ulong)?;
            for a in abs {
                ioctl(&file, UI_SET_ABSBIT, a.code as libc::c_ulong)?;
            }
        }
        let mut n = [0u8; 80];
        for (i, b) in name.bytes().take(79).enumerate() {
            n[i] = b;
        }
        let setup = UinputSetup { id: InputId { bustype: 0x03, vendor, product, version: 1 }, name: n, ff_effects_max: 0 };
        // SAFETY: `setup` is a valid, fully initialised struct of the size the ioctl expects.
        if unsafe { libc::ioctl(file.as_raw_fd(), UI_DEV_SETUP as _, &setup as *const UinputSetup) } < 0 {
            return Err(std::io::Error::last_os_error());
        }
        for a in abs {
            let s = AbsSetup { code: a.code, _pad: 0, value: 0, minimum: a.min, maximum: a.max, fuzz: a.fuzz, flat: a.flat, resolution: 0 };
            // SAFETY: as above.
            if unsafe { libc::ioctl(file.as_raw_fd(), UI_ABS_SETUP as _, &s as *const AbsSetup) } < 0 {
                return Err(std::io::Error::last_os_error());
            }
        }
        ioctl(&file, UI_DEV_CREATE, 0)?;
        Ok(Device { file })
    }

    /// Writes the events and a sync in one write, so they arrive together.
    fn emit(&mut self, events: &[Ev]) -> std::io::Result<()> {
        let mut buf: Vec<u8> = Vec::with_capacity((events.len() + 1) * std::mem::size_of::<InputEvent>());
        for (kind, code, value) in events.iter().copied().chain(std::iter::once((EV_SYN, 0, 0))) {
            let e = InputEvent { sec: 0, usec: 0, kind, code, value };
            // SAFETY: InputEvent is repr(C) plain data; its bytes are what the kernel reads.
            buf.extend_from_slice(unsafe { std::slice::from_raw_parts(&e as *const InputEvent as *const u8, std::mem::size_of::<InputEvent>()) });
        }
        self.file.write_all(&buf)
    }
}

impl Drop for Device {
    fn drop(&mut self) {
        let _ = ioctl(&self.file, UI_DEV_DESTROY, 0);
    }
}

// ---------------------------------------------------------------------------------------------------------------
// Pure parts (tested)
// ---------------------------------------------------------------------------------------------------------------

/// Splits a move into whole pixels and the leftover that is carried to the next one.
pub fn take_whole(value: f64, carry: &mut f64) -> i32 {
    let v = (value + *carry).clamp(-4000.0, 4000.0);
    let whole = v.round();
    *carry = v - whole;
    whole as i32
}

pub fn button_code(name: &str) -> Option<u16> {
    match name {
        "LEFT" => Some(BTN_LEFT),
        "RIGHT" => Some(BTN_RIGHT),
        "MIDDLE" => Some(BTN_MIDDLE),
        _ => None,
    }
}

/// Key codes a phone may press: the ordinary keyboard (1 to 248), not power, sleep or wake keys.
pub fn key_allowed(code: u32) -> bool {
    (1..=248).contains(&code) && ![116, 142, 143, 205, 205].contains(&code)
}

/// 0.0..1.0 -> 0..65535.
pub fn abs_position(f: f64) -> i32 {
    (if f.is_finite() { f.clamp(0.0, 1.0) } else { 0.0 } * 65535.0).round() as i32
}

/// Scroll amounts in "lines" (may be fractions) -> (whole wheel clicks, high-resolution units; 120 = one click).
pub fn wheel_units(lines: f64) -> (i32, i32) {
    let hi = (lines.clamp(-40.0, 40.0) * 120.0).round() as i32;
    (hi / 120, hi)
}

/// What the phone's controller looks like at one moment.
#[derive(Debug, Clone, Copy, PartialEq, Default)]
pub struct PadState {
    pub buttons: u16,
    /// left stick, right stick: -1.0..1.0
    pub lx: f64,
    pub ly: f64,
    pub rx: f64,
    pub ry: f64,
    /// triggers 0.0..1.0
    pub lt: f64,
    pub rt: f64,
    /// direction pad -1, 0, 1
    pub hat_x: i32,
    pub hat_y: i32,
}

fn stick(v: f64) -> i32 {
    ((if v.is_finite() { v.clamp(-1.0, 1.0) } else { 0.0 }) * 32767.0).round() as i32
}

fn trigger(v: f64) -> i32 {
    ((if v.is_finite() { v.clamp(0.0, 1.0) } else { 0.0 }) * 255.0).round() as i32
}

impl PadState {
    /// From the phone's `GAMEPAD` message: `buttons` (bit mask), `lx ly rx ry` (-1..1), `lt rt` (0..1), `hatX hatY`.
    pub fn from_packet(p: &Value) -> PadState {
        let f = |k: &str| p.get(k).and_then(Value::as_f64).unwrap_or(0.0);
        let hat = |k: &str| (p.get(k).and_then(Value::as_i64).unwrap_or(0)).clamp(-1, 1) as i32;
        PadState {
            buttons: (p.get("buttons").and_then(Value::as_u64).unwrap_or(0) & 0x7ff) as u16,
            lx: f("lx"),
            ly: f("ly"),
            rx: f("rx"),
            ry: f("ry"),
            lt: f("lt"),
            rt: f("rt"),
            hat_x: hat("hatX"),
            hat_y: hat("hatY"),
        }
    }
}

/// The events that turn `old` into `new` (only what changed, so a held stick is not repeated).
pub fn pad_events(old: &PadState, new: &PadState) -> Vec<Ev> {
    let mut out: Vec<Ev> = Vec::new();
    for (i, code) in PAD_BUTTONS.iter().enumerate() {
        let (a, b) = (old.buttons >> i & 1, new.buttons >> i & 1);
        if a != b {
            out.push((EV_KEY, *code, b as i32));
        }
    }
    let axes: [(u16, i32, i32); 8] = [
        (ABS_X, stick(old.lx), stick(new.lx)),
        // up on the phone is up on the controller: Linux gamepads report up as negative
        (ABS_Y, stick(old.ly), stick(new.ly)),
        (ABS_RX, stick(old.rx), stick(new.rx)),
        (ABS_RY, stick(old.ry), stick(new.ry)),
        (ABS_Z, trigger(old.lt), trigger(new.lt)),
        (ABS_RZ, trigger(old.rt), trigger(new.rt)),
        (ABS_HAT0X, old.hat_x, new.hat_x),
        (ABS_HAT0Y, old.hat_y, new.hat_y),
    ];
    for (code, a, b) in axes {
        if a != b {
            out.push((EV_ABS, code, b));
        }
    }
    out
}

// ---------------------------------------------------------------------------------------------------------------
// The devices (made when first needed, kept while the daemon runs)
// ---------------------------------------------------------------------------------------------------------------

#[derive(Default)]
struct Hub {
    mouse: Option<Device>,
    pointer: Option<Device>,
    keyboard: Option<Device>,
    pad: Option<Device>,
    carry: (f64, f64),
    wheel_carry: (f64, f64),
    pad_state: PadState,
    /// What is held down right now (so it can be let go when the phone disappears).
    held_keys: Vec<u16>,
    held_buttons: Vec<u16>,
}

static HUB: Mutex<Option<Hub>> = Mutex::new(None);

fn with_hub<T>(f: impl FnOnce(&mut Hub) -> std::io::Result<T>) -> Option<T> {
    let mut g = HUB.lock().ok()?;
    let hub = g.get_or_insert_with(Hub::default);
    match f(hub) {
        Ok(v) => Some(v),
        Err(e) => {
            log::warn!("uinput: {e}; falling back to the old tools");
            // a device that failed is made again next time
            *g = None;
            None
        }
    }
}

/// Whether the kernel's input device can be opened (so the smooth path is possible).
pub fn available() -> bool {
    OpenOptions::new().write(true).open("/dev/uinput").is_ok()
}

fn mouse(h: &mut Hub) -> std::io::Result<&mut Device> {
    if h.mouse.is_none() {
        h.mouse = Some(Device::create("Zohara Link Mouse", 0x1209, 0x7a01, &[BTN_LEFT, BTN_RIGHT, BTN_MIDDLE], &[REL_X, REL_Y, REL_WHEEL, REL_HWHEEL, REL_WHEEL_HI_RES, REL_HWHEEL_HI_RES], &[])?);
    }
    Ok(h.mouse.as_mut().unwrap())
}

/// Moves the pointer by (dx, dy) pixels (fractions are carried). Some(()) = done through uinput.
pub fn mouse_move(dx: f64, dy: f64) -> Option<()> {
    with_hub(|h| {
        let (mut cx, mut cy) = h.carry;
        let (ix, iy) = (take_whole(dx, &mut cx), take_whole(dy, &mut cy));
        h.carry = (cx, cy);
        if ix == 0 && iy == 0 {
            return Ok(());
        }
        mouse(h)?.emit(&[(EV_REL, REL_X, ix), (EV_REL, REL_Y, iy)])
    })
}

pub fn mouse_button(name: &str, down: bool) -> Option<()> {
    let code = button_code(name)?;
    with_hub(|h| {
        h.held_buttons.retain(|c| *c != code);
        if down {
            h.held_buttons.push(code);
        }
        mouse(h)?.emit(&[(EV_KEY, code, down as i32)])
    })
}

pub fn mouse_click(name: &str) -> Option<()> {
    let code = button_code(name)?;
    with_hub(|h| {
        let m = mouse(h)?;
        m.emit(&[(EV_KEY, code, 1)])?;
        m.emit(&[(EV_KEY, code, 0)])
    })
}

/// Scrolls by lines (fractions allowed: positive = down / right). The smooth units make a finger drag scroll smoothly.
pub fn mouse_scroll(dy_lines: f64, dx_lines: f64) -> Option<()> {
    with_hub(|h| {
        let (mut vy, mut vx) = h.wheel_carry;
        let mut ev: Vec<Ev> = Vec::new();
        for (lines, carry, whole_code, hi_code, sign) in [(dy_lines, &mut vy, REL_WHEEL, REL_WHEEL_HI_RES, -1), (dx_lines, &mut vx, REL_HWHEEL, REL_HWHEEL_HI_RES, 1)] {
            let total = lines + *carry;
            let (whole, hi) = wheel_units(total);
            // what was not sent yet stays for next time
            *carry = total - (hi as f64) / 120.0;
            if hi != 0 {
                ev.push((EV_REL, hi_code, sign * hi));
            }
            if whole != 0 {
                ev.push((EV_REL, whole_code, sign * whole));
            }
        }
        h.wheel_carry = (vy, vx);
        if ev.is_empty() { Ok(()) } else { mouse(h)?.emit(&ev) }
    })
}

/// Puts the pointer at a place given as fractions of the screen.
pub fn pointer_to(fx: f64, fy: f64) -> Option<()> {
    with_hub(|h| {
        if h.pointer.is_none() {
            h.pointer = Some(Device::create(
                "Zohara Link Pointer",
                0x1209,
                0x7a02,
                &[BTN_LEFT, BTN_RIGHT],
                &[],
                &[AbsAxis { code: ABS_X, min: 0, max: 65535, fuzz: 0, flat: 0 }, AbsAxis { code: ABS_Y, min: 0, max: 65535, fuzz: 0, flat: 0 }],
            )?);
        }
        h.pointer.as_mut().unwrap().emit(&[(EV_ABS, ABS_X, abs_position(fx)), (EV_ABS, ABS_Y, abs_position(fy))])
    })
}

pub fn key(code: u32, down: bool) -> Option<()> {
    if !key_allowed(code) {
        return None;
    }
    with_hub(|h| {
        if h.keyboard.is_none() {
            let keys: Vec<u16> = (1u16..=248).collect();
            h.keyboard = Some(Device::create("Zohara Link Keyboard", 0x1209, 0x7a03, &keys, &[], &[])?);
        }
        h.held_keys.retain(|c| *c != code as u16);
        if down {
            h.held_keys.push(code as u16);
        }
        h.keyboard.as_mut().unwrap().emit(&[(EV_KEY, code as u16, down as i32)])
    })
}

/// A tap of one key.
pub fn key_tap(code: u32) -> Option<()> {
    key(code, true)?;
    key(code, false)
}

/// Sets the controller to this state (made on first use).
pub fn gamepad(new: PadState) -> Option<()> {
    with_hub(|h| {
        if h.pad.is_none() {
            let stick_axis = |code| AbsAxis { code, min: -32768, max: 32767, fuzz: 16, flat: 128 };
            let trig = |code| AbsAxis { code, min: 0, max: 255, fuzz: 0, flat: 0 };
            let hat = |code| AbsAxis { code, min: -1, max: 1, fuzz: 0, flat: 0 };
            h.pad = Some(Device::create(
                "Microsoft X-Box 360 pad",
                0x045e,
                0x028e,
                &PAD_BUTTONS,
                &[],
                &[stick_axis(ABS_X), stick_axis(ABS_Y), trig(ABS_Z), stick_axis(ABS_RX), stick_axis(ABS_RY), trig(ABS_RZ), hat(ABS_HAT0X), hat(ABS_HAT0Y)],
            )?);
            h.pad_state = PadState::default();
        }
        let events = pad_events(&h.pad_state, &new);
        h.pad_state = new;
        if events.is_empty() { Ok(()) } else { h.pad.as_mut().unwrap().emit(&events) }
    })
}

/// Lets go of everything that is held: keys, mouse buttons, the controller's sticks and buttons.
pub fn release_all() {
    let Ok(mut g) = HUB.lock() else { return };
    let Some(h) = g.as_mut() else { return };
    let keys = std::mem::take(&mut h.held_keys);
    let buttons = std::mem::take(&mut h.held_buttons);
    if let Some(k) = h.keyboard.as_mut() {
        for c in keys {
            let _ = k.emit(&[(EV_KEY, c, 0)]);
        }
    }
    if let Some(m) = h.mouse.as_mut() {
        for c in buttons {
            let _ = m.emit(&[(EV_KEY, c, 0)]);
        }
    }
    let idle = PadState::default();
    let ev = pad_events(&h.pad_state, &idle);
    h.pad_state = idle;
    if let (Some(p), false) = (h.pad.as_mut(), ev.is_empty()) {
        let _ = p.emit(&ev);
    }
}

/// Removes the controller (the phone left the controller screen, or disconnected).
pub fn gamepad_off() {
    if let Ok(mut g) = HUB.lock() {
        if let Some(h) = g.as_mut() {
            h.pad = None;
            h.pad_state = PadState::default();
        }
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use serde_json::json;

    #[test]
    fn slow_movement_is_carried_not_lost() {
        let mut carry = 0.0;
        let total: i32 = (0..10).map(|_| take_whole(0.3, &mut carry)).sum();
        assert_eq!(total, 3);
        assert!(carry.abs() < 0.5);
        let mut c = 0.0;
        assert_eq!(take_whole(-2.6, &mut c), -3);
        assert!((c - 0.4).abs() < 1e-9);
        assert_eq!(take_whole(f64::MAX, &mut 0.0), 4000, "a wild value is capped");
    }

    #[test]
    fn positions_buttons_and_keys_are_checked() {
        assert_eq!((abs_position(0.0), abs_position(1.0), abs_position(0.5)), (0, 65535, 32768));
        assert_eq!((abs_position(-3.0), abs_position(f64::NAN), abs_position(9.0)), (0, 0, 65535));
        assert_eq!(button_code("RIGHT"), Some(BTN_RIGHT));
        assert_eq!(button_code("evil"), None);
        assert!(key_allowed(30) && key_allowed(57));
        assert!(!key_allowed(0) && !key_allowed(116) && !key_allowed(142) && !key_allowed(100_000), "power and sleep keys are not for a phone");
    }

    #[test]
    fn scrolling_has_smooth_units() {
        assert_eq!(wheel_units(1.0), (1, 120));
        assert_eq!(wheel_units(-0.5), (0, -60));
        assert_eq!(wheel_units(2.25), (2, 270));
        assert_eq!(wheel_units(1000.0), (40, 4800), "capped");
    }

    #[test]
    fn the_controller_sends_only_what_changed() {
        let idle = PadState::default();
        assert!(pad_events(&idle, &idle).is_empty());
        let p = PadState::from_packet(&json!({"buttons": 0b101, "lx": 1.0, "ly": -0.5, "lt": 1.0, "hatY": -1}));
        assert_eq!(p.buttons, 0b101);
        let ev = pad_events(&idle, &p);
        assert!(ev.contains(&(EV_KEY, 0x130, 1)), "A pressed");
        assert!(ev.contains(&(EV_KEY, 0x133, 1)), "X pressed (bit 2)");
        assert!(!ev.iter().any(|e| e.1 == 0x131), "B untouched");
        assert!(ev.contains(&(EV_ABS, ABS_X, 32767)));
        assert!(ev.contains(&(EV_ABS, ABS_Y, -16384)));
        assert!(ev.contains(&(EV_ABS, ABS_Z, 255)));
        assert!(ev.contains(&(EV_ABS, ABS_HAT0Y, -1)));
        // releasing everything again
        let back = pad_events(&p, &idle);
        assert!(back.contains(&(EV_KEY, 0x130, 0)) && back.contains(&(EV_ABS, ABS_X, 0)) && back.contains(&(EV_ABS, ABS_Z, 0)));
        // nonsense from the network is clamped, never wild
        let wild = PadState::from_packet(&json!({"lx": 99.0, "rt": -4.0, "hatX": 7, "buttons": 0xffff_ffffu64}));
        assert_eq!((wild.lx, wild.rt, wild.hat_x, wild.buttons), (99.0, -4.0, 1, 0x7ff));
        let ev = pad_events(&idle, &wild);
        assert!(ev.contains(&(EV_ABS, ABS_X, 32767)));
        assert!(!ev.iter().any(|e| e.1 == ABS_RZ), "a negative trigger is 0, which is unchanged");
    }

    #[test]
    fn structs_have_the_kernels_sizes() {
        assert_eq!(std::mem::size_of::<UinputSetup>(), 92);
        assert_eq!(std::mem::size_of::<AbsSetup>(), 28);
        assert_eq!(std::mem::size_of::<InputEvent>(), 24);
    }
}

#[cfg(test)]
mod real {
    use super::*;

    fn devices() -> String {
        std::fs::read_to_string("/proc/bus/input/devices").unwrap_or_default()
    }

    /// `cargo test real_devices -- --ignored --nocapture`: makes the real devices through /dev/uinput, checks the kernel lists
    /// them, moves the pointer 20 pixels and back, then removes everything.
    #[test]
    #[ignore]
    fn real_devices() {
        assert!(available(), "/dev/uinput cannot be opened");
        assert!(mouse_move(20.0, 0.0).is_some());
        assert!(mouse_move(-20.0, 0.0).is_some());
        assert!(mouse_scroll(0.0, 0.0).is_some());
        assert!(pointer_to(0.5, 0.5).is_some());
        assert!(key(30, false).is_some());
        assert!(gamepad(PadState { buttons: 1, lx: 0.5, ..PadState::default() }).is_some());
        assert!(gamepad(PadState::default()).is_some());
        std::thread::sleep(std::time::Duration::from_millis(300));
        let d = devices();
        for name in ["Zohara Link Mouse", "Zohara Link Pointer", "Zohara Link Keyboard", "Microsoft X-Box 360 pad"] {
            assert!(d.contains(name), "{name} is not listed");
        }
        let pad = d.split("\n\n").find(|b| b.contains("Microsoft X-Box 360 pad")).unwrap().to_string();
        println!("PAD ENTRY:\n{pad}");
        gamepad_off();
        release_all();
        *HUB.lock().unwrap() = None; // drops (destroys) every device
        std::thread::sleep(std::time::Duration::from_millis(300));
        assert!(!devices().contains("Zohara Link Mouse"), "the devices must be removed again");
        println!("REAL DEVICES OK");
    }

    /// `cargo test real_speed -- --ignored --nocapture`: how long 2000 tiny moves take (net movement is zero).
    #[test]
    #[ignore]
    fn real_speed() {
        let t = std::time::Instant::now();
        for i in 0..2000 {
            mouse_move(if i % 2 == 0 { 1.0 } else { -1.0 }, 0.0).unwrap();
        }
        println!("SPEED: 2000 moves in {:?}", t.elapsed());
        *HUB.lock().unwrap() = None;
    }
}
