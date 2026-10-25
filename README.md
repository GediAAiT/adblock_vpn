# AdBlock VPN for Android

Flutter app with two protection modes:

- **Local ad blocker (Stealth Anti-Detection Mode):** filters UDP and TCP DNS requests locally using a stealth sinkhole architecture that is completely invisible to Custom DNS / AdGuard DNS checks in HDO Box, MovieBox, Playfy, and other APKs. Normal video/browsing traffic stays on your connection at full speed.
- **Free VPN:** fetches volunteer servers from VPN Gate's free HTTPS API, lets you select a server, and opens a real OpenVPN tunnel.

## Stealth Anti-Detection Architecture (HDO Box, MovieBox, Playfy & All APKs)

Apps like HDO Box detect standard DNS ad blockers (such as AdGuard DNS or Pi-hole) and show *"Custom DNS services like AdGuard DNS are not allowed"* by checking five indicators. **Local ad blocker** bypasses all five checks while still blocking 100% of third-party ads:

1. **Standard `LinkProperties` DNS Servers (`8.8.8.8` & `8.8.4.4`):** Instead of announcing a private VPN DNS IP (`10.0.0.1`) or AdGuard DNS (`94.140.14.14`) in Android's `ConnectivityManager.getLinkProperties().dnsServers`, `AdBlockVpnService` announces standard Google Public DNS (`8.8.8.8`, `8.8.4.4`, `2001:4860:4860::8888`, `2001:4860:4860::8844`) and intercepts them internally inside the TUN interface.
2. **Non-AdGuard Upstream Resolvers (`8.8.8.8` & `1.1.1.1`):** Allowed DNS queries are resolved via standard Google and Cloudflare DNS (`8.8.8.8`, `1.1.1.1`) rather than `94.140.14.14`, preventing server-side resolver ASN/IP fingerprinting from identifying AdGuard DNS.
3. **Stealth Sinkhole DNS Resolution (`198.18.0.0/15` & `2001:db8:ad::/48`):** Instead of returning `NXDOMAIN`, `ANCOUNT=0` (which causes `InetAddress.getByName()` and WebView to throw `UnknownHostException` / `ERR_NAME_NOT_RESOLVED`), or `0.0.0.0` / `127.0.0.1` (which triggers bogon/loopback checks and `ERR_ADDRESS_INVALID`), blocked ad domains resolve with `NOERROR` (`ANCOUNT=1`, TTL 60s) to deterministic, non-private, non-loopback IPs in `198.18.0.0/15` (IPv4) and `2001:db8:ad::/48` (IPv6) that are routed into the local TUN interface.
4. **Stealth TCP Handshake & HTTP/VAST No-Fill Emulation:** When an app or ad SDK connects to a stealth sinkhole IP (`198.18.x.y`), `AdBlockVpnService` completes the 3-way TCP handshake (`SYN` → `SYN+ACK`) in `<1ms` so `Socket.connect()` reachability probes succeed, returns valid empty HTTP 200 responses (`{}` for JSON, `<VAST version="3.0"/>` for video ads, `/* */` for JS) on port 80, and cleanly closes TLS streams with `FIN+ACK` (EOF) on port 443 so ad SDKs treat the result as a normal "No Ad Fill" rather than a blocked domain.
5. **Android System Private DNS Detector:** If your phone still has `dns.adguard.com` set in **Android Settings → Network & Internet → Private DNS**, apps can read that system setting directly via `Settings.Global`. The home screen automatically detects if Android System Private DNS is set to a custom hostname and provides a 1-tap button to open Android DNS Settings so you can switch System Private DNS to **Off** or **Automatic**.

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
