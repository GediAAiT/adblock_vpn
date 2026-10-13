import 'package:flutter/services.dart';
import 'package:flutter/widgets.dart';
import 'package:flutter_test/flutter_test.dart';

import 'package:adblock_vpn/main.dart';

void main() {
  const channel = MethodChannel('com.example.adblock_vpn/vpn');

  setUp(() {
    TestDefaultBinaryMessengerBinding.instance.defaultBinaryMessenger
        .setMockMethodCallHandler(channel, (call) async {
      if (call.method == 'getState') {
        return {
          'localState': 'disconnected',
          'remoteState': 'disconnected',
          'blockedCount': 0
        };
      }
      return null;
    });
  });

  tearDown(() {
    TestDefaultBinaryMessengerBinding.instance.defaultBinaryMessenger
        .setMockMethodCallHandler(channel, null);
  });

  testWidgets('AdBlock home renders', (WidgetTester tester) async {
    await tester.pumpWidget(const AdBlockApp());
    await tester.pump(const Duration(milliseconds: 100));

    expect(find.text('AdBlock'), findsOneWidget);
    expect(find.text('START PROTECTION'), findsOneWidget);
    expect(find.text('Local ad blocker'), findsOneWidget);
    expect(find.text('Free VPN'), findsOneWidget);
    await tester.pumpWidget(const SizedBox());
  });

  testWidgets('permission denial stays unprotected and allows retry',
      (tester) async {
    TestDefaultBinaryMessengerBinding.instance.defaultBinaryMessenger
        .setMockMethodCallHandler(channel, (call) async {
      if (call.method == 'getState') {
        return {'localState': 'disconnected', 'remoteState': 'disconnected'};
      }
      if (call.method == 'startVpn') {
        throw PlatformException(
            code: 'PERMISSION_DENIED', message: 'VPN permission was denied');
      }
      return null;
    });
    await tester.pumpWidget(const AdBlockApp());
    await tester.pump(const Duration(milliseconds: 100));
    await tester.ensureVisible(find.text('START PROTECTION'));
    await tester.tap(find.text('START PROTECTION'));
    await tester.pump(const Duration(milliseconds: 400));
    expect(find.text('START PROTECTION'), findsOneWidget);
    expect(find.textContaining('VPN permission was denied'), findsOneWidget);
    expect(find.text('NOT PROTECTED'), findsOneWidget);
    await tester.pumpWidget(const SizedBox());
  });
}
