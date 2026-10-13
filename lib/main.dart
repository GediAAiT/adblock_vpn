import 'dart:async';
import 'dart:ui';
import 'package:flutter/material.dart';
import 'package:flutter/services.dart';
import 'vpn_gate.dart';

void main() => runApp(const AdBlockApp());

class AdBlockApp extends StatelessWidget {
  const AdBlockApp({super.key});

  @override
  Widget build(BuildContext context) {
    return MaterialApp(
      title: 'AdBlock VPN',
      debugShowCheckedModeBanner: false,
      theme: ThemeData(
        colorSchemeSeed: const Color(0xFF00FFC2),
        useMaterial3: true,
        brightness: Brightness.dark,
        scaffoldBackgroundColor: const Color(0xFF0A0E17),
        textTheme: const TextTheme(
          headlineSmall:
              TextStyle(fontWeight: FontWeight.bold, letterSpacing: 1.2),
        ),
      ),
      home: const HomePage(),
    );
  }
}

class HomePage extends StatefulWidget {
  const HomePage({super.key});

  @override
  State<HomePage> createState() => _HomePageState();
}

class _HomePageState extends State<HomePage>
    with SingleTickerProviderStateMixin {
  static const _channel = MethodChannel('com.example.adblock_vpn/vpn');

  bool _isRunning = false;
  int _blockedCount = 0;
  String _status = 'Not protected';
  Timer? _pollTimer;
  bool _busy = false;
  bool _remoteMode = false;
  bool _loadingServers = false;
  bool _refreshing = false;
  bool _requestPending = false;
  List<VpnServer> _servers = [];
  VpnServer? _selectedServer;
  String? _error;
  String _remoteState = 'disconnected';
  int _bytesIn = 0;
  DateTime? _startedAt;

  late final AnimationController _pulseController;
  late final Animation<double> _pulseAnimation;

  @override
  void initState() {
    super.initState();
    _channel.setMethodCallHandler(_handleNativeCall);
    _refreshState();
    _pollTimer =
        Timer.periodic(const Duration(seconds: 1), (_) => _refreshState());

    _pulseController = AnimationController(
      vsync: this,
      duration: const Duration(milliseconds: 1500),
    )..repeat(reverse: true);

    _pulseAnimation = Tween<double>(begin: 1.0, end: 1.1).animate(
      CurvedAnimation(parent: _pulseController, curve: Curves.easeInOut),
    );
  }

  Future<dynamic> _handleNativeCall(MethodCall call) async {
    if (call.method == 'onStatsUpdate') {
      if (!mounted || _remoteMode) return null;
      final Map args = call.arguments as Map;
      setState(() {
        _blockedCount = args['blockedCount'] ?? _blockedCount;
      });
    }
    return null;
  }

  Future<void> _refreshState() async {
    if (_refreshing) return;
    _refreshing = true;
    try {
      final state = await _channel.invokeMapMethod<String, dynamic>('getState');
      if (!mounted || state == null) return;
      final local = state['localState'] ?? 'disconnected';
      final remote = state['remoteState'] ?? 'disconnected';
      if (remote != 'disconnected') {
        _remoteMode = true;
      } else if (local != 'disconnected') {
        _remoteMode = false;
      }
      final active = _remoteMode ? remote : local;
      final connected = active == 'connected';
      final connecting = active != 'disconnected' &&
          active != 'connected' &&
          active != 'error';
      final timedOut = connecting &&
          _startedAt != null &&
          DateTime.now().difference(_startedAt!) > const Duration(seconds: 45);
      setState(() {
        _remoteState = remote;
        _isRunning = connected;
        _busy = connecting || _requestPending;
        _blockedCount = state['blockedCount'] as int? ?? 0;
        _bytesIn = state['bytesIn'] as int? ?? 0;
        _status = connected
            ? (_remoteMode ? 'VPN connected' : 'DNS filtering active')
            : connecting
                ? (active == 'disconnecting' ? 'Stopping…' : 'Connecting…')
                : 'Not protected';
        if (connected) _startedAt = null;
        if (active == 'error') {
          _error = 'VPN connection failed. Refresh and choose another server.';
        }
        final nativeError = state['localError'] as String? ?? '';
        if (!_remoteMode && nativeError.isNotEmpty) _error = nativeError;
      });
      if (timedOut) {
        _startedAt = null;
        await _channel.invokeMethod('stopVpn');
        if (mounted) {
          setState(() {
            _error =
                'Connection timed out. Choose another server or use local filtering.';
          });
        }
      }
    } on PlatformException catch (e) {
      if (mounted) setState(() => _error = e.message);
    } on MissingPluginException {
      if (mounted) {
        setState(() => _error = 'VPN protection requires the Android app.');
      }
    } finally {
      _refreshing = false;
    }
  }

  Future<void> _loadServers() async {
    if (_loadingServers) return;
    setState(() {
      _loadingServers = true;
      _error = null;
    });
    try {
      final servers = await VpnGateApi().fetchServers();
      if (!mounted) return;
      setState(() {
        _servers = servers;
        _selectedServer = servers.first;
      });
    } catch (e) {
      if (mounted) setState(() => _error = 'Cannot load free servers: $e');
    } finally {
      if (mounted) setState(() => _loadingServers = false);
    }
  }

  Future<void> _toggle() async {
    if (_busy || _loadingServers) return;
    if (_remoteMode && !_isRunning && _selectedServer == null) {
      await _loadServers();
      if (!mounted || _selectedServer == null) return;
    }
    _startedAt = DateTime.now();
    _requestPending = true;
    setState(() {
      _busy = true;
      _error = null;
    });
    setState(() => _status = _isRunning ? 'Stopping…' : 'Starting…');
    try {
      if (_isRunning) {
        await _channel.invokeMethod('stopVpn');
      } else {
        if (_remoteMode) {
          await _channel.invokeMethod('startRemoteVpn', {
            'config': _selectedServer!.config,
            'name': _selectedServer!.label,
          });
        } else {
          await _channel.invokeMethod('startVpn');
        }
      }
    } catch (e) {
      _startedAt = null;
      if (mounted) {
        setState(() {
          _busy = false;
          _error = '$e';
        });
      }
    } finally {
      _requestPending = false;
      if (mounted) await _refreshState();
    }
  }

  @override
  Widget build(BuildContext context) {
    final size = MediaQuery.of(context).size;
    final primaryColor =
        _isRunning ? const Color(0xFF00FFC2) : Colors.grey.shade600;

    return Scaffold(
      extendBodyBehindAppBar: true,
      appBar: AppBar(
        title: const Text('AdBlock',
            style: TextStyle(fontWeight: FontWeight.bold)),
        centerTitle: true,
        backgroundColor: Colors.transparent,
        elevation: 0,
      ),
      body: Stack(
        children: [
          // Background Icon Watermark
          Positioned(
            top: -size.height * 0.1,
            right: -size.width * 0.3,
            child: Opacity(
              opacity: 0.05,
              child: Transform.rotate(
                angle: -0.2,
                child: Image.asset(
                  'assets/icon/app_icon.png',
                  width: size.width * 1.5,
                  fit: BoxFit.cover,
                ),
              ),
            ),
          ),

          // Glowing orb background effect
          Positioned(
            top: size.height * 0.2,
            left: size.width * 0.1,
            child: Container(
              width: 300,
              height: 300,
              decoration: BoxDecoration(
                shape: BoxShape.circle,
                color: primaryColor.withValues(alpha: 0.15),
              ),
              child: BackdropFilter(
                filter: ImageFilter.blur(sigmaX: 80, sigmaY: 80),
                child: Container(color: Colors.transparent),
              ),
            ),
          ),

          SafeArea(
            child: Center(
              child: SingleChildScrollView(
                padding: const EdgeInsets.symmetric(vertical: 24),
                child: Column(
                  mainAxisAlignment: MainAxisAlignment.center,
                  children: [
                    SegmentedButton<bool>(
                      segments: const [
                        ButtonSegment(
                            value: false,
                            label: Text('Local ad blocker'),
                            icon: Icon(Icons.shield)),
                        ButtonSegment(
                            value: true,
                            label: Text('Free VPN'),
                            icon: Icon(Icons.public)),
                      ],
                      selected: {_remoteMode},
                      onSelectionChanged: _isRunning || _busy
                          ? null
                          : (values) {
                              setState(() {
                                _remoteMode = values.single;
                                _error = null;
                              });
                              if (_remoteMode && _servers.isEmpty) {
                                _loadServers();
                              }
                            },
                    ),
                    const SizedBox(height: 28),
                    if (_remoteMode) ...[
                      Padding(
                        padding: const EdgeInsets.symmetric(horizontal: 24),
                        child: Row(children: [
                          Expanded(
                              child: DropdownButton<String>(
                            isExpanded: true,
                            value: _selectedServer?.id,
                            hint: Text(_loadingServers
                                ? 'Loading servers…'
                                : 'Choose a free server'),
                            items: _servers
                                .map((server) => DropdownMenuItem(
                                      value: server.id,
                                      child: Text(
                                          '${server.label} · ${server.speedLabel}',
                                          overflow: TextOverflow.ellipsis),
                                    ))
                                .toList(),
                            onChanged: _busy || _isRunning
                                ? null
                                : (id) => setState(() {
                                      _selectedServer = _servers
                                          .firstWhere((s) => s.id == id);
                                    }),
                          )),
                          IconButton(
                              onPressed: _busy || _isRunning || _loadingServers
                                  ? null
                                  : _loadServers,
                              icon: const Icon(Icons.refresh),
                              tooltip: 'Refresh free servers'),
                        ]),
                      ),
                      const Padding(
                          padding: EdgeInsets.symmetric(horizontal: 24),
                          child: Text(
                              'VPN Gate servers are run by volunteers and keep connection logs. Availability and speed vary.',
                              textAlign: TextAlign.center,
                              style: TextStyle(
                                  color: Colors.white60, fontSize: 12))),
                      const SizedBox(height: 24),
                    ],
                    // Shield Icon with animation
                    AnimatedBuilder(
                      animation: _pulseAnimation,
                      builder: (context, child) {
                        return Transform.scale(
                          scale: _isRunning ? _pulseAnimation.value : 1.0,
                          child: Container(
                            width: 160,
                            height: 160,
                            decoration: BoxDecoration(
                              shape: BoxShape.circle,
                              boxShadow: _isRunning
                                  ? [
                                      BoxShadow(
                                        color:
                                            primaryColor.withValues(alpha: 0.4),
                                        blurRadius: 40,
                                        spreadRadius: 10,
                                      )
                                    ]
                                  : [],
                              gradient: LinearGradient(
                                colors: _isRunning
                                    ? [primaryColor, const Color(0xFF00A3FF)]
                                    : [
                                        Colors.grey.shade800,
                                        Colors.grey.shade900
                                      ],
                                begin: Alignment.topLeft,
                                end: Alignment.bottomRight,
                              ),
                            ),
                            child: const Icon(
                              Icons.shield_rounded,
                              size: 80,
                              color: Colors.white,
                            ),
                          ),
                        );
                      },
                    ),
                    const SizedBox(height: 40),

                    AnimatedSwitcher(
                      duration: const Duration(milliseconds: 300),
                      child: Text(
                        _status.toUpperCase(),
                        key: ValueKey(_status),
                        style:
                            Theme.of(context).textTheme.headlineSmall?.copyWith(
                                  color: primaryColor,
                                  letterSpacing: 2,
                                ),
                      ),
                    ),
                    const SizedBox(height: 12),

                    Container(
                      padding: const EdgeInsets.symmetric(
                          horizontal: 24, vertical: 12),
                      decoration: BoxDecoration(
                        color: Colors.black.withValues(alpha: 0.3),
                        borderRadius: BorderRadius.circular(30),
                        border: Border.all(
                            color: Colors.white.withValues(alpha: 0.1)),
                      ),
                      child: Row(
                        mainAxisSize: MainAxisSize.min,
                        children: [
                          const Icon(Icons.block,
                              size: 16, color: Colors.white70),
                          const SizedBox(width: 8),
                          Text(
                            _remoteMode
                                ? 'Received: ${(_bytesIn / 1048576).toStringAsFixed(1)} MB'
                                : 'Blocked DNS requests: $_blockedCount',
                            style: const TextStyle(
                                fontSize: 16, fontWeight: FontWeight.w500),
                          ),
                        ],
                      ),
                    ),

                    const SizedBox(height: 32),
                    if (_error != null)
                      Padding(
                        padding: const EdgeInsets.fromLTRB(24, 0, 24, 24),
                        child: Text(_error!,
                            textAlign: TextAlign.center,
                            style: const TextStyle(color: Colors.redAccent)),
                      ),

                    // Toggle Button
                    GestureDetector(
                      onTap: _busy || _loadingServers ? null : _toggle,
                      child: AnimatedContainer(
                        duration: const Duration(milliseconds: 300),
                        width: 240,
                        height: 64,
                        decoration: BoxDecoration(
                          borderRadius: BorderRadius.circular(32),
                          gradient: LinearGradient(
                            colors: _isRunning
                                ? [
                                    const Color(0xFFFF4B4B),
                                    const Color(0xFFFF004D)
                                  ]
                                : [primaryColor, const Color(0xFF00A3FF)],
                          ),
                          boxShadow: [
                            BoxShadow(
                              color: (_isRunning
                                      ? const Color(0xFFFF004D)
                                      : primaryColor)
                                  .withValues(alpha: 0.4),
                              blurRadius: 20,
                              offset: const Offset(0, 8),
                            ),
                          ],
                        ),
                        child: Center(
                          child: Text(
                            _busy
                                ? 'PLEASE WAIT…'
                                : _isRunning
                                    ? 'STOP PROTECTION'
                                    : 'START PROTECTION',
                            style: const TextStyle(
                              color: Colors.white,
                              fontSize: 16,
                              fontWeight: FontWeight.bold,
                              letterSpacing: 1.5,
                            ),
                          ),
                        ),
                      ),
                    ),
                    const SizedBox(height: 32),
                    if (_busy ||
                        (_remoteMode &&
                            _remoteState != 'disconnected' &&
                            !_isRunning))
                      TextButton(
                          onPressed: () async {
                            _startedAt = null;
                            await _channel.invokeMethod('stopVpn');
                            await _refreshState();
                          },
                          child: const Text('Cancel connection')),

                    Padding(
                      padding: const EdgeInsets.symmetric(horizontal: 40),
                      child: Text(
                        _remoteMode
                            ? 'OpenVPN with AdGuard ad-filtering DNS.\nApps using their own encrypted DNS may bypass filtering.'
                            : 'Local DNS filtering across apps and browsers.\nYour public IP stays the same.',
                        textAlign: TextAlign.center,
                        style: const TextStyle(
                            color: Colors.white54, fontSize: 13, height: 1.5),
                      ),
                    ),
                    const SizedBox(height: 48),
                    const Padding(
                        padding: EdgeInsets.symmetric(horizontal: 24),
                        child: Text(
                            'Blocks known third-party ad domains. Ads sharing video servers or already cached may still appear.',
                            textAlign: TextAlign.center,
                            style: TextStyle(
                                color: Colors.white54, fontSize: 12))),
                  ],
                ),
              ),
            ),
          ),
        ],
      ),
    );
  }

  @override
  void dispose() {
    _pulseController.dispose();
    _pollTimer?.cancel();
    _channel.setMethodCallHandler(null);
    super.dispose();
  }
}
