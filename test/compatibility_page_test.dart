import 'package:adblock_vpn/compatibility_page.dart';
import 'package:flutter/material.dart';
import 'package:flutter/services.dart';
import 'package:flutter_test/flutter_test.dart';

void main() {
  const channel = MethodChannel('com.example.adblock_vpn/vpn');
  final calls = <MethodCall>[];
  var failSave = false;
  final binding = TestWidgetsFlutterBinding.ensureInitialized();

  setUp(() {
    binding.platformDispatcher.implicitView!.physicalSize =
        const Size(900, 1800);
    binding.platformDispatcher.implicitView!.devicePixelRatio = 1;
    calls.clear();
    failSave = false;
    TestDefaultBinaryMessengerBinding.instance.defaultBinaryMessenger
        .setMockMethodCallHandler(channel, (call) async {
      calls.add(call);
      if (call.method == 'getCompatibilitySettings') {
        return {
          'compatibility': true,
          'allowedDomains': ['api.example.com'],
          'recentBlockedDomains': ['ads.example.com'],
          'excludedPackages': ['com.example.video'],
        };
      }
      if (call.method == 'getCompatibilityApps') {
        return [
          {'packageName': 'com.example.video', 'label': 'Video app'},
          {'packageName': 'com.example.browser', 'label': 'Browser'},
        ];
      }
      if (call.method == 'setCompatibilitySettings' && failSave) {
        throw PlatformException(
            code: 'BUSY', message: 'Stop protection first.');
      }
      return null;
    });
  });

  tearDown(() {
    binding.platformDispatcher.implicitView!.resetPhysicalSize();
    binding.platformDispatcher.implicitView!.resetDevicePixelRatio();
    TestDefaultBinaryMessengerBinding.instance.defaultBinaryMessenger
        .setMockMethodCallHandler(channel, null);
  });

  testWidgets('adds a recent domain and saves restored settings',
      (tester) async {
    await tester.pumpWidget(
        const MaterialApp(home: CompatibilityPage(readOnly: false)));
    await tester.pumpAndSettle();
    expect(
        tester.widget<SwitchListTile>(find.byType(SwitchListTile)).value, true);
    await tester.ensureVisible(find.text('Allow'));
    await tester.tap(find.text('Allow'));
    await tester.pump();
    await tester.ensureVisible(find.text('Save settings'));
    await tester.tap(find.text('Save settings'));
    await tester.pumpAndSettle();
    expect(calls.last.method, 'setCompatibilitySettings');
    expect(calls.last.arguments, {
      'compatibility': true,
      'allowedDomains': ['api.example.com', 'ads.example.com'],
      'excludedPackages': ['com.example.video'],
    });
  });

  testWidgets('running protection allows diagnostics but disables edits',
      (tester) async {
    await tester
        .pumpWidget(const MaterialApp(home: CompatibilityPage(readOnly: true)));
    await tester.pumpAndSettle();
    expect(
        tester
            .widget<TextField>(find.byKey(const Key('allowedDomains')))
            .enabled,
        false);
    expect(tester.widget<FilledButton>(find.byType(FilledButton)).onPressed,
        isNull);
    await tester.ensureVisible(find.text('Refresh'));
    await tester.tap(find.text('Refresh'));
    await tester.pumpAndSettle();
    expect(
        calls.where((c) => c.method == 'getCompatibilitySettings').length, 2);
    expect(calls.where((c) => c.method == 'setCompatibilitySettings'), isEmpty);
  });

  testWidgets('native save rejection keeps edits for retry', (tester) async {
    failSave = true;
    await tester.pumpWidget(
        const MaterialApp(home: CompatibilityPage(readOnly: false)));
    await tester.pumpAndSettle();
    await tester.enterText(
        find.byKey(const Key('allowedDomains')), 'video.example.com');
    await tester.ensureVisible(find.text('Save settings'));
    await tester.tap(find.text('Save settings'));
    await tester.pumpAndSettle();
    expect(find.text('Stop protection first.'), findsOneWidget);
    expect(find.text('video.example.com'), findsOneWidget);
    expect(tester.widget<FilledButton>(find.byType(FilledButton)).onPressed,
        isNotNull);
  });

  testWidgets('restores, searches and saves app bypass selections',
      (tester) async {
    await tester.pumpWidget(
        const MaterialApp(home: CompatibilityPage(readOnly: false)));
    await tester.pumpAndSettle();
    await tester.tap(find.text('Apps that bypass protection'));
    await tester.pumpAndSettle();
    final video = find.widgetWithText(CheckboxListTile, 'Video app');
    expect(tester.widget<CheckboxListTile>(video).value, true);
    await tester.ensureVisible(video);
    await tester.tap(video);
    await tester.enterText(find.byKey(const Key('appSearch')), 'Browser');
    await tester.pumpAndSettle();
    expect(find.text('Video app'), findsNothing);
    final browser = find.widgetWithText(CheckboxListTile, 'Browser');
    await tester.ensureVisible(browser);
    await tester.tap(browser);
    await tester.pump();
    await tester.ensureVisible(find.text('Save settings'));
    await tester.tap(find.text('Save settings'));
    await tester.pumpAndSettle();
    final save =
        calls.lastWhere((call) => call.method == 'setCompatibilitySettings');
    expect(save.arguments['excludedPackages'], ['com.example.browser']);
    expect(save.arguments['allowedDomains'], ['api.example.com']);
  });
}
