//! `dawngasm-clearance-helper`
//!
//! A small native process that opens the platform webview, lets the user pass a
//! browser challenge (Cloudflare Turnstile, hotaudio verification), and prints
//! the resulting cookies and User-Agent as JSON on stdout.
//!
//! The desktop app spawns this helper so the JVM process never links
//! WebKitGTK, WKWebView, or WebView2 directly.
//!
//! Protocol (one JSON request on stdin):
//!
//! ```json
//! {
//!   "url": "https://hotaudio.net/",
//!   "cookie_urls": ["https://hotaudio.net/", "https://www.hotaudio.net/"],
//!   "required_cookie": "cf_clearance",
//!   "timeout_seconds": 300,
//!   "title": "hotaudio clearance",
//!   "init_script": null
//! }
//! ```
//!
//! Response:
//!
//! ```json
//! {"ok": true, "cookie": "cf_clearance=...; ...", "user_agent": "...", "url": "..."}
//! {"ok": false, "error": {"code": "timeout", "message": "..."}}
//! ```

use std::collections::BTreeMap;
use std::io::{self, Read};
use std::sync::{Arc, Mutex};
use std::time::{Duration, Instant};

use serde::{Deserialize, Serialize};
use tao::dpi::LogicalSize;
use tao::event::{Event, WindowEvent};
use tao::event_loop::{ControlFlow, EventLoop};
use tao::platform::run_return::EventLoopExtRunReturn as _;
use tao::window::WindowBuilder;
use wry::{WebView, WebViewBuilder};

const DEFAULT_TIMEOUT_SECONDS: u64 = 300;
const MAX_TIMEOUT_SECONDS: u64 = 600;
const POLL_INTERVAL: Duration = Duration::from_millis(400);
const DEFAULT_WINDOW_TITLE: &str = "Dawngasm — clearance";

#[derive(Debug, Deserialize)]
struct Request {
    url: String,
    #[serde(default)]
    cookie_urls: Vec<String>,
    required_cookie: String,
    #[serde(default)]
    timeout_seconds: u64,
    #[serde(default)]
    title: Option<String>,
    #[serde(default)]
    init_script: Option<String>,
    #[serde(default)]
    user_agent: Option<String>,
}

impl Request {
    fn cookie_urls(&self) -> Vec<String> {
        if self.cookie_urls.is_empty() {
            vec![self.url.clone()]
        } else {
            self.cookie_urls.clone()
        }
    }

    fn timeout(&self) -> Duration {
        let seconds = if self.timeout_seconds == 0 {
            DEFAULT_TIMEOUT_SECONDS
        } else {
            self.timeout_seconds.min(MAX_TIMEOUT_SECONDS)
        };
        Duration::from_secs(seconds)
    }
}

#[derive(Debug, Serialize)]
struct Success {
    ok: bool,
    cookie: String,
    user_agent: String,
    url: String,
}

#[derive(Debug, Serialize)]
struct Failure {
    ok: bool,
    error: ErrorBody,
}

#[derive(Debug, Serialize)]
struct ErrorBody {
    code: String,
    message: String,
}

fn main() {
    match std::env::args().nth(1).as_deref() {
        Some("--describe") => {
            println!(
                "{}",
                serde_json::json!({
                    "protocol": 1,
                    "id": "clearance",
                    "name": "Dawngasm native clearance helper",
                    "version": env!("CARGO_PKG_VERSION"),
                    "description": "Opens a system webview and returns the clearance cookie.",
                })
            );
        }
        _ => run(),
    }
}

fn run() {
    let mut input = String::new();
    if let Err(error) = io::stdin().read_to_string(&mut input) {
        fail("protocol", &format!("could not read the request: {error}"));
        return;
    }
    let request: Request = match serde_json::from_str(&input) {
        Ok(request) => request,
        Err(error) => {
            fail("protocol", &format!("invalid request: {error}"));
            return;
        }
    };

    match capture(&request) {
        Ok((cookie, user_agent)) => println!(
            "{}",
            serde_json::to_string(&Success {
                ok: true,
                cookie,
                user_agent,
                url: request.url,
            })
            .unwrap_or_else(|_| "{\"ok\":false,\"error\":{\"code\":\"internal\",\"message\":\"could not encode the response\"}}".to_string())
        ),
        Err((code, message)) => fail(&code, &message),
    }
}

