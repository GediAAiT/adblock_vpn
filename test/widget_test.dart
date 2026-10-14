import 'dart:async';

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

  testWidgets('Stop disconnects an active local VPN', (tester) async {
    var local = 'connected';
    var stops = 0;
    TestDefaultBinaryMessengerBinding.instance.defaultBinaryMessenger
        .setMockMethodCallHandler(channel, (call) async {
      if (call.method == 'getState') {
        return {'localState': local, 'remoteState': 'disconnected'};
      }
      if (call.method == 'stopVpn') {
        stops++;
        local = 'disconnected';
      }
      return null;
    });
    await tester.pumpWidget(const AdBlockApp());
    await tester.pump(const Duration(milliseconds: 100));
    await tester.ensureVisible(find.text('STOP PROTECTION'));
    await tester.tap(find.text('STOP PROTECTION'));
    await tester.pump(const Duration(milliseconds: 400));
    expect(stops, 1);
    expect(find.text('START PROTECTION'), findsOneWidget);
    await tester.pumpWidget(const SizedBox());
  });

  testWidgets(
      'Stop cancels restored remote connection without fetching servers',
      (tester) async {
    var remote = 'connecting';
    var stops = 0;
    TestDefaultBinaryMessengerBinding.instance.defaultBinaryMessenger
        .setMockMethodCallHandler(channel, (call) async {
      if (call.method == 'getState') {
        return {'localState': 'disconnected', 'remoteState': remote};
      }
      if (call.method == 'stopVpn') {
        stops++;
        remote = 'disconnected';
      }
      return null;
    });
    await tester.pumpWidget(const AdBlockApp());
    await tester.pump(const Duration(milliseconds: 100));
    await tester.ensureVisible(find.text('STOP PROTECTION'));
    await tester.tap(find.text('STOP PROTECTION'));
    await tester.pump(const Duration(milliseconds: 400));
    expect(stops, 1);
    expect(find.text('START PROTECTION'), findsOneWidget);
    await tester.pumpWidget(const SizedBox());
  });

  testWidgets('late start error cannot undo cancellation', (tester) async {
    final start = Completer<dynamic>();
    final stop = Completer<dynamic>();
    var stops = 0;
    TestDefaultBinaryMessengerBinding.instance.defaultBinaryMessenger
        .setMockMethodCallHandler(channel, (call) async {
      if (call.method == 'getState') {
        return {'localState': 'disconnected', 'remoteState': 'disconnected'};
      }
      if (call.method == 'startVpn') return start.future;
      if (call.method == 'stopVpn') {
        stops++;
        return stop.future;
      }
      return null;
    });
    await tester.pumpWidget(const AdBlockApp());
    await tester.pump(const Duration(milliseconds: 100));
    await tester.ensureVisible(find.text('START PROTECTION'));
    await tester.tap(find.text('START PROTECTION'));
    await tester.pump();
    await tester.tap(find.text('STOP PROTECTION'));
    await tester.pump();
    start.completeError(
        PlatformException(code: 'CANCELLED', message: 'Late error'));
    await tester.pump();
    await tester.tap(find.text('STOP PROTECTION'));
    await tester.pump();
    expect(stops, 1);
    expect(find.textContaining('Late error'), findsNothing);
    stop.complete();
    await tester.pump(const Duration(milliseconds: 400));
    expect(find.text('START PROTECTION'), findsOneWidget);
    await tester.pumpWidget(const SizedBox());
  });

  testWidgets('failed stop displays error and remains available for retry',
      (tester) async {
    TestDefaultBinaryMessengerBinding.instance.defaultBinaryMessenger
        .setMockMethodCallHandler(channel, (call) async {
      if (call.method == 'getState') {
        return {'localState': 'connected', 'remoteState': 'disconnected'};
      }
      if (call.method == 'stopVpn') {
        throw PlatformException(code: 'STOP_FAILED');
      }
      return null;
    });
    await tester.pumpWidget(const AdBlockApp());
    await tester.pump(const Duration(milliseconds: 100));
    await tester.ensureVisible(find.text('STOP PROTECTION'));
    await tester.tap(find.text('STOP PROTECTION'));
    await tester.pump(const Duration(milliseconds: 400));
    expect(find.textContaining('Cannot stop protection'), findsOneWidget);
    expect(find.text('STOP PROTECTION'), findsOneWidget);
    await tester.pumpWidget(const SizedBox());
  });
}
