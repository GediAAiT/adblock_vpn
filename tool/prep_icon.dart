// Prepares Android launcher icon source images from the RamaAdBlock logo.
//
// The source artwork contains the emblem plus a wordmark + tagline. Text is
// unreadable at launcher sizes, so we crop to just the winged-R shield emblem
// and pad it onto square canvases using the artwork's own dark background,
// producing:
//   - app_icon.png            (legacy, full-bleed emblem on dark)
//   - app_icon_foreground.png (adaptive foreground, emblem in the safe zone)
//   - app_icon_background.png (adaptive background, solid dark)
//
// Run with: dart run tool/prep_icon.dart
import 'dart:io';
import 'package:image/image.dart' as img;

const _src =
    r'C:\Users\gedio\.claude\image-cache\5f7468cb-9118-428c-9694-835bc75a8475\1.png';

// Emblem bounding box as fractions of the source width/height.
const _left = 0.245;
const _right = 0.895;
const _top = 0.100;
const _bottom = 0.625;

void main() {
  final bytes = File(_src).readAsBytesSync();
  final source = img.decodeImage(bytes);
  if (source == null) {
    stderr.writeln('Could not decode source image.');
    exit(1);
  }
  final w = source.width, h = source.height;
  stdout.writeln('Source: ${w}x$h');

  // Crop the emblem only (no wordmark/tagline — unreadable at icon sizes).
  final cx = (_left * w).round();
  final cy = (_top * h).round();
  final cw = ((_right - _left) * w).round();
  final ch = ((_bottom - _top) * h).round();
  final emblem = img.copyCrop(source, x: cx, y: cy, width: cw, height: ch);
  stdout.writeln('Emblem crop: ${cw}x$ch at ($cx,$cy)');

  Directory('assets/icon').createSync(recursive: true);

  // Build the 1024x1024 launcher icon. The emblem is landscape, so we scale it
  // to full tile width (no horizontal padding, no side seam) and fill the thin
  // top/bottom bands by clamping the emblem's own edge rows — which are uniform
  // dark navy background — so the backdrop extends seamlessly with no visible
  // rectangle.
  const size = 1024;
  const rw = size;
  final rh = (emblem.height * size / emblem.width).round();
  final resized = img.copyResize(emblem,
      width: rw, height: rh, interpolation: img.Interpolation.cubic);

  final canvas = img.Image(width: size, height: size, numChannels: 4);
  final oy = (size - rh) ~/ 2;
  img.compositeImage(canvas, resized, dstX: 0, dstY: oy);
  for (var x = 0; x < size; x++) {
    final top = resized.getPixel(x, 0);
    final bot = resized.getPixel(x, rh - 1);
    for (var y = 0; y < oy; y++) {
      canvas.setPixel(x, y, top);
    }
    for (var y = oy + rh; y < size; y++) {
      canvas.setPixel(x, y, bot);
    }
  }

  File('assets/icon/app_icon.png').writeAsBytesSync(img.encodePng(canvas));
  stdout.writeln('Wrote assets/icon/app_icon.png (${size}x$size, '
      'emblem ${rw}x$rh at y=$oy)');
}
