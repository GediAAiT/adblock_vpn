import 'dart:convert';
import 'dart:async';
import 'dart:io';
import 'package:flutter_test/flutter_test.dart';
import 'package:adblock_vpn/vpn_gate.dart';

const profile = '''client
dev tun
proto tcp
remote 219.100.37.121 443
cipher AES-128-CBC
<ca>
certificate-data
</ca>
<cert>
client-certificate
</cert>
<key>
client-key
</key>
''';

String csvRow(String host, int score, {String config = profile}) =>
    '$host,219.100.37.121,$score,20,10000000,"Japan, East",JP,1,1,1,1,2weeks,operator,message,${base64Encode(utf8.encode(config))}\r\n';

String csv(String rows) => '*vpn_servers\r\n'
    '#HostName,IP,Score,Ping,Speed,CountryLong,CountryShort,NumVpnSessions,Uptime,TotalUsers,TotalTraffic,LogType,Operator,Message,OpenVPN_ConfigData_Base64\r\n'
    '$rows*\r\n';

class FakeTransport extends VpnGateTransport {
  FakeTransport(this.read);
  final Future<String> Function(Uri, InternetAddress?) read;
  final calls = <(Uri, InternetAddress?)>[];

  @override
  Future<String> getText(
    Uri uri, {
    InternetAddress? connectAddress,
    Duration timeout = const Duration(seconds: 10),
    int maxBytes = 8 * 1024 * 1024,
  }) {
    calls.add((uri, connectAddress));
    return read(uri, connectAddress);
  }
}

String dnsAnswer(List<String> addresses) => jsonEncode({
      'Status': 0,
      'Answer': [
        for (final address in addresses) {'type': 1, 'data': address}
      ],
    });

