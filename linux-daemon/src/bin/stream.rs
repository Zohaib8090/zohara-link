//! `zohara-linkd-stream`: sends the computer's screen to a paired phone as live video, and takes the phone's input back.
//!
//! The daemon (`zohara-linkd`) starts this program when a phone asks to stream and stops it when the phone leaves. It is a separate
//! program so the daemon never depends on GStreamer: if GStreamer is missing, only streaming fails.
//!
//! The daemon and this program talk with one JSON object per line on stdin/stdout:
//!
//! daemon -> stream
//!   {"cmd":"start","source":"test"|"pipewire","fd":N,"node":N,"width":1280,"height":720,"fps":60,"bitrate":8000}
//!   {"cmd":"answer","sdp":"..."}            the phone's answer to our offer
//!   {"cmd":"ice","mline":0,"candidate":"..."}
//!   {"cmd":"stop"}
//! stream -> daemon
//!   {"event":"offer","sdp":"..."}           send to the phone as STREAM_OFFER
//!   {"event":"ice","mline":0,"candidate":"..."}
//!   {"event":"input","data":{...}}          a message from the phone's data channel (an INPUT_EVENT body)
//!   {"event":"state","state":"streaming"|"closed"}
//!   {"event":"error","message":"..."}
//!
//! Video: a hardware H.264 encoder when there is one (VA-API on the Intel chip, then NVENC), software x264 otherwise; no B-frames and
//! a short key-frame interval so a lost picture heals fast. Everything is encrypted by WebRTC (DTLS-SRTP).

use gstreamer as gst;
use gstreamer::prelude::*;
use gstreamer_sdp as gst_sdp;
use gstreamer_webrtc as gst_webrtc;
use serde_json::{json, Value};
use std::io::{BufRead, Write};
use std::sync::{Arc, Mutex};

#[derive(Debug, Clone, PartialEq)]
pub struct Options {
    pub source: Source,
    pub width: u32,
    pub height: u32,
    pub fps: u32,
    /// kilobits per second
    pub bitrate: u32,
}

#[derive(Debug, Clone, PartialEq)]
pub enum Source {
    /// A moving test picture (tests, and checking the transport without a screen).
    Test,
    /// The screen from the desktop's screen-sharing portal: a PipeWire connection and the stream's node.
    PipeWire { fd: i32, node: u32 },
}

/// Keeps what the phone may ask for inside sane bounds.
pub fn clamp_options(width: u64, height: u64, fps: u64, bitrate: u64) -> (u32, u32, u32, u32) {
    ((width as u32).clamp(320, 3840), (height as u32).clamp(240, 2160), (fps as u32).clamp(10, 120), (bitrate as u32).clamp(500, 80_000))
}

/// The encoders in the order we like them, as (element name, the part of the pipeline after the converter).
pub fn encoder_candidates(o: &Options, nvidia_awake: bool) -> Vec<(&'static str, String)> {
    let gop = o.fps; // a key frame every second
    let b = o.bitrate;
    let va = (
        "vah264enc",
        format!("vah264enc rate-control=cbr bitrate={b} key-int-max={gop} b-frames=0 target-usage=7 ! video/x-h264,profile=constrained-baseline"),
    );
    let nv = (
        "nvh264enc",
        format!("nvh264enc preset=low-latency-hq zerolatency=true rc-mode=cbr bitrate={b} gop-size={gop} bframes=0 ! video/x-h264,profile=constrained-baseline"),
    );
    let x264 = (
        "x264enc",
        format!("x264enc tune=zerolatency speed-preset=ultrafast sliced-threads=true bitrate={b} key-int-max={gop} bframes=0 ! video/x-h264,profile=baseline"),
    );
    // The Intel chip first: it does not wake the NVIDIA chip. When the NVIDIA chip is awake (a game runs on it), NVENC is fine.
    if nvidia_awake { vec![nv, va, x264] } else { vec![va, nv, x264] }
}