fn fail(code: &str, message: &str) {
    println!(
        "{}",
        serde_json::to_string(&Failure {
            ok: false,
            error: ErrorBody {
                code: code.to_string(),
                message: message.to_string(),
            },
        })
        .unwrap_or_else(|_| {
            "{\"ok\":false,\"error\":{\"code\":\"internal\",\"message\":\"could not encode the error\"}}"
                .to_string()
        })
    );
}

fn capture(request: &Request) -> Result<(String, String), (String, String)> {
    let mut event_loop = EventLoop::new();
    let title = request.title.as_deref().unwrap_or(DEFAULT_WINDOW_TITLE);
    let window = WindowBuilder::new()
        .with_title(title)
        .with_inner_size(LogicalSize::new(980.0, 760.0))
        .build(&event_loop)
        .map_err(|error| {
            (
                "window".to_string(),
                format!("could not open the clearance window: {error}"),
            )
        })?;

    let mut builder = WebViewBuilder::new().with_devtools(false);
    if let Some(user_agent) = &request.user_agent {
        builder = builder.with_user_agent(user_agent);
    }
    if let Some(script) = &request.init_script {
        builder = builder.with_initialization_script(script);
    }

    #[cfg(any(target_os = "windows", target_os = "macos"))]
    let webview = builder.build(&window).map_err(|error| {
        (
            "webview".to_string(),
            format!("could not create the clearance webview: {error}"),
        )
    })?;

    #[cfg(not(any(target_os = "windows", target_os = "macos")))]
    let webview = {
        use tao::platform::unix::WindowExtUnix as _;
        use wry::WebViewBuilderExtUnix as _;
        let vbox = window.default_vbox().ok_or_else(|| {
            (
                "webview".to_string(),
                "the clearance window has no GTK container".to_string(),
            )
        })?;
        let wv = builder.build_gtk(vbox).map_err(|error| {
            (
                "webview".to_string(),
                format!("could not create the clearance webview: {error}"),
            )
        })?;

        use wry::WebViewExtUnix as _;
        use webkit2gtk::{CookieManagerExt as _, SettingsExt as _, WebContextExt as _, WebViewExt as _};
        let gtk_wv = wv.webview();
        if let Some(settings) = gtk_wv.settings() {
            settings.set_enable_javascript(true);
            settings.set_enable_html5_local_storage(true);
            settings.set_enable_html5_database(true);
            settings.set_enable_webgl(true);
            settings.set_enable_site_specific_quirks(true);
            settings.set_enable_developer_extras(false);
            settings.set_hardware_acceleration_policy(webkit2gtk::HardwareAccelerationPolicy::Always);
        }
        if let Some(ctx) = webkit2gtk::WebContext::default() {
            if let Some(cm) = ctx.cookie_manager() {
                cm.set_accept_policy(webkit2gtk::CookieAcceptPolicy::Always);
            }
        }
        wv
    };

    clear_stale_cookies(&webview, &request.cookie_urls(), &request.required_cookie);
    let actual_user_agent = Arc::new(Mutex::new(None));
    capture_user_agent(&webview, Arc::clone(&actual_user_agent));
    webview.load_url(&request.url).map_err(|error| {
        (
            "load".to_string(),
            format!("could not load {}: {error}", request.url),
        )
    })?;

    let started = Instant::now();
    let timeout = request.timeout();
    let mut captured = None;
    let exit_code = event_loop.run_return(|event, _, control_flow| {
        *control_flow = ControlFlow::WaitUntil(Instant::now() + POLL_INTERVAL);
        match event {
            Event::WindowEvent {
                event: WindowEvent::CloseRequested,
                ..
            } => *control_flow = ControlFlow::Exit,
            Event::MainEventsCleared => {
                if started.elapsed() >= timeout {
                    *control_flow = ControlFlow::Exit;
                    return;
                }
                if let Some(cookie) = clearance_from(&webview, request) {
                    captured = Some(cookie);
                    *control_flow = ControlFlow::Exit;
                }
            }
            _ => {}
        }
    });

    if exit_code != 0 && captured.is_none() {
        return Err((
            "display".to_string(),
            "the clearance window lost its display connection".to_string(),
        ));
    }
    let cookie = captured.ok_or_else(|| {
        (
            "timeout".to_string(),
            format!(
                "the clearance window closed without a {} cookie",
                request.required_cookie
            ),
        )
    })?;
    let user_agent = actual_user_agent
        .lock()
        .ok()
        .and_then(|slot| slot.clone())
        .or_else(|| request.user_agent.clone())
        .unwrap_or_default();
    Ok((cookie, user_agent))
}

