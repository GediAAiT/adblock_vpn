import 'dart:io';
import 'package:adblock_vpn/vpn_gate.dart';

Future<void> main(List<String> args) async {
  final servers = args.isEmpty
      ? await VpnGateApi().fetchServers()
      : VpnGateApi.parseServers(await File(args.single).readAsString());
  stdout.writeln('Parsed ${servers.length} usable VPN Gate OpenVPN servers.');
  for (final server in servers.take(3)) {
    stdout.writeln(
        '${server.label}: ${server.speedLabel} (reported by VPN Gate)');
  }
}
