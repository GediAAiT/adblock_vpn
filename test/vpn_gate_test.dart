import 'dart:convert';
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

void main() {
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