void main() {
  test('IP override sends TLS with original hostname, never plaintext HTTP',
      () async {
    final server = await ServerSocket.bind(InternetAddress.loopbackIPv4, 0);
    final firstRecord = Completer<List<int>>();
    final sockets = <Socket>[];
    final subscription = server.listen((socket) {
      sockets.add(socket);
      final bytes = <int>[];
      socket.listen((chunk) {
        bytes.addAll(chunk);
        if (bytes.length >= 5 &&
            (bytes[0] != 22 ||
                bytes.length >= 5 + (bytes[3] << 8) + bytes[4])) {
          if (!firstRecord.isCompleted) firstRecord.complete(bytes);
          socket.destroy();
        }
      });
    });
    try {
      await expectLater(
          VpnGateTransport().getText(
            Uri.parse('https://www.vpngate.net:${server.port}/api/iphone/'),
            connectAddress: InternetAddress.loopbackIPv4,
            timeout: const Duration(seconds: 3),
          ),
          throwsA(isA<IOException>()));
      final record =
          await firstRecord.future.timeout(const Duration(seconds: 3));
      expect(record.first, 22); // TLS handshake record.
      expect(latin1.decode(record), contains('www.vpngate.net')); // SNI.
    } finally {
      for (final socket in sockets) {
        socket.destroy();
      }
      await subscription.cancel();
      await server.close();
    }
  });

  test('normal discovery needs no fallback', () async {
    final transport =
        FakeTransport((uri, address) async => csv(csvRow('live', 1)));
    final servers = await VpnGateApi(transport: transport).fetchServers();
    expect(servers.single.hostName, 'live');
    expect(transport.calls, [(VpnGateApi.endpoint, null)]);
  });

  test('failed hostname lookup uses HTTPS DNS and preserves original HTTPS URL',
      () async {
    final transport = FakeTransport((uri, address) async {
      if (uri == VpnGateApi.endpoint && address == null) {
        throw const SocketException('Failed host lookup');
      }
      if (uri.host == '1.1.1.1') {
        expect(uri.scheme, 'https');
        expect(uri.queryParameters['name'], 'www.vpngate.net');
        return dnsAnswer(['130.158.75.39', '130.158.75.40']);
      }
      expect(uri, VpnGateApi.endpoint);
      if (address!.address == '130.158.75.39') {
        throw const SocketException('Unreachable');
      }
      return csv(csvRow('recovered', 1));
    });
    final servers = await VpnGateApi(transport: transport).fetchServers();
    expect(servers.single.hostName, 'recovered');
    expect(transport.calls.length, 4);
    expect(transport.calls.last.$2!.address, '130.158.75.40');
  });

  test('timeout tries alternate DNS endpoint and rejects private DNS answers',
      () async {
    final transport = FakeTransport((uri, address) async {
      if (uri == VpnGateApi.endpoint && address == null) {
        throw TimeoutException('DNS timeout');
      }
      if (uri.host == '1.1.1.1') {
        throw const SocketException('Resolver unavailable');
      }
      if (uri.host == '8.8.8.8') {
        return dnsAnswer([
          '127.0.0.1',
          '10.0.0.1',
          '192.168.1.1',
          '169.254.169.254',
          '::1',
          'invalid',
          '130.158.75.40'
        ]);
      }
      expect(address!.address, '130.158.75.40');
      return csv(csvRow('recovered', 1));
    });
    expect((await VpnGateApi(transport: transport).fetchServers()).length, 1);
    expect(transport.calls.length, 4);
  });

  test('offline failure gives actionable error after bounded retries',
      () async {
    final transport = FakeTransport((uri, address) async {
      throw const SocketException('errno = 7');
    });
    await expectLater(
        VpnGateApi(transport: transport).fetchServers(),
        throwsA(isA<VpnGateLoadException>().having(
            (e) => e.message,
            'message',
            allOf(contains('secure DNS'), contains('Local ad blocker'),
                isNot(contains('errno'))))));
    expect(transport.calls.length, 3);
  });

  test('certificate failure is never bypassed by DNS recovery', () async {
    final transport = FakeTransport((uri, address) async {
      throw const HandshakeException('Invalid certificate');
    });
    await expectLater(
        VpnGateApi(transport: transport).fetchServers(),
        throwsA(isA<VpnGateLoadException>().having(
            (e) => e.message, 'message', contains('secure connection'))));
    expect(transport.calls.length, 1);
  });

  test('malformed and empty DNS replies never become connection targets',
      () async {
    final transport = FakeTransport((uri, address) async {
      if (uri == VpnGateApi.endpoint) throw const SocketException('DNS failed');
      return uri.host == '1.1.1.1' ? '<html>portal</html>' : '{"Status":3}';
    });
    await expectLater(VpnGateApi(transport: transport).fetchServers(),
        throwsA(isA<VpnGateLoadException>()));
    expect(transport.calls.length, 3);
  });

  test('parses quoted CSV, sorts candidates, and skips corrupt profiles', () {
    final servers = VpnGateApi.parseServers(csv(
      '${csvRow('slow', 10)}${csvRow('broken', 30, config: 'invalid')}${csvRow('fast', 20)}',
    ));
    expect(servers.map((s) => s.hostName), ['fast', 'slow']);
    expect(servers.first.country, 'Japan, East');
    expect(servers.first.config, contains('dhcp-option DNS 94.140.14.14'));
  });

  test('rejects HTML errors and empty server lists', () {
    expect(() => VpnGateApi.parseServers('<html>blocked</html>'),
        throwsFormatException);
    expect(() => VpnGateApi.parseServers(csv('')), throwsFormatException);
  });

  test('pins endpoint and filtering DNS and strips scripts, routes, file paths',
      () {
    final result = buildFilteredConfig(
        '$profile\n'
            'dhcp-option DNS 8.8.8.8\nup /tmp/script\nscript-security 2\n'
            'route 10.0.0.0 255.0.0.0\nca /tmp/other-ca\nauth-user-pass /tmp/password\n',
        '219.100.37.121');
    expect(result, isNot(contains('8.8.8.8')));
    expect(result, isNot(contains('/tmp/')));
    expect(result, isNot(contains('script-security')));
    expect(result, contains('auth-user-pass\n'));
    expect(result, contains('redirect-gateway def1'));
    expect(result, contains('<key>\nclient-key\n</key>'));
  });

  test('rejects profiles redirecting to an unexpected server', () {
    expect(
        () => buildFilteredConfig(profile, '8.8.8.8'), throwsFormatException);
    expect(() => buildFilteredConfig('$profile<ca>\nbad', '219.100.37.121'),
        throwsFormatException);
  });
}
