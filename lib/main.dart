import 'dart:async';
import 'dart:ui';
import 'package:flutter/material.dart';
import 'package:flutter/services.dart';

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
          headlineSmall: TextStyle(fontWeight: FontWeight.bold, letterSpacing: 1.2),
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

class _HomePageState extends State<HomePage> with SingleTickerProviderStateMixin {
  static const _channel = MethodChannel('com.example.adblock_vpn/vpn');

  bool _isRunning = false;
  int _blockedCount = 0;
  String _status = 'Not protected';
  Timer? _pollTimer;

  late final AnimationController _pulseController;
  late final Animation<double> _pulseAnimation;

  @override
  void initState() {
    super.initState();
    _channel.setMethodCallHandler(_handleNativeCall);
    _refreshState();

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
      final Map args = call.arguments as Map;
      setState(() {
        _blockedCount = args['blockedCount'] ?? _blockedCount;
      });
    }
    return null;
  }

  Future<void> _refreshState() async {
    try {
      final bool running = await _channel.invokeMethod('isRunning');
      setState(() {
        _isRunning = running;
        _status = running ? 'Protected' : 'Not protected';
      });
    } on PlatformException {
      // native side not ready yet
    }
  }

  Future<void> _toggle() async {
    setState(() => _status = _isRunning ? 'Stopping…' : 'Starting…');
    try {
      if (_isRunning) {
        await _channel.invokeMethod('stopVpn');
      } else {
        await _channel.invokeMethod('startVpn');
      }
    } on PlatformException catch (e) {
      setState(() => _status = 'Error: ${e.message}');
      return;
    }
    await _refreshState();
  }

  @override
  Widget build(BuildContext context) {
    final size = MediaQuery.of(context).size;
    final primaryColor = _isRunning ? const Color(0xFF00FFC2) : Colors.grey.shade600;
    
    return Scaffold(
      extendBodyBehindAppBar: true,
      appBar: AppBar(
        title: const Text('AdBlock', style: TextStyle(fontWeight: FontWeight.bold)),
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
                color: primaryColor.withOpacity(0.15),
              ),
              child: BackdropFilter(
                filter: ImageFilter.blur(sigmaX: 80, sigmaY: 80),
                child: Container(color: Colors.transparent),
              ),
            ),
          ),

          SafeArea(
            child: Center(
              child: Column(
                mainAxisAlignment: MainAxisAlignment.center,
                children: [
                  const Spacer(),
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
                                      color: primaryColor.withOpacity(0.4),
                                      blurRadius: 40,
                                      spreadRadius: 10,
                                    )
                                  ]
                                : [],
                            gradient: LinearGradient(
                              colors: _isRunning
                                  ? [primaryColor, const Color(0xFF00A3FF)]
                                  : [Colors.grey.shade800, Colors.grey.shade900],
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
                      style: Theme.of(context).textTheme.headlineSmall?.copyWith(
                            color: primaryColor,
                            letterSpacing: 2,
                          ),
                    ),
                  ),
                  const SizedBox(height: 12),
                  
                  Container(
                    padding: const EdgeInsets.symmetric(horizontal: 24, vertical: 12),
                    decoration: BoxDecoration(
                      color: Colors.black.withOpacity(0.3),
                      borderRadius: BorderRadius.circular(30),
                      border: Border.all(color: Colors.white.withOpacity(0.1)),
                    ),
                    child: Row(
                      mainAxisSize: MainAxisSize.min,
                      children: [
                        const Icon(Icons.block, size: 16, color: Colors.white70),
                        const SizedBox(width: 8),
                        Text(
                          'Blocked: $_blockedCount',
                          style: const TextStyle(fontSize: 16, fontWeight: FontWeight.w500),
                        ),
                      ],
                    ),
                  ),
                  
                  const Spacer(),

                  // Toggle Button
                  GestureDetector(
                    onTap: _toggle,
                    child: AnimatedContainer(
                      duration: const Duration(milliseconds: 300),
                      width: 240,
                      height: 64,
                      decoration: BoxDecoration(
                        borderRadius: BorderRadius.circular(32),
                        gradient: LinearGradient(
                          colors: _isRunning
                              ? [const Color(0xFFFF4B4B), const Color(0xFFFF004D)]
                              : [primaryColor, const Color(0xFF00A3FF)],
                        ),
                        boxShadow: [
                          BoxShadow(
                            color: (_isRunning ? const Color(0xFFFF004D) : primaryColor).withOpacity(0.4),
                            blurRadius: 20,
                            offset: const Offset(0, 8),
                          ),
                        ],
                      ),
                      child: Center(
                        child: Text(
                          _isRunning ? 'STOP PROTECTION' : 'START PROTECTION',
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
                  
                  const Padding(
                    padding: EdgeInsets.symmetric(horizontal: 40),
                    child: Text(
                      'System-wide DNS filtering active.\nProtects you across all apps and browsers.',
                      textAlign: TextAlign.center,
                      style: TextStyle(color: Colors.white54, fontSize: 13, height: 1.5),
                    ),
                  ),
                  const SizedBox(height: 48),
                ],
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
    super.dispose();
  }
}
