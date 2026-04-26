import 'dart:ui';
import 'dart:async';
import 'package:flutter/material.dart';
import 'package:flutter/services.dart';
import 'dart:math' as math;

class SmartNavigationWidget extends StatefulWidget {
  const SmartNavigationWidget({super.key});

  @override
  State<SmartNavigationWidget> createState() => _SmartNavigationWidgetState();
}

class _SmartNavigationWidgetState extends State<SmartNavigationWidget> {
  static const MethodChannel _channel = MethodChannel('com.display.switcher/navigation');

  bool _isVisible = false;
  String _title = "";
  String _text = "";
  String _subText = "";
  Uint8List? _iconBytes;

  Timer? _aodDimTimer;
  bool _isDimmed = false;

  DateTime? _tripStartTime;
  Timer? _tripDurationTimer;
  String _tripDuration = "0:00:00";

  @override
  void initState() {
    super.initState();
    _channel.setMethodCallHandler(_handleMethodCall);
  }

  void _resetAodTimer() {
    setState(() {
      _isDimmed = false;
    });
    _aodDimTimer?.cancel();
    _aodDimTimer = Timer(const Duration(seconds: 5), () {
      if (mounted) {
        setState(() {
          _isDimmed = true;
        });
      }
    });
  }

  void _startTripDurationCalculator() {
    _tripDurationTimer?.cancel();
    if (_isVisible) {
      if (_tripStartTime == null) {
        _tripStartTime = DateTime.now();
      }
      _tripDurationTimer = Timer.periodic(const Duration(seconds: 1), (timer) {
        if (mounted && _tripStartTime != null) {
          final duration = DateTime.now().difference(_tripStartTime!);
          setState(() {
            _tripDuration = _formatDuration(duration);
          });
        }
      });
    } else {
      _tripStartTime = null;
      _tripDuration = "0:00:00";
    }
  }

  String _formatDuration(Duration duration) {
    String twoDigits(int n) => n.toString().padLeft(2, "0");
    String twoDigitMinutes = twoDigits(duration.inMinutes.remainder(60));
    String twoDigitSeconds = twoDigits(duration.inSeconds.remainder(60));
    return "${duration.inHours}:$twoDigitMinutes:$twoDigitSeconds";
  }

  Future<void> _handleMethodCall(MethodCall call) async {
    switch (call.method) {
      case 'updateNavigationState':
        if (mounted) {
          final wasVisible = _isVisible;
          setState(() {
            _isVisible = true;
            _title = call.arguments['title'] ?? "";
            _text = call.arguments['text'] ?? "";
            _subText = call.arguments['subText'] ?? "";
            _iconBytes = call.arguments['icon'];
          });
          if (!wasVisible) {
            _startTripDurationCalculator();
          }
          _resetAodTimer();
        }
        break;
      case 'hideNavigationState':
        if (mounted) {
          setState(() {
            _isVisible = false;
          });
          _aodDimTimer?.cancel();
          _startTripDurationCalculator();
        }
        break;
    }
  }

  @override
  void dispose() {
    _aodDimTimer?.cancel();
    _tripDurationTimer?.cancel();
    super.dispose();
  }

