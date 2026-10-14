# AdBlock VPN for Android

Flutter app with two protection modes:

- **Local ad blocker:** filters DNS requests against the bundled ad/tracker list and forwards allowed queries to AdGuard's public filtering DNS. Normal video/browsing traffic stays on your connection. This does not change your public IP.
- **Free VPN:** fetches volunteer servers from VPN Gate's free HTTPS API, lets you select a server, and opens a real OpenVPN tunnel with AdGuard DNS configured inside it. Server-pushed DNS cannot override the filtering resolvers.

Android permits one active VPN at a time. Stop protection before switching modes. The local blocklist applies in local mode; remote mode uses AdGuard's public blocklists. Remote mode reports transferred bytes because the public resolver does not expose a per-device blocked-ad counter.

## Free services used

- Server discovery: https://www.vpngate.net/api/iphone/ (public GET endpoint, CSV with Base64 OpenVPN profiles, no API key).
- VPN Gate documentation: https://www.vpngate.net/en/about.aspx
- AdGuard DNS documentation: https://adguard-dns.io/en/public-dns.html
- Filtering DNS: `94.140.14.14` and `94.140.15.15`.

HTTP discovers the servers and configuration. OpenVPN carries device traffic; a REST request alone cannot establish a system VPN. VPN Gate servers are operated by volunteers, can go offline, and keep connection logs. They do not provide guaranteed uptime, speed, or privacy. The reported server score/speed comes from VPN Gate and is not a measurement from your phone.

## Build with Java 25

The wrapper uses Gradle 9.1.0, which supports running on Java 25. Android app bytecode still targets Java 17 for device compatibility.

```powershell
$env:JAVA_HOME = 'C:/Program Files/OpenLogic/jdk-25.0.2.10-hotspot'
$env:Path = "$env:JAVA_HOME/bin;$env:Path"
flutter pub get
flutter analyze
flutter test
flutter build apk --release
```

APK: `build/app/outputs/flutter-apk/app-release.apk`.

The project currently signs release builds with the development debug key. Configure your own signing key before distributing a production release.

## Use

1. Install the APK and open AdBlock.
2. Select **Local ad blocker** for local domain filtering, or **Free VPN** to load available servers.
3. In Free VPN mode, choose a server and tap **Start protection**.
4. Accept Android's VPN connection request. The screen shows connected only after the native tunnel reports readiness.
5. If a volunteer server fails, refresh the list and choose another. Connections time out after 45 seconds while the app is open; the cancel button stops a pending attempt.

## Ad-blocking limits and verification

### App compatibility (local mode)

If HDO Box or another app stops when filtering is enabled, stop protection and
open **App compatibility**. Enable **Compatibility mode**, save, and start local
protection again. This returns NOERROR with no answers for blocked queries,
uses Cloudflare's non-filtering DNS (`1.1.1.1`, `1.0.0.1`) for permitted queries,
and routes only the VPN's virtual DNS addresses. Public resolver IPs and known
encrypted DNS hostnames remain reachable, avoiding broken HTTPS/TCP connections
to intercepted resolver addresses. The bundled ad blocklist still applies to
queries reaching the local VPN. Apps using their own DNS may show more ads.

If an app still refuses to work, stop protection and open **App compatibility →
Apps that bypass protection**. Search for the installed app, select it, save,
restart local protection, and close and reopen the affected app. Selected apps
use the normal network outside the local VPN and are not ad-filtered by this
app; other apps remain protected. Selections persist, apply in either local
mode, and can be removed to restore filtering. The picker lists launchable
apps without requesting unrestricted installed-app access. Uninstalled app
selections can be removed and do not prevent the VPN from starting. Android's
VPN indicator remains visible, and some apps may still detect the VPN.

