import 'package:flutter/material.dart';
import 'package:flutter/services.dart';

class CompatibilityPage extends StatefulWidget {
  const CompatibilityPage({super.key, required this.readOnly});

  final bool readOnly;

  @override
  State<CompatibilityPage> createState() => _CompatibilityPageState();
}

class _CompatibilityPageState extends State<CompatibilityPage> {
  static const _channel = MethodChannel('com.example.adblock_vpn/vpn');
  final _domains = TextEditingController();
  bool _enabled = false;
  bool _loading = true;
  bool _saving = false;
  List<String> _recent = [];
  String? _error;
  Set<String> _excludedPackages = {};
  List<Map<String, String>> _apps = [];
  bool _appsLoading = true;
  String? _appsError;
  String _appSearch = '';

  @override
  void initState() {
    super.initState();
    _load();
    _loadApps();
  }

  Future<void> _loadApps() async {
    setState(() {
      _appsLoading = true;
      _appsError = null;
    });
    try {
      final apps =
          await _channel.invokeListMethod<dynamic>('getCompatibilityApps');
      if (!mounted) return;
      setState(() => _apps = (apps ?? [])
          .map((app) => Map<String, String>.from(app as Map))
          .toList());
    } catch (e) {
      if (mounted) setState(() => _appsError = 'Cannot load apps: $e');
    } finally {
      if (mounted) setState(() => _appsLoading = false);
    }
  }

  Future<void> _load({bool recentOnly = false}) async {
    try {
      final settings = await _channel
          .invokeMapMethod<String, dynamic>('getCompatibilitySettings');
      if (!mounted) return;
      if (settings == null) throw StateError('Settings unavailable.');
      setState(() {
        if (!recentOnly) {
          _enabled = settings['compatibility'] == true;
          _domains.text =
              List<String>.from(settings['allowedDomains'] ?? []).join('\n');
          _excludedPackages =
              Set<String>.from(settings['excludedPackages'] ?? []);
        }
        _recent = List<String>.from(settings['recentBlockedDomains'] ?? []);
        _loading = false;
        _error = null;
      });
    } catch (e) {
      if (mounted) {
        setState(() => _error = 'Cannot load compatibility settings: $e');
      }
    }
  }

  List<String> _entries() => _domains.text
      .split(RegExp(r'[\s,]+'))
      .where((entry) => entry.isNotEmpty)
      .toList();

  Future<void> _save() async {
    setState(() {
      _saving = true;
      _error = null;
    });
    try {
      await _channel.invokeMethod('setCompatibilitySettings', {
        'compatibility': _enabled,
        'allowedDomains': _entries(),
        'excludedPackages': _excludedPackages.toList()..sort(),
      });
      if (mounted) Navigator.of(context).pop();
    } on PlatformException catch (e) {
      if (mounted) {
        setState(() => _error = e.message ?? 'Cannot save settings.');
      }
    } catch (e) {
      if (mounted) setState(() => _error = 'Cannot save settings: $e');
    } finally {
      if (mounted) setState(() => _saving = false);
    }
  }

  Future<void> _clearRecent() async {
    try {
      await _channel.invokeMethod('clearRecentBlockedDomains');
      if (mounted) setState(() => _recent = []);
    } catch (e) {
      if (mounted) setState(() => _error = 'Cannot clear recent domains: $e');
    }
  }