/// Makes sure the Rust side knows GStreamer's WebRTC classes before webrtcbin hands any out (otherwise the first data channel is
/// refused with "expected GstWebRTCDataChannel, got GstWebRTCDataChannel").
fn register_webrtc_types() {
    use gst::glib::prelude::StaticType;
    let _ = gst_webrtc::WebRTCDataChannel::static_type();
    let _ = gst_webrtc::WebRTCRTPTransceiver::static_type();
}

fn element_exists(name: &str) -> bool {
    gst::ElementFactory::find(name).is_some()
}

/// The pipeline text for the options, using the first encoder that exists.
pub fn pipeline_description(o: &Options, nvidia_awake: bool, have: &dyn Fn(&str) -> bool) -> Result<String, String> {
    let (name, enc) = encoder_candidates(o, nvidia_awake)
        .into_iter()
        .find(|(n, _)| have(n))
        .ok_or("No H.264 encoder is installed (install gst-plugin-va, or gst-plugins-ugly for x264).")?;
    eprintln!("encoder: {name}");
    let source = match &o.source {
        Source::Test => "videotestsrc is-live=true pattern=ball".to_string(),
        Source::PipeWire { fd, node } => format!("pipewiresrc fd={fd} path={node} do-timestamp=true always-copy=true"),
    };
    Ok(format!(
        "{source} ! queue max-size-buffers=1 max-size-bytes=0 max-size-time=0 leaky=downstream ! videoconvert ! videoscale ! videorate \
         ! video/x-raw,width={w},height={h},framerate={fps}/1 ! {enc} ! h264parse config-interval=-1 \
         ! rtph264pay config-interval=-1 aggregate-mode=zero-latency pt=96 \
         ! application/x-rtp,media=video,encoding-name=H264,payload=96,clock-rate=90000 ! w. \
         webrtcbin name=w bundle-policy=max-bundle latency=0",
        w = o.width,
        h = o.height,
        fps = o.fps,
    ))
}

