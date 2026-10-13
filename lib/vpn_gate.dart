import 'dart:async';
import 'dart:convert';
import 'dart:io';

/// VPN Gate's public HTTPS API returns a CSV server list with OpenVPN profiles.
class VpnGateApi {
  static final endpoint = Uri.parse('https://www.vpngate.net/api/iphone/');

  Future<List<VpnServer>> fetchServers() async {
    final client = HttpClient()
      ..connectionTimeout = const Duration(seconds: 15);
    try {
      return await _fetch(client).timeout(const Duration(seconds: 30));
    } finally {
      client.close(force: true);
    }
  }

  Future<List<VpnServer>> _fetch(HttpClient client) async {
    final request = await client.getUrl(endpoint);
    request.followRedirects = false;
    request.headers.set(HttpHeaders.acceptHeader, 'text/plain,text/csv');
    final response = await request.close();
    if (response.statusCode != HttpStatus.ok) {
      throw HttpException('VPN Gate returned HTTP ${response.statusCode}');
    }
    final bytes = <int>[];
    await for (final chunk in response) {
      if (bytes.length + chunk.length > 8 * 1024 * 1024) {
        throw const FormatException('VPN server list is too large');
      }
      bytes.addAll(chunk);
    }
    return parseServers(utf8.decode(bytes));
  }

  static List<VpnServer> parseServers(String csv) {
    final rows = _readCsv(csv);
    final index =
        rows.indexWhere((row) => row.isNotEmpty && row.first == '#HostName');
    if (index < 0) {
      throw const FormatException('Unrecognized VPN Gate response');
    }
    final header = rows[index];
    final servers = <VpnServer>[];
    final seen = <String>{};
    for (final row in rows.skip(index + 1)) {
      if (row.length != header.length || row.first.startsWith('*')) continue;
      final values = Map<String, String>.fromIterables(header, row);
      try {
        final ip = values['IP'] ?? '';
        final address = InternetAddress.tryParse(ip);
        if (address == null || address.type != InternetAddressType.IPv4) {
          continue;
        }
        final octets = address.rawAddress;
        if (octets[0] == 0 ||
            octets[0] == 10 ||
            octets[0] == 127 ||
            octets[0] >= 224 ||
            (octets[0] == 169 && octets[1] == 254) ||
            (octets[0] == 172 && octets[1] >= 16 && octets[1] <= 31) ||
            (octets[0] == 192 && octets[1] == 168)) {
          continue;
        }
        final encoded = values['OpenVPN_ConfigData_Base64'] ?? '';
        if (encoded.isEmpty || encoded.length > 256 * 1024) continue;
        final config =
            buildFilteredConfig(utf8.decode(base64Decode(encoded)), ip);
        final host = values['#HostName'] ?? '';
        if (!seen.add('$host/$ip')) continue;
        servers.add(VpnServer(
          hostName: host,
          ip: ip,
          country: values['CountryLong'] ?? 'Unknown',
          countryCode: values['CountryShort'] ?? '',
          score: int.tryParse(values['Score'] ?? '') ?? 0,
          ping: int.tryParse(values['Ping'] ?? '') ?? -1,
          speed: int.tryParse(values['Speed'] ?? '') ?? 0,
          config: config,
        ));
      } on FormatException {
        continue;
      }
    }
    servers.sort((a, b) => b.score.compareTo(a.score));
    if (servers.isEmpty) {
      throw const FormatException('No usable OpenVPN servers. Refresh later.');
    }
    return servers;
  }

  static List<List<String>> _readCsv(String input) {
    final rows = <List<String>>[];
    var row = <String>[];
    var field = StringBuffer();
    var quoted = false;
    for (var i = 0; i < input.length; i++) {
      final c = input[i];
      if (c == '"') {
        if (quoted && i + 1 < input.length && input[i + 1] == '"') {
          field.write('"');
          i++;
        } else {
          quoted = !quoted;
        }
      } else if (!quoted && c == ',') {
        row.add(field.toString());
        field = StringBuffer();
      } else if (!quoted && (c == '\n' || c == '\r')) {
        if (c == '\r' && i + 1 < input.length && input[i + 1] == '\n') i++;
        row.add(field.toString());
        rows.add(row);
        row = <String>[];
        field = StringBuffer();
      } else {
        field.write(c);
      }
    }
    if (quoted) throw const FormatException('Malformed server CSV');
    if (field.isNotEmpty || row.isNotEmpty) {
      row.add(field.toString());
      rows.add(row);
    }
    return rows;
  }
}

class VpnServer {
  const VpnServer(
      {required this.hostName,
      required this.ip,
      required this.country,
      required this.countryCode,
      required this.score,
      required this.ping,
      required this.speed,
      required this.config});
  final String hostName;
  final String ip;
  final String country;
  final String countryCode;
  final int score;
  final int ping;
  final int speed;
  final String config;
  String get id => '$hostName/$ip';
  String get label => '$country · $hostName';
  String get speedLabel => '${(speed / 1000000).toStringAsFixed(1)} Mbps';
}

/// Routes, DNS, scripts, file paths and app exclusions are owned by the app.
String buildFilteredConfig(String config, String expectedIp) {
  const allowed = {
    'client',
    'dev',
    'proto',
    'cipher',
    'data-ciphers',
    'data-ciphers-fallback',
    'auth',
    'nobind',
    'persist-key',
    'persist-tun',
    'verb',
    'remote-cert-tls',
    'verify-x509-name',
    'tls-version-min',
    'tls-cipher',
    'tls-ciphersuites',
    'key-direction'
  };
  const inlineBlocks = {'ca', 'cert', 'key', 'tls-auth', 'tls-crypt'};
  final lines = <String>[];
  final blocks = <String>{};
  String? block;
  var hasRemote = false;
  for (final raw in const LineSplitter().convert(config)) {
    final line = raw.trim();
    if (block != null) {
      lines.add(raw);
      if (line == '</$block>') block = null;
      continue;
    }
    if (line.isEmpty || line.startsWith('#') || line.startsWith(';')) continue;
    if (line.startsWith('<')) {
      final name = line.replaceAll(RegExp('[<>]'), '');
      if (!inlineBlocks.contains(name) || !blocks.add(name)) {
        throw const FormatException('Unexpected inline VPN profile block');
      }
      block = name;
      lines.add(line);
      continue;
    }
    final tokens = line.split(RegExp(r'\s+'));
    final directive = tokens.first.toLowerCase();
    if (directive == 'remote') {
      if (tokens.length < 3 || tokens[1] != expectedIp) {
        throw const FormatException(
            'VPN profile endpoint does not match server');
      }
      final port = int.tryParse(tokens[2]);
      if (port == null || port < 1 || port > 65535) {
        throw const FormatException('Invalid VPN server port');
      }
      if (!hasRemote) lines.add('remote $expectedIp $port');
      hasRemote = true;
    } else if (directive == 'auth-user-pass') {
      lines.add('auth-user-pass');
    } else if (allowed.contains(directive)) {
      lines.add(line);
    }
  }
  if (block != null || !hasRemote || !blocks.contains('ca')) {
    throw const FormatException('Incomplete VPN Gate profile');
  }
  lines.addAll([
    'resolv-retry 15',
    'connect-retry 2 5',
    'connect-retry-max 2',
    'redirect-gateway def1',
    'dhcp-option DNS 94.140.14.14',
    'dhcp-option DNS 94.140.15.15',
    'pull-filter ignore "dhcp-option"'
  ]);
  return '${lines.join('\n')}\n';
}