For a remaining failure, reproduce it and open **App compatibility → Refresh**
to inspect the latest 30 locally blocked domain names. Stop protection, add a
specific hostname using **Allow** or the text field, save, restart protection,
and reopen the affected app. Exceptions persist and apply in both local modes;
each exact hostname and its DNS alias chain can resolve through non-filtering
DNS. Subdomains need separate entries. Remove an exception to restore filtering.
Exceptions affect all apps and can allow ads or tracking, so add only domains
you have tested. No HDO-specific domains are guessed or automatically allowed.

The recent list covers all apps without attributing requests to an app, stays
only in process memory, and clears when local protection starts or when you
tap **Clear history**. Settings cannot be saved while protection is active.
These settings do not change Free VPN mode.

**Stop protection** sends an explicit shutdown command to the local VPN, closes
its tunnel and active DNS sockets, cancels queued DNS work and pending startup,
and removes its notification. The notification also has a **Stop protection**
action. Cancelling a remote connection does not require downloading a server
list; a late start response cannot replace the cancellation result. Failed
stop requests are shown in the app so they can be retried.

This is a compatibility aid, not guaranteed anti-detection: Android exposes VPN
state and apps can detect missing ad content even with successful DNS replies.
DNS filtering cannot synthesize valid HTTPS ad responses or hide the VPN from
Android. HDO Box playback and detection behavior require testing on your device.

This can block known third-party advertising domains used by SDKs such as AdMob, AppLovin, Vungle and Unity Ads. It cannot promise 100% ad removal in MovieBox, HDO Stream, or any other app. Ads sharing a domain with video/API content, cached ads, direct-IP advertising, and apps using their own encrypted DNS can bypass DNS filtering. Blocking shared video domains can break playback. Android Private DNS can also bypass the VPN's DNS settings; test with Private DNS off when diagnosing ads.

In local mode, the counter measures locally blocked DNS requests, not visible ads or additional blocks performed by AdGuard. With compatibility mode off, known resolver IP routes are intercepted, but this service only processes UDP DNS; TCP DNS and non-DNS connections to those IPs are not supported. Compatibility mode leaves public resolver IPs outside those routes. Use remote mode if you need a full traffic tunnel.

Validate on the actual phone and app versions:

1. Confirm an ordinary website and a video still load with protection enabled.
2. Confirm a known ad domain such as `doubleclick.net` fails to resolve while an ordinary domain resolves. In local mode the blocked-request counter should increase for locally listed domains.
3. In Free VPN mode verify your public IP changes, then test disconnect/reconnect and network changes.
4. Close and reopen the target app to clear existing connections; compare the same app actions with protection off and on. Ads already cached may persist.
5. Check denial of VPN permission, server timeout, stopping while starting, and revoking VPN permission in Android Settings.

Automated checks cover CSV quoting/sorting, malformed profiles, endpoint matching, stripping external scripts/files/routes, filtering DNS configuration, and home-screen rendering. `dart run tool/check_vpn_api.dart` checks live server discovery; add a downloaded CSV path to validate a response offline. A successful APK build does not establish that a volunteer server connects or that a particular app's ads are blocked.

## Implementation

- `lib/vpn_gate.dart`: bounded HTTPS discovery, CSV parsing, ranked server models, profile validation.
- `lib/main.dart`: mode/server selection, native state polling, cancellation and connection timeout.
- `MainActivity.kt`: Android VPN consent and OpenVPN profile setup with forced filtering DNS.
- `RemoteVpnService.kt`: pinned native engine adapter and actual connection/traffic state.
- `AdBlockVpnService.kt`: local DNS blocker, bounded DNS worker queue, permission-revocation/startup cleanup.

Profiles only retain transport/cryptographic directives and inline certificates/keys. DNS, routes and app bypass settings are controlled by the app. The native OpenVPN engine is pinned to `com.github.nizwar:openvpn_library:b3941ef040`, obtained from JitPack. Review its native-library compatibility on your target Android devices, including devices with 16 KB memory pages, before shipping.

The `android_overlay` folder is a historical reference; builds use `android/app/src/main` directly.

OpenVPN engine source and licenses: https://github.com/nizwar/openvpn_library and https://github.com/schwabe/ics-openvpn. Follow their license requirements when distributing.