/// What the stream tells whoever started it.
#[derive(Debug, Clone, PartialEq)]
pub enum Event {
    Offer(String),
    Ice { mline: u32, candidate: String },
    Input(Value),
    State(&'static str),
    Error(String),
}

impl Event {
    pub fn to_json(&self) -> Value {
        match self {
            Event::Offer(sdp) => json!({"event": "offer", "sdp": sdp}),
            Event::Ice { mline, candidate } => json!({"event": "ice", "mline": mline, "candidate": candidate}),
            Event::Input(v) => json!({"event": "input", "data": v}),
            Event::State(s) => json!({"event": "state", "state": s}),
            Event::Error(m) => json!({"event": "error", "message": m}),
        }
    }
}

type Sink = Arc<dyn Fn(Event) + Send + Sync>;

/// A running stream.
pub struct Stream {
    pipeline: gst::Pipeline,
    webrtc: gst::Element,
}

/// Messages from a phone are small JSON objects; anything else is dropped.
pub fn parse_input_message(text: &str) -> Option<Value> {
    if text.len() > 4096 {
        return None;
    }
    let v: Value = serde_json::from_str(text).ok()?;
    v.get("action")?.as_str()?;
    Some(v)
}

impl Stream {
    pub fn start(o: &Options, nvidia_awake: bool, sink: Sink) -> Result<Stream, String> {
        gst::init().map_err(|e| format!("GStreamer could not start: {e}"))?;
        register_webrtc_types();
        if !element_exists("webrtcbin") {
            return Err("GStreamer's WebRTC part is not installed (gst-plugins-bad).".into());
        }
        let desc = pipeline_description(o, nvidia_awake, &element_exists)?;
        let pipeline = gst::parse::launch(&desc).map_err(|e| format!("The stream could not be built: {e}"))?.downcast::<gst::Pipeline>().map_err(|_| "not a pipeline")?;
        let webrtc = pipeline.by_name("w").ok_or("no webrtcbin")?;

        // negotiation: we make the offer, the phone answers
        {
            let sink = sink.clone();
            webrtc.connect("on-negotiation-needed", false, move |values| {
                let w = values[0].get::<gst::Element>().ok()?;
                let sink = sink.clone();
                let w2 = w.clone();
                let promise = gst::Promise::with_change_func(move |reply| {
                    let offer = match reply {
                        Ok(Some(r)) => r.value("offer").ok().and_then(|v| v.get::<gst_webrtc::WebRTCSessionDescription>().ok()),
                        _ => None,
                    };
                    let Some(offer) = offer else {
                        sink(Event::Error("The offer could not be made.".into()));
                        return;
                    };
                    w2.emit_by_name::<()>("set-local-description", &[&offer, &None::<gst::Promise>]);
                    sink(Event::Offer(offer.sdp().to_string()));
                });
                w.emit_by_name::<()>("create-offer", &[&None::<gst::Structure>, &promise]);
                None
            });
        }
        {
            let sink = sink.clone();
            webrtc.connect("on-ice-candidate", false, move |values| {
                let mline = values[1].get::<u32>().ok()?;
                let candidate = values[2].get::<String>().ok()?;
                sink(Event::Ice { mline, candidate });
                None
            });
        }
        {
            let sink = sink.clone();
            webrtc.connect_notify(Some("connection-state"), move |w, _| {
                let state = w.property::<gst_webrtc::WebRTCPeerConnectionState>("connection-state");
                match state {
                    gst_webrtc::WebRTCPeerConnectionState::Connected => sink(Event::State("streaming")),
                    gst_webrtc::WebRTCPeerConnectionState::Failed | gst_webrtc::WebRTCPeerConnectionState::Closed | gst_webrtc::WebRTCPeerConnectionState::Disconnected => {
                        sink(Event::State("closed"))
                    }
                    _ => {}
                }
            });
        }

        // errors on the pipeline are told, not swallowed
        if let Some(bus) = pipeline.bus() {
            let sink = sink.clone();
            let _ = bus.add_watch(move |_, msg| {
                if let gst::MessageView::Error(e) = msg.view() {
                    sink(Event::Error(format!("{} ({})", e.error(), e.debug().unwrap_or_default())));
                }
                gst::glib::ControlFlow::Continue
            });
        }
        pipeline.set_state(gst::State::Playing).map_err(|e| format!("The stream could not start: {e}"))?;

        // the phone's input comes back on this channel
        // handled as a plain GObject: the typed wrapper refuses the object ("expected GstWebRTCDataChannel, got GstWebRTCDataChannel")
        let channel_value = webrtc.emit_by_name_with_values("create-data-channel", &[("input").to_value(), None::<gst::Structure>.to_value()]).ok_or("no data channel")?;
        // (made after the pipeline runs: before that webrtcbin answers with nothing)
        let channel = channel_value.get::<gst::glib::Object>().map_err(|e| format!("data channel: {e}"))?;
        {
            let sink = sink.clone();
            channel.connect("on-message-string", false, move |values| {
                let text = values[1].get::<String>().ok()?;
                if let Some(v) = parse_input_message(&text) {
                    sink(Event::Input(v));
                }
                None
            });
        }

        Ok(Stream { pipeline, webrtc })
    }

    pub fn set_answer(&self, sdp: &str) -> Result<(), String> {
        let msg = gst_sdp::SDPMessage::parse_buffer(sdp.as_bytes()).map_err(|_| "The phone's answer is not valid SDP.")?;
        let answer = gst_webrtc::WebRTCSessionDescription::new(gst_webrtc::WebRTCSDPType::Answer, msg);
        self.webrtc.emit_by_name::<()>("set-remote-description", &[&answer, &None::<gst::Promise>]);
        Ok(())
    }

    pub fn add_ice(&self, mline: u32, candidate: &str) {
        self.webrtc.emit_by_name::<()>("add-ice-candidate", &[&mline, &candidate]);
    }