  @override
  Widget build(BuildContext context) {
    final editable = !widget.readOnly && !_loading && !_saving;
    final appLabels = {
      for (final app in _apps) app['packageName']!: app['label']!,
      for (final package in _excludedPackages)
        if (!_apps.any((app) => app['packageName'] == package))
          package: package,
    };
    final visibleApps = appLabels.entries
        .where((app) => '${app.key} ${app.value}'
            .toLowerCase()
            .contains(_appSearch.toLowerCase()))
        .toList()
      ..sort((a, b) => a.value.toLowerCase().compareTo(b.value.toLowerCase()));
    return Scaffold(
      appBar: AppBar(title: const Text('App compatibility')),
      body: ListView(
        padding: const EdgeInsets.all(20),
        children: [
          const Text(
            'For apps that stop working with ad blocking. These settings apply '
            'to Local ad blocker only. Android still reports an active VPN, '
            'and apps can still detect missing ads.',
          ),
          if (widget.readOnly)
            const Padding(
              padding: EdgeInsets.only(top: 16),
              child:
                  Text('Stop protection to change settings. You can view and '
                      'refresh recent blocked domains while it is running.'),
            ),
          SwitchListTile(
            contentPadding: EdgeInsets.zero,
            title: const Text('Compatibility mode'),
            subtitle: const Text('Use gentler DNS blocking and let apps use '
                'their own DNS connections. This may allow more ads.'),
            value: _enabled,
            onChanged:
                editable ? (value) => setState(() => _enabled = value) : null,
          ),
          ExpansionTile(
            tilePadding: EdgeInsets.zero,
            title: const Text('Apps that bypass protection'),
            subtitle: Text('${_excludedPackages.length} selected'),
            children: [
              const Text('If an app such as HDO Box still refuses to work, '
                  'select it here. It will use your normal connection and may '
                  'show ads. Other apps keep local protection. Save, start '
                  'protection, then close and reopen the selected app. '
                  'This cannot guarantee that every VPN check will pass.'),
              TextField(
                key: const Key('appSearch'),
                decoration: const InputDecoration(labelText: 'Search apps'),
                onChanged: (value) => setState(() => _appSearch = value),
              ),
              if (_appsLoading) const LinearProgressIndicator(),
              if (_appsError != null) ...[
                Text(_appsError!),
                TextButton(
                    onPressed: _loadApps, child: const Text('Retry app list')),
              ],
              if (!_appsLoading && visibleApps.isEmpty)
                const Text('No matching apps found.'),
              ConstrainedBox(
                constraints: const BoxConstraints(maxHeight: 300),
                child: ListView(
                  shrinkWrap: true,
                  children: [
                    for (final app in visibleApps)
                      CheckboxListTile(
                        title: Text(app.value),
                        subtitle: Text(app.key),
                        value: _excludedPackages.contains(app.key),
                        onChanged: editable
                            ? (selected) => setState(() {
                                  if (selected == true) {
                                    _excludedPackages.add(app.key);
                                  } else {
                                    _excludedPackages.remove(app.key);
                                  }
                                })
                            : null,
                      ),
                  ],
                ),
              ),
            ],
          ),
          const SizedBox(height: 16),
          TextField(
            key: const Key('allowedDomains'),
            controller: _domains,
            enabled: editable,
            minLines: 3,
            maxLines: 8,
            autocorrect: false,
            enableSuggestions: false,
            decoration: const InputDecoration(
              border: OutlineInputBorder(),
              labelText: 'Allowed domains',
              hintText: 'api.example.com',
              helperText: 'One exact hostname per line, up to 100.\n'
                  'No URLs or wildcards. Subdomains are separate.',
            ),
          ),
          const SizedBox(height: 12),
          const Text('Exceptions work in either local mode and affect every '
              'app. Allowing a domain can restore playback or app checks, but '
              'also permits ads and tracking from that domain.'),
          const SizedBox(height: 16),
          if (_error != null)
            Text(_error!, style: const TextStyle(color: Colors.redAccent)),
          FilledButton(
            onPressed: editable ? _save : null,
            child: Text(_saving ? 'Saving…' : 'Save settings'),
          ),
          const SizedBox(height: 24),
          Text('Recently blocked domains',
              style: Theme.of(context).textTheme.titleMedium),
          const Text('Reproduce the problem in the other app, then refresh '
              'this list. It includes requests from all apps, without app '
              'attribution. Only the latest 30 names are kept in memory; '
              'starting local protection clears them.'),
          Row(children: [
            TextButton.icon(
              onPressed: _saving ? null : () => _load(recentOnly: !_loading),
              icon: const Icon(Icons.refresh),
              label: const Text('Refresh'),
            ),
            TextButton(
              onPressed: _recent.isEmpty ? null : _clearRecent,
              child: const Text('Clear history'),
            ),
          ]),
          if (_loading && _error == null) const LinearProgressIndicator(),
          if (!_loading && _recent.isEmpty)
            const Text('No locally blocked domains recorded yet.'),
          for (final domain in _recent)
            ListTile(
              contentPadding: EdgeInsets.zero,
              title: Text(domain),
              trailing: TextButton(
                onPressed: editable
                    ? () => setState(() {
                          _domains.text = {..._entries(), domain}.join('\n');
                        })
                    : null,
                child: const Text('Allow'),
              ),
            ),
        ],
      ),
    );
  }

  @override
  void dispose() {
    _domains.dispose();
    super.dispose();
  }
}
