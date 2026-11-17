package dev.dawngasm.app.ui.components

object ClearanceScripts {
    /**
     * JavaScript injected into audiochan.com to render Cloudflare Turnstile
     * and communicate completion to either Android JavascriptInterface or iOS
     * WKScriptMessageHandler.
     */
    val AUDIOCHAN_TURNSTILE_INJECTION: String = """
        (function() {
            if (window.__dawngasm_injected) return;
            window.__dawngasm_injected = true;

            function log(msg) {
                console.log('[Dawngasm-Clearance] ' + msg);
            }

            function setupWidget() {
                var container = document.getElementById('dawngasm-verify-box');
                if (!container) {
                    container = document.createElement('div');
                    container.id = 'dawngasm-verify-box';
                    container.style.cssText = 'position:fixed;top:16px;left:50%;transform:translateX(-50%);z-index:999999;background:#181825;color:#cdd6f4;padding:20px;border-radius:16px;box-shadow:0 8px 32px rgba(0,0,0,0.55);text-align:center;max-width:92%;width:340px;font-family:system-ui,-apple-system,sans-serif;border:1px solid #45475a;';
                    container.innerHTML = '<div style="font-weight:600;font-size:16px;margin-bottom:6px;">Playback Verification</div><div id="dawngasm-verify-status" style="font-size:13px;color:#a6adc8;margin-bottom:14px;">Complete the check below to enable streaming on audiochan.</div><div id="dawngasm-turnstile-target" style="display:flex;justify-content:center;min-height:65px;"></div><div id="dawngasm-verify-msg" style="margin-top:10px;font-size:12px;color:#89b4fa;"></div>';
                    document.body.appendChild(container);
                }

                window.__dawngasmTurnstileReady = function() {
                    log('Turnstile API ready! Calling turnstile.ready()...');
                    if (window.turnstile && typeof window.turnstile.ready === 'function') {
                        window.turnstile.ready(renderWidget);
                    } else {
                        renderWidget();
                    }
                };

                if (!window.turnstile) {
                    log('Injecting Turnstile API script tag...');
                    var s = document.createElement('script');
                    s.src = 'https://challenges.cloudflare.com/turnstile/v0/api.js?onload=__dawngasmTurnstileReady&render=explicit';
                    s.async = false;
                    document.head.appendChild(s);
                } else if (typeof window.turnstile.ready === 'function') {
                    window.turnstile.ready(renderWidget);
                } else {
                    renderWidget();
                }
            }

            function renderWidget() {
                var target = document.getElementById('dawngasm-turnstile-target');
                var msg = document.getElementById('dawngasm-verify-msg');
                var status = document.getElementById('dawngasm-verify-status');
                if (!target || !window.turnstile || typeof window.turnstile.render !== 'function') return;

                try {
                    var widgetId = window.turnstile.render(target, {
                        sitekey: '0x4AAAAAAAzFFy-NNEmM0ZDp',
                        action: 'audio_playback',
                        retry: 'auto',
                        'retry-interval': 2000,
                        'refresh-expired': 'auto',
                        callback: function(token) {
                            if (status) status.innerText = 'Token verified! Verifying with audiochan...';
                            if (msg) msg.innerText = 'Submitting playback verification token...';
                            fetch('https://api.audiochan.com/audios/playback-verification', {
                                method: 'POST',
                                headers: {
                                    'Content-Type': 'application/json',
                                    'Accept': 'application/json'
                                },
                                credentials: 'include',
                                body: JSON.stringify({ turnstile_token: token })
                            })
                            .then(function(r) { return r.json(); })
                            .then(function(data) {
                                if (data && data.success) {
                                    if (msg) {
                                        msg.style.color = '#a6e3a1';
                                        msg.innerText = 'Verified! Saving clearance...';
                                    }
                                    if (window.AudiochanBridge) {
                                        window.AudiochanBridge.reportVerified();
                                    }
                                    if (window.webkit && window.webkit.messageHandlers && window.webkit.messageHandlers.audiochanBridge) {
                                        window.webkit.messageHandlers.audiochanBridge.postMessage('verified');
                                    }
                                } else {
                                    if (msg) {
                                        msg.style.color = '#f38ba8';
                                        msg.innerText = 'Verification failed: ' + (data && data.message ? data.message : 'Please retry.');
                                    }
                                }
                            })
                            .catch(function(err) {
                                if (msg) {
                                    msg.style.color = '#f38ba8';
                                    msg.innerText = 'Network error: ' + err;
                                }
                            });
                        },
                        'error-callback': function(code) {
                            log('Turnstile reported error: ' + code);
                            if (msg) {
                                msg.style.color = '#f38ba8';
                                msg.innerText = 'Challenge error (' + code + '). Retrying...';
                            }
                        },
                        'expired-callback': function() {
                            log('Turnstile token expired.');
                            if (msg) msg.innerText = 'Token expired, refreshing...';
                        }
                    });
                    log('Widget rendered with id: ' + widgetId);
                } catch(e) {
                    log('Exception in renderWidget: ' + e);
                }
            }

            if (document.readyState === 'complete' || document.readyState === 'interactive') {
                setupWidget();
            } else {
                window.addEventListener('DOMContentLoaded', setupWidget);
            }
        })();
    """.trimIndent()

    /**
     * JavaScript injected into hotaudio.net to query /cdn-cgi/trace and report the client's
     * detected public IP address to the host application bridge.
     */
    val HOTAUDIO_TRACE_INJECTION: String = """
        (function() {
            try {
                fetch('/cdn-cgi/trace')
                    .then(function(r) { return r.text(); })
                    .then(function(t) {
                        if (window.ClearanceBridge) {
                            window.ClearanceBridge.reportTrace(t);
                        }
                        if (window.webkit && window.webkit.messageHandlers && window.webkit.messageHandlers.clearanceBridge) {
                            window.webkit.messageHandlers.clearanceBridge.postMessage(t);
                        }
                    })
                    .catch(function() {});
            } catch(e) {}
        })();
    """.trimIndent()
}