    pub fn stop(&self) {
        let _ = self.pipeline.set_state(gst::State::Null);
    }
}

impl Drop for Stream {
    fn drop(&mut self) {
        self.stop();
    }
}

/// What keeps the screen-sharing session alive: dropped = the desktop stops sharing.
struct ScreenShare {
    _runtime: tokio::runtime::Runtime,
    _session: Box<dyn std::any::Any>,
}

fn token_path() -> std::path::PathBuf {
    std::path::PathBuf::from(std::env::var("HOME").unwrap_or_default()).join(".config/zohara-link/screencast-token")
}

/// Asks the desktop's screen-sharing portal for the screen. The first time the desktop shows its own permission dialog (the person
/// presses Share once); the portal's token is kept so it is not asked again. Returns the PipeWire connection and the stream's node.
fn acquire_screen() -> Result<(i32, u32, ScreenShare), String> {
    use ashpd::desktop::{screencast::{CursorMode, Screencast, SourceType}, PersistMode};
    use std::os::fd::IntoRawFd;
    let runtime = tokio::runtime::Builder::new_multi_thread().enable_all().worker_threads(2).build().map_err(|e| e.to_string())?;
    let token = std::fs::read_to_string(token_path()).ok().map(|t| t.trim().to_string()).filter(|t| !t.is_empty());
    let (fd, node, session) = runtime.block_on(async move {
        let proxy = Screencast::new().await.map_err(|e| format!("The desktop's screen sharing is not available ({e})."))?;
        let session = proxy.create_session().await.map_err(|e| format!("Screen sharing could not start ({e})."))?;
        proxy
            .select_sources(&session, CursorMode::Embedded, SourceType::Monitor.into(), false, token.as_deref(), PersistMode::ExplicitlyRevoked)
            .await
            .map_err(|e| format!("Screen sharing could not start ({e})."))?;
        let started = proxy
            .start(&session, None)
            .await
            .map_err(|e| format!("Screen sharing could not start ({e})."))?
            .response()
            .map_err(|_| "Screen sharing was not allowed on the computer. Press Share when the computer asks, then try again.".to_string())?;
        if let Some(t) = started.restore_token() {
            if let Some(dir) = token_path().parent() {
                let _ = std::fs::create_dir_all(dir);
            }
            let _ = std::fs::write(token_path(), t);
        }
        let first = started.streams().first().ok_or("The desktop shared no screen.")?;
        eprintln!("screen share: node {} type {:?} size {:?} position {:?}", first.pipe_wire_node_id(), first.source_type(), first.size(), first.position());
        // KDE can offer a blank virtual monitor instead of the real screen; streaming it shows black. Forget the choice and say so.
        if matches!(first.source_type(), Some(SourceType::Virtual)) {
            let _ = std::fs::remove_file(token_path());
            return Err("The desktop shared a blank virtual screen instead of your real one. Try again and choose your own screen in the desktop's window.".to_string());
        }
        let node = first.pipe_wire_node_id();
        let fd = proxy.open_pipe_wire_remote(&session).await.map_err(|e| format!("The screen could not be opened ({e})."))?;
        Ok::<_, String>((fd.into_raw_fd(), node, session))
    })?;
    Ok((fd, node, ScreenShare { _runtime: runtime, _session: Box::new(session) }))
}

fn nvidia_awake() -> bool {
    // read only: the sysfs file of the NVIDIA chip's power state is never touched when it is asleep
    std::fs::read_dir("/sys/class/drm")
        .map(|rd| {
            rd.flatten().any(|e| {
                let p = e.path().join("device");
                let vendor = std::fs::read_to_string(p.join("vendor")).unwrap_or_default();
                vendor.trim() == "0x10de" && std::fs::read_to_string(p.join("power/runtime_status")).map(|s| s.trim() == "active").unwrap_or(false)
            })
        })
        .unwrap_or(false)
}

fn main() {
    // `zohara-linkd-stream --grant-screen`: ask the desktop once for permission to share the screen (Settings runs this).
    if std::env::args().any(|a| a == "--grant-screen") {
        match acquire_screen() {
            Ok(_) => {
                println!("Screen sharing is allowed.");
                std::process::exit(0);
            }
            Err(e) => {
                eprintln!("{e}");
                std::process::exit(1);
            }
        }
    }
    let out = Arc::new(Mutex::new(std::io::stdout()));
    let sink: Sink = {
        let out = out.clone();
        Arc::new(move |e: Event| {
            if let Ok(mut o) = out.lock() {
                let _ = writeln!(o, "{}", e.to_json());
                let _ = o.flush();
            }
        })
    };
    let mut stream: Option<Stream> = None;
    let mut _share: Option<ScreenShare> = None;
    for line in std::io::stdin().lock().lines().map_while(Result::ok) {
        let Ok(v) = serde_json::from_str::<Value>(&line) else { continue };
        match v.get("cmd").and_then(Value::as_str) {
            Some("start") => {
                let n = |k: &str, d: u64| v.get(k).and_then(Value::as_u64).unwrap_or(d);
                let (w, h, fps, kbps) = clamp_options(n("width", 1280), n("height", 720), n("fps", 60), n("bitrate", 8000));
                let source = match v.get("source").and_then(Value::as_str) {
                    Some("pipewire") => Source::PipeWire { fd: n("fd", 0) as i32, node: n("node", 0) as u32 },
                    Some("screen") => match acquire_screen() {
                        Ok((fd, node, share)) => {
                            _share = Some(share);
                            Source::PipeWire { fd, node }
                        }
                        Err(e) => {
                            sink(Event::Error(e));
                            continue;
                        }
                    },
                    _ => Source::Test,
                };
                match Stream::start(&Options { source, width: w, height: h, fps, bitrate: kbps }, nvidia_awake(), sink.clone()) {
                    Ok(s) => stream = Some(s),
                    Err(e) => sink(Event::Error(e)),
                }
            }
            Some("answer") => {
                if let (Some(s), Some(sdp)) = (&stream, v.get("sdp").and_then(Value::as_str)) {
                    if let Err(e) = s.set_answer(sdp) {
                        sink(Event::Error(e));
                    }
                }
            }
            Some("ice") => {
                if let (Some(s), Some(c)) = (&stream, v.get("candidate").and_then(Value::as_str)) {
                    s.add_ice(v.get("mline").and_then(Value::as_u64).unwrap_or(0) as u32, c);
                }
            }
            Some("stop") => break,
            _ => {}
        }
    }
    drop(stream);
    drop(_share);
}

#[cfg(test)]
mod tests {
    use super::*;

