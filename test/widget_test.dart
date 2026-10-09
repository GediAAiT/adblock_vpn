import 'package:flutter/services.dart';
import 'package:flutter_test/flutter_test.dart';

import 'package:adblock_vpn/main.dart';

void main() {
  const channel = MethodChannel('com.example.adblock_vpn/vpn');

  setUp(() {
    TestDefaultBinaryMessengerBinding.instance.defaultBinaryMessenger
        .setMockMethodCallHandler(channel, (call) async {
      if (call.method == 'isRunning') return false;
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
  });
}