fn cookie_hosts(request: &Request) -> Vec<String> {
    let mut hosts = Vec::new();
    for raw in request.cookie_urls() {
        if let Some(host) = host_of(&raw) {
            if !hosts.contains(&host) {
                hosts.push(host);
            }
        }
    }
    hosts
}

fn host_of(raw: &str) -> Option<String> {
    let without_scheme = raw.split_once("://").map(|(_, rest)| rest).unwrap_or(raw);
    let authority = without_scheme.split('/').next()?;
    let host = authority.rsplit('@').next()?.split(':').next()?;
    let host = host.trim().trim_start_matches('.').to_ascii_lowercase();
    (!host.is_empty()).then_some(host)
}

fn cookie_belongs_to(domain: Option<&str>, hosts: &[String]) -> bool {
    match domain {
        None => true,
        Some(raw) => {
            let domain = raw.trim_start_matches('.').to_ascii_lowercase();
            hosts
                .iter()
                .any(|host| domain == *host || domain.ends_with(&format!(".{host}")))
        }
    }
}

fn clear_stale_cookies(webview: &WebView, cookie_urls: &[String], required_cookie: &str) {
    let hosts: Vec<String> = cookie_urls
        .iter()
        .filter_map(|url| host_of(url))
        .collect();
    for url in cookie_urls {
        let Ok(cookies) = webview.cookies_for_url(url) else {
            continue;
        };
        for cookie in &cookies {
            if cookie_belongs_to(cookie.domain(), &hosts) && cookie.name() == required_cookie {
                let _ = webview.delete_cookie(cookie);
            }
        }
    }
}

fn capture_user_agent(webview: &WebView, sink: Arc<Mutex<Option<String>>>) {
    let _ = webview.evaluate_script_with_callback("navigator.userAgent", move |value| {
        let user_agent = serde_json::from_str::<String>(&value).unwrap_or(value);
        if let Ok(mut slot) = sink.lock() {
            *slot = Some(user_agent);
        }
    });
}

fn clearance_from(webview: &WebView, request: &Request) -> Option<String> {
    let hosts = cookie_hosts(request);
    let mut merged: BTreeMap<String, String> = BTreeMap::new();
    let mut found_required = false;

    for url in request.cookie_urls() {
        let Ok(cookies) = webview.cookies_for_url(&url) else {
            continue;
        };
        for cookie in &cookies {
            if !cookie_belongs_to(cookie.domain(), &hosts) {
                continue;
            }
            let name = cookie.name().to_string();
            if name.is_empty() {
                continue;
            }
            if name == request.required_cookie {
                found_required = true;
            }
            merged.entry(name).or_insert_with(|| cookie.value().to_string());
        }
    }

    found_required.then(|| {
        merged
            .into_iter()
            .map(|(name, value)| format!("{name}={value}"))
            .collect::<Vec<_>>()
            .join("; ")
    })
}