    fn opts() -> Options {
        Options { source: Source::Test, width: 1280, height: 720, fps: 60, bitrate: 8000 }
    }

    #[test]
    fn requests_are_kept_inside_bounds() {
        assert_eq!(clamp_options(1, 1, 1, 1), (320, 240, 10, 500));
        assert_eq!(clamp_options(99999, 99999, 9999, 9_999_999), (3840, 2160, 120, 80_000));
    }

    #[test]
    fn the_intel_encoder_is_first_unless_the_nvidia_chip_is_already_awake() {
        let names = |awake| encoder_candidates(&opts(), awake).iter().map(|c| c.0).collect::<Vec<_>>();
        assert_eq!(names(false), ["vah264enc", "nvh264enc", "x264enc"]);
        assert_eq!(names(true), ["nvh264enc", "vah264enc", "x264enc"]);
    }

    #[test]
    fn the_first_installed_encoder_is_used_and_none_is_an_error() {
        let d = pipeline_description(&opts(), false, &|n| n == "x264enc" || n == "nvh264enc").unwrap();
        assert!(d.contains("nvh264enc") && !d.contains("vah264enc") && !d.contains("x264enc tune"), "{d}");
        assert!(d.contains("webrtcbin name=w") && d.contains("rtph264pay"));
        assert!(pipeline_description(&opts(), false, &|_| false).is_err());
        let pw = Options { source: Source::PipeWire { fd: 7, node: 42 }, ..opts() };
        assert!(pipeline_description(&pw, false, &|n| n == "x264enc").unwrap().starts_with("pipewiresrc fd=7 path=42"));
    }

