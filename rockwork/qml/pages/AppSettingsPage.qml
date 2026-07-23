import QtQuick 2.2
import Sailfish.Silica 1.0
import Sailfish.WebView 1.0
import Sailfish.WebView.Popups 1.0
import Sailfish.WebEngine 1.0

Page {
    id: appSettings

    property string uuid;
    property string url;
    property var pebble;
    allowedOrientations: Orientation.All

    // boot.rebble.io serves a desktop layout to Sailfish's stock gecko UA and its login flow
    // gates on "open this on your phone". Present a mobile Firefox UA (matching the gecko 91
    // engine) so it serves the mobile flow.
    //
    // The GLOBAL override is used, not gecko's per-domain general.useragent.override.<host> form,
    // because embedlite doesn't reliably honor per-domain overrides. To keep it from leaking onto
    // PKJS config pages (same global engine), it's applied only for the boot flow and cleared
    // again on close. It's also (re)applied once WebEngineSettings reports initialized: setting a
    // preference before the engine is up is silently dropped, which is the likely reason a plain
    // onCompleted set never took.
    property bool isBootFlow: ("" + url).indexOf("boot.rebble.io") >= 0
    property string bootUserAgent: "Mozilla/5.0 (Android 12; Mobile; rv:91.0) Gecko/91.0 Firefox/91.0"

    // Runs in the web content process (see loadFrameScript below). Walks up from the click target
    // to the enclosing <a>, and if it points at a pebble scheme, cancels the doomed navigation and
    // hands the URL back to QML. dump() lands in the journal, so a miss is diagnosable on-device.
    property string pebbleLinkCatcher:
        "addEventListener('click', function(e) {" +
        "  var n = e.target;" +
        "  while (n && n.nodeType === 1) {" +
        "    if (n.localName === 'a' && n.href &&" +
        "        (n.href.indexOf('pebble://') === 0 || n.href.indexOf('pebblejs://') === 0)) {" +
        "      e.preventDefault();" +
        "      dump('pebbleLinkCatcher: ' + n.href + '\\n');" +
        "      sendAsyncMessage('embed:pebble', { url: n.href });" +
        "      return;" +
        "    }" +
        "    n = n.parentNode;" +
        "  }" +
        "}, true);" +
        "dump('pebbleLinkCatcher installed\\n');"
    // Gate navigation to the real url until the mobile UA is set. Non-boot pages are ready at once.
    // The boot page loads about:blank first (warming the engine), then switches to boot.rebble.io
    // only after applyBootUserAgent runs — otherwise, on a cold start the engine inits and fires
    // the first request in one go, before any preference we set could apply.
    property bool bootUaReady: !isBootFlow
    function applyBootUserAgent() {
        if (!isBootFlow || !WebEngineSettings.initialized) return;
        WebEngineSettings.setPreference("general.useragent.override",
                                        bootUserAgent, WebEngineSettings.StringPref);
        bootUaReady = true;
    }
    Component.onCompleted: applyBootUserAgent()
    Component.onDestruction: {
        if (isBootFlow) {
            WebEngineSettings.setPreference("general.useragent.override", "",
                                            WebEngineSettings.StringPref);
        }
    }
    Connections {
        target: WebEngineSettings
        onInitializedChanged: applyBootUserAgent()
    }

    // Fill the whole page and let the flickable own the header, so its contentHeight spans
    // header + full web page — otherwise a config form taller than the screen can't be
    // scrolled all the way to its Save button.
    WebViewFlickable {
        id: webview
        anchors.fill: parent

        header: PageHeader {
            // The generated data: URL from pebble-clay isn't useful and looks like a bug.
            title: url.substring(0, 5) === "data:" ? qsTr("App settings") : ""
            description: url.substring(0, 5) === "data:" ? "" : url
        }

        // boot.rebble.io ends login by navigating to
        //   pebble://custom-boot-config-url/<boot-url>
        // where <boot-url> is percent-encoded and itself carries ?access_token=<TOK>&t=<ts> — so
        // in the raw url the token param appears as %3Faccess_token%3D…%26t%3D…, not a real query.
        // Decode first, then pull out just <TOK> (up to the next '&'). The old param.split("=")[1]
        // was doubly wrong even after decoding: base64 tokens contain '=' padding (truncated) and
        // it left the "&t" suffix glued on — every token handed to the daemon was corrupted.
        function extractAccessToken(u) {
            var s = "" + u;
            try { s = decodeURIComponent(s); } catch (e) {}
            var m = s.match(/[?&]access_token=([^&]*)/);
            return m ? m[1] : "";
        }

        // Clay closes the config page by navigating to pebblejs://close#<data>. Sailfish's
        // gecko (91) doesn't support the legacy chrome.manifest protocol handler that would
        // have intercepted it, so catch the navigation here instead: parse the action out of
        // the pebble URL and hand the settings back to the watchapp.
        function handlePebbleUrl(u) {
            if (u.indexOf("pebblejs://") !== 0 && u.indexOf("pebble://") !== 0)
                return false;
            console.log("pebble config close url:", u);
            var hIdx = u.indexOf("://") + 3;
            var hashIdx = u.indexOf("#");
            var action = hashIdx >= 0 ? u.substring(hIdx, hashIdx) : u.substring(hIdx);
            if (action.indexOf("close") === 0) {
                // The watchapp's webviewclosed handler (pebble-clay) wants only the fragment
                // after '#' — the URL-encoded config JSON — not the whole pebblejs://close URL.
                var response = hashIdx >= 0 ? u.substring(hashIdx + 1) : "";
                pebble.configurationClosed(appSettings.uuid, response);
                pageStack.pop();
            } else if (action.indexOf("custom-boot-config-url") === 0) {
                var token = extractAccessToken(u);
                if (token) pebble.setOAuthToken(token);
                pageStack.pop();
            }
            return true;
        }

        webView {
            clip: true
            focus: true
            active: true

            // about:blank until the boot UA is applied (see bootUaReady); non-boot pages load
            // appSettings.url straight away.
            url: bootUaReady ? appSettings.url : "about:blank"
            // Fires for navigations gecko actually performs (e.g. Clay's pebblejs://close). It
            // does NOT fire for the boot flow's pebble://custom-boot-config-url link: gecko hands
            // unknown schemes to the external-protocol path without changing location, so that
            // one is caught by the injected frame script below instead.
            onUrlChanged: handlePebbleUrl("" + webView.url)
            onViewInitialized: {
                webView.addMessageListener("embed:pebble");
                // Inject a content script that catches clicks on pebble://pebblejs:// links —
                // which gecko silently refuses to navigate to — and posts the URL back, the only
                // content->QML channel available (RawWebView exposes no navigation-request signal
                // carrying a URL). Inlined as a data: URI so there's no packaged file to locate or
                // read past the sandbox.
                webView.loadFrameScript("data:text/javascript," + encodeURIComponent(pebbleLinkCatcher));
            }
            onRecvAsyncMessage: {
                if (message === "embed:pebble" && data && data.url) {
                    console.log("embed:pebble caught:", data.url);
                    handlePebbleUrl("" + data.url);
                }
            }
        }
    }
}