  @override
  Widget build(BuildContext context) {
    if (!_isVisible) {
      return const SizedBox.shrink();
    }

    return GestureDetector(
      onDoubleTap: () => _resetAodTimer(),
      onVerticalDragEnd: (details) {
        if (details.primaryVelocity != null && details.primaryVelocity! > 300) {
          setState(() {
            _isVisible = false;
          });
        }
      },
      child: AnimatedOpacity(
        duration: const Duration(milliseconds: 500),
        opacity: _isDimmed ? 0.3 : 1.0,
        child: CustomPaint(
          painter: _SquircleBackgroundPainter(
            radius: 16.0,
            colors: [
              const Color(0xFF1A1A1A),
              Colors.black,
            ]
          ),
          child: ClipPath(
            clipper: _SquircleClipper(cornerRadius: 16.0),
            child: Stack(
              children: [
              Positioned.fill(
                child: Row(
                  children: [
                    Expanded(flex: 3, child: const SizedBox()),
                    Expanded(
                      flex: 7,
                      child: Padding(
                        padding: const EdgeInsets.fromLTRB(8.0, 16.0, 20.0, 16.0),
                        child: Column(
                          crossAxisAlignment: CrossAxisAlignment.stretch,
                          children: [
                            Row(
                              mainAxisAlignment: MainAxisAlignment.end,
                              children: [
                                Text(
                                  "NAVIGATION • $_tripDuration",
                                  style: const TextStyle(
                                    color: Colors.greenAccent,
                                    fontSize: 10,
                                    fontWeight: FontWeight.w600,
                                    letterSpacing: 1.2,
                                  ),
                                ),
                              ],
                            ),
                            const Spacer(),
                            Row(
                              crossAxisAlignment: CrossAxisAlignment.center,
                              children: [
                                if (_iconBytes != null) ...[
                                  ClipRRect(
                                    borderRadius: BorderRadius.circular(8.0),
                                    child: Image.memory(
                                      _iconBytes!,
                                      width: 48,
                                      height: 48,
                                      fit: BoxFit.cover,
                                    ),
                                  ),
                                  const SizedBox(width: 12),
                                ],
                                Expanded(
                                  child: Column(
                                    crossAxisAlignment: CrossAxisAlignment.start,
                                    children: [
                                      if (_title.isNotEmpty)
                                        Text(
                                          _title,
                                          style: const TextStyle(
                                            color: Colors.white,
                                            fontSize: 22,
                                            fontWeight: FontWeight.w800,
                                            fontFamily: 'sans-serif',
                                          ),
                                          maxLines: 2,
                                          overflow: TextOverflow.ellipsis,
                                        ),
                                      if (_text.isNotEmpty)
                                        Text(
                                          _text,
                                          style: const TextStyle(
                                            color: Colors.white70,
                                            fontSize: 16,
                                            fontWeight: FontWeight.w600,
                                            fontFamily: 'sans-serif',
                                          ),
                                          maxLines: 1,
                                          overflow: TextOverflow.ellipsis,
                                        ),
                                      if (_subText.isNotEmpty)
                                        Text(
                                          _subText,
                                          style: const TextStyle(
                                            color: Colors.white54,
                                            fontSize: 14,
                                            fontWeight: FontWeight.w500,
                                          ),
                                          maxLines: 1,
                                          overflow: TextOverflow.ellipsis,
                                        ),
                                    ],
                                  ),
                                ),
                              ],
                            ),
                            const Spacer(),
                          ],
                        ),
                      ),
                    ),
                  ],
                ),
                ),
              ],
            ),
          ),
        ),
      ),
    );
  }
}

/// 精确的超椭圆（Squircle）裁剪器
/// 使用2.84指数实现与屏幕圆角一致的平滑曲线
class _SquircleClipper extends CustomClipper<Path> {
  final double cornerRadius;
  static const double n = 2.84; // 超椭圆指数

  _SquircleClipper({required this.cornerRadius});

  @override
  Path getClip(Size size) {
    return _createSquirclePath(size, cornerRadius);
  }

  Path _createSquirclePath(Size size, double radius) {
    final w = size.width;
    final h = size.height;
    final r = radius;

    final path = Path();

    // 从左上角开始，顺时针绘制
    path.moveTo(0, r);

    // 左上角超椭圆
    _drawSquircleArc(path, r, r, r, math.pi, math.pi * 1.5);

    // 上边
    path.lineTo(w - r, 0);

    // 右上角超椭圆
    _drawSquircleArc(path, w - r, r, r, math.pi * 1.5, math.pi * 2);

    // 右边
    path.lineTo(w, h - r);

    // 右下角超椭圆
    _drawSquircleArc(path, w - r, h - r, r, 0, math.pi * 0.5);

    // 下边
    path.lineTo(r, h);

    // 左下角超椭圆
    _drawSquircleArc(path, r, h - r, r, math.pi * 0.5, math.pi);

    path.close();
    return path;
  }

  void _drawSquircleArc(
    Path path,
    double cx,
    double cy,
    double radius,
    double startAngle,
    double endAngle,
  ) {
    const int segments = 30;

    for (int i = 0; i <= segments; i++) {
      final t = i / segments;
      final angle = startAngle + (endAngle - startAngle) * t;

      final cosA = math.cos(angle);
      final sinA = math.sin(angle);

      // 超椭圆公式: r * sgn(t) * |t|^(2/n)
      final x = cx + radius * _sgn(cosA) * math.pow(cosA.abs(), 2.0 / n);
      final y = cy + radius * _sgn(sinA) * math.pow(sinA.abs(), 2.0 / n);

      path.lineTo(x, y);
    }
  }

  double _sgn(double x) => x < 0 ? -1.0 : 1.0;

  @override
  bool shouldReclip(_SquircleClipper oldClipper) =>
      oldClipper.cornerRadius != cornerRadius;
}

class _SquircleBackgroundPainter extends CustomPainter {
  final double radius;
  final List<Color> colors;

  _SquircleBackgroundPainter({
    required this.radius,
    required this.colors,
  });

  @override
  void paint(Canvas canvas, Size size) {
    final paint = Paint()
      ..shader = LinearGradient(
        begin: Alignment.topLeft,
        end: Alignment.bottomRight,
        colors: colors,
      ).createShader(Rect.fromLTWH(0, 0, size.width, size.height))
      ..style = PaintingStyle.fill;

    final path = _SquircleClipper(cornerRadius: radius).getClip(size);
    canvas.drawPath(path, paint);
  }

  @override
  bool shouldRepaint(_SquircleBackgroundPainter oldDelegate) {
    return oldDelegate.radius != radius || oldDelegate.colors != colors;
  }
}