    #[test]
    fn phone_messages_are_small_json_with_an_action() {
        assert!(parse_input_message(r#"{"action":"MOVE","dx":1,"dy":2}"#).is_some());
        assert!(parse_input_message(r#"{"no":"action"}"#).is_none());
        assert!(parse_input_message("not json").is_none());
        assert!(parse_input_message(&format!(r#"{{"action":"TYPE","text":"{}"}}"#, "a".repeat(5000))).is_none());
    }

    #[test]
    fn events_become_json_lines() {
        assert_eq!(Event::Offer("x".into()).to_json()["event"], "offer");
        assert_eq!(Event::Ice { mline: 1, candidate: "c".into() }.to_json()["mline"], 1);
        assert_eq!(Event::State("closed").to_json()["state"], "closed");
    }

    // ── a whole stream, sender and receiver in one process ──────────────────────────────────────────────────────────────

    use std::sync::atomic::{AtomicUsize, Ordering};
    use std::time::{Duration, Instant};

    /// The phone's part, made with GStreamer too: it answers the offer, counts the video packets it gets, and sends an input
    /// message through the data channel once it is open.
    struct Receiver {
        pipeline: gst::Pipeline,
        webrtc: gst::Element,
        packets: Arc<AtomicUsize>,
    }

    impl Receiver {
        fn new(send_ice: Arc<dyn Fn(u32, String) + Send + Sync>, input_json: String) -> Receiver {
            let pipeline = gst::Pipeline::new();
            let webrtc = gst::ElementFactory::make("webrtcbin").name("r").property_from_str("bundle-policy", "max-bundle").build().unwrap();
            pipeline.add(&webrtc).unwrap();
            let packets = Arc::new(AtomicUsize::new(0));
            {
                let (pipeline, packets) = (pipeline.clone(), packets.clone());
                webrtc.connect_pad_added(move |_, pad| {
                    if pad.direction() != gst::PadDirection::Src {
                        return;
                    }
                    let depay = gst::ElementFactory::make("rtph264depay").build().unwrap();
                    let sink = gst::ElementFactory::make("fakesink").property("sync", false).build().unwrap();
                    pipeline.add_many([&depay, &sink]).unwrap();
                    depay.link(&sink).unwrap();
                    let counter = packets.clone();
                    depay.static_pad("src").unwrap().add_probe(gst::PadProbeType::BUFFER, move |_, _| {
                        counter.fetch_add(1, Ordering::SeqCst);
                        gst::PadProbeReturn::Ok
                    });
                    depay.sync_state_with_parent().unwrap();
                    sink.sync_state_with_parent().unwrap();
                    pad.link(&depay.static_pad("sink").unwrap()).unwrap();
                });
            }
            webrtc.connect("on-ice-candidate", false, move |values| {
                send_ice(values[1].get::<u32>().unwrap(), values[2].get::<String>().unwrap());
                None
            });
            webrtc.connect("on-data-channel", false, move |values| {
                let ch = values[1].get::<gst::glib::Object>().unwrap();
                let (ch2, msg) = (ch.clone(), input_json.clone());
                let send = move |c: &gst::glib::Object| c.emit_by_name::<()>("send-string", &[&Some(msg.clone())]);
                let open = ch.property_value("ready-state").get::<gst_webrtc::WebRTCDataChannelState>().map(|s| s == gst_webrtc::WebRTCDataChannelState::Open).unwrap_or(false);
                if open {
                    send(&ch2);
                } else {
                    // the closure holds the channel, which GLib does not mark as thread-safe; it is only used from the callback
                    unsafe {
                        ch.connect_unsafe("on-open", false, move |_| {
                            send(&ch2);
                            None
                        });
                    }
                }
                None
            });
            pipeline.set_state(gst::State::Playing).unwrap();
            Receiver { pipeline, webrtc, packets }
        }

        /// Takes the offer, returns the answer text through `done`.
        fn answer(&self, offer_sdp: &str, done: Arc<dyn Fn(String) + Send + Sync>) {
            let msg = gst_sdp::SDPMessage::parse_buffer(offer_sdp.as_bytes()).unwrap();
            let offer = gst_webrtc::WebRTCSessionDescription::new(gst_webrtc::WebRTCSDPType::Offer, msg);
            self.webrtc.emit_by_name::<()>("set-remote-description", &[&offer, &None::<gst::Promise>]);
            let w = self.webrtc.clone();
            let promise = gst::Promise::with_change_func(move |reply| {
                let answer = reply.unwrap().unwrap().value("answer").unwrap().get::<gst_webrtc::WebRTCSessionDescription>().unwrap();
                w.emit_by_name::<()>("set-local-description", &[&answer, &None::<gst::Promise>]);
                done(answer.sdp().to_string());
            });
            self.webrtc.emit_by_name::<()>("create-answer", &[&None::<gst::Structure>, &promise]);
        }
    }

    impl Drop for Receiver {
        fn drop(&mut self) {
            let _ = self.pipeline.set_state(gst::State::Null);
        }
    }

    /// Needs GStreamer's webrtc plugin and an H.264 encoder (x264 in the test container); skipped where they are not installed.
    #[test]
    fn a_test_picture_reaches_the_receiver_and_input_comes_back() {
        let _ = gst::init();
        register_webrtc_types();
        if gst::init().is_err() || !element_exists("webrtcbin") || !element_exists("x264enc") || !element_exists("rtph264depay") {
            eprintln!("SKIPPED: GStreamer webrtc or x264 is not installed here");
            return;
        }
        let stream_cell: Arc<Mutex<Option<Arc<Stream>>>> = Arc::new(Mutex::new(None));
        let receiver_cell: Arc<Mutex<Option<Arc<Receiver>>>> = Arc::new(Mutex::new(None));
        let inputs: Arc<Mutex<Vec<Value>>> = Arc::new(Mutex::new(Vec::new()));
        let errors: Arc<Mutex<Vec<String>>> = Arc::new(Mutex::new(Vec::new()));
        let streaming = Arc::new(AtomicUsize::new(0));

        // the receiver's side of the signalling: its candidates go to the stream
        let rc = receiver_cell.clone();
        let sc = stream_cell.clone();
        let sc_for_ice = sc.clone();
        let receiver = Arc::new(Receiver::new(
            Arc::new(move |mline, cand| {
                if let Some(s) = sc_for_ice.lock().unwrap().as_ref() {
                    s.add_ice(mline, &cand);
                }
            }),
            r#"{"action":"MOVE","dx":3.5,"dy":-2}"#.to_string(),
        ));
        *rc.lock().unwrap() = Some(receiver.clone());

        let sink: Sink = {
            let (rc, sc, inputs, errors, streaming) = (receiver_cell.clone(), stream_cell.clone(), inputs.clone(), errors.clone(), streaming.clone());
            Arc::new(move |e: Event| match e {
                Event::Offer(sdp) => {
                    let sc = sc.clone();
                    let r = rc.lock().unwrap().clone().unwrap();
                    r.answer(
                        &sdp,
                        Arc::new(move |answer| {
                            if let Some(s) = sc.lock().unwrap().as_ref() {
                                s.set_answer(&answer).unwrap();
                            }
                        }),
                    );
                }
                Event::Ice { mline, candidate } => {
                    if let Some(r) = rc.lock().unwrap().as_ref() {
                        r.webrtc.emit_by_name::<()>("add-ice-candidate", &[&mline, &candidate]);
                    }
                }
                Event::Input(v) => inputs.lock().unwrap().push(v),
                Event::State("streaming") => {
                    streaming.fetch_add(1, Ordering::SeqCst);
                }
                Event::Error(m) => errors.lock().unwrap().push(m),
                _ => {}
            })
        };
        let stream = Arc::new(Stream::start(&Options { source: Source::Test, width: 640, height: 360, fps: 30, bitrate: 1500 }, false, sink).unwrap());
        *stream_cell.lock().unwrap() = Some(stream.clone());

        let end = Instant::now() + Duration::from_secs(25);
        while Instant::now() < end {
            if receiver.packets.load(Ordering::SeqCst) > 30 && !inputs.lock().unwrap().is_empty() {
                break;
            }
            std::thread::sleep(Duration::from_millis(100));
        }
        let got = receiver.packets.load(Ordering::SeqCst);
        let msgs = inputs.lock().unwrap().clone();
        let errs = errors.lock().unwrap().clone();
        stream.stop();
        assert!(errs.is_empty(), "errors: {errs:?}");
        assert!(got > 30, "only {got} video packets arrived");
        assert_eq!(msgs.first().and_then(|m| m["action"].as_str()), Some("MOVE"), "the input message did not come back: {msgs:?}");
        assert_eq!(msgs[0]["dx"], 3.5);
        assert!(streaming.load(Ordering::SeqCst) >= 1, "the connected state was not reported");
    }
}
