import 'dart:io';
import 'package:adblock_vpn/vpn_gate.dart';

Future<void> main(List<String> args) async {
  final servers = args.isEmpty || args.single == '--dns-fallback'
      ? await VpnGateApi(transport: args.isEmpty ? null : ForceDnsFallback())
          .fetchServers()
      : VpnGateApi.parseServers(await File(args.single).readAsString());
  stdout.writeln('Parsed ${servers.length} usable VPN Gate OpenVPN servers.');
  for (final server in servers.take(3)) {
    stdout.writeln(
        '${server.label}: ${server.speedLabel} (reported by VPN Gate)');
  }
}

/// Exercise the real encrypted DNS and HTTPS download without relying on a
/// broken system resolver on the development machine.
class ForceDnsFallback extends VpnGateTransport {
  @override
  Future<String> getText(
    Uri uri, {
    InternetAddress? connectAddress,
    Duration timeout = const Duration(seconds: 10),
    int maxBytes = 8 * 1024 * 1024,
  }) async {
    if (uri == VpnGateApi.endpoint && connectAddress == null) {
      throw const SocketException('Simulated system DNS failure');
    }
    try {
      final text = await super.getText(uri,
          connectAddress: connectAddress, timeout: timeout, maxBytes: maxBytes);
      stderr.writeln('HTTPS success: ${uri.host}, ${text.length} characters');
      return text;
    } catch (e) {
      stderr.writeln(
          'HTTPS failed: ${uri.host} via ${connectAddress?.address ?? "default"}: $e');
      rethrow;
    }
  }
}
