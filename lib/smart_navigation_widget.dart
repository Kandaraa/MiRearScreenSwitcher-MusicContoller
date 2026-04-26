import 'dart:ui';
import 'dart:async';
import 'package:flutter/material.dart';
import 'package:flutter/services.dart';
import 'dart:typed_data';
import 'dart:math' as math;

class SmartNavigationWidget extends StatefulWidget {
  const SmartNavigationWidget({super.key});

  @override
  State<SmartNavigationWidget> createState() => _SmartNavigationWidgetState();
}

class _SmartNavigationWidgetState extends State<SmartNavigationWidget> {
  static const MethodChannel _channel = MethodChannel('com.display.switcher/navigation');

  // Navigation State
  bool _isActive = false;
  String _instructionText = "";
  String _distanceText = "";
  String _etaText = "";
  Uint8List? _turnIconBytes;

  // Trip State
  DateTime? _tripStartTime;
  Timer? _progressTimer;
  String _tripDurationStr = "00:00";

  // AOD State
  bool _isAodMode = false;
  Timer? _aodTimer;

  @override
  void initState() {
    super.initState();
    _channel.setMethodCallHandler(_handleMethodCall);

    // Timer to update trip duration every second
    _progressTimer = Timer.periodic(const Duration(seconds: 1), (timer) {
      if (_isActive && _tripStartTime != null) {
        _updateTripDuration();
      }
    });
  }

  void _updateTripDuration() {
    if (_tripStartTime == null) return;
    final duration = DateTime.now().difference(_tripStartTime!);
    final minutes = duration.inMinutes;
    final seconds = duration.inSeconds % 60;
    final hours = duration.inHours;

    setState(() {
      if (hours > 0) {
        _tripDurationStr = '${hours.toString().padLeft(2, '0')}:${(minutes % 60).toString().padLeft(2, '0')}:${seconds.toString().padLeft(2, '0')}';
      } else {
        _tripDurationStr = '${minutes.toString().padLeft(2, '0')}:${seconds.toString().padLeft(2, '0')}';
      }
    });
  }

  void _resetAodTimer() {
    _aodTimer?.cancel();
    setState(() {
      _isAodMode = false;
    });
    _aodTimer = Timer(const Duration(seconds: 5), () {
      if (mounted) {
        setState(() {
          _isAodMode = true;
        });
      }
    });
  }

  Future<void> _handleMethodCall(MethodCall call) async {
    if (call.method == 'updateNavigationState') {
      if (!mounted) return;

      final Map<dynamic, dynamic>? args = call.arguments as Map<dynamic, dynamic>?;
      if (args == null) return;

      setState(() {
        _isActive = true;

        // Parse the strings from the extras bundle
        _instructionText = args['nav_title'] as String? ?? '';
        _distanceText = args['nav_text'] as String? ?? '';
        _etaText = args['nav_sub_text'] as String? ?? '';

        // Convert byte[] to Uint8List for Image.memory
        if (args['nav_turn_icon'] is List<int>) {
          _turnIconBytes = Uint8List.fromList((args['nav_turn_icon'] as List<int>).cast<int>());
        } else if (args['nav_turn_icon'] is Uint8List) {
           _turnIconBytes = args['nav_turn_icon'] as Uint8List;
        }

        if (_tripStartTime == null) {
          _tripStartTime = DateTime.now();
        }
      });

      _resetAodTimer();
    } else if (call.method == 'hideNavigationState') {
      if (mounted) {
        setState(() {
          _isActive = false;
        });
      }
    }
  }

  @override
  void dispose() {
    _progressTimer?.cancel();
    _aodTimer?.cancel();
    super.dispose();
  }

  @override
  Widget build(BuildContext context) {
    if (!_isActive) {
      return const SizedBox.shrink();
    }

    final screenWidth = MediaQuery.of(context).size.width;

    return Scaffold(
      backgroundColor: Colors.black,
      body: Stack(
        children: [
          // Background Gradient matching MRSS 4-color scheme (dimmed for AOD)
          Positioned.fill(
             child: AnimatedOpacity(
               duration: const Duration(milliseconds: 500),
               opacity: _isAodMode ? 0.1 : 0.8,
               child: Container(
                  decoration: const BoxDecoration(
                    gradient: LinearGradient(
                      begin: Alignment.topLeft,
                      end: Alignment.bottomRight,
                      colors: [
                        Color(0xFFFF9D88), // 珊瑚橙
                        Color(0xFFFFB5C5), // 粉红
                        Color(0xFFE0B5DC), // 紫色
                        Color(0xFFA8C5E5), // 蓝色
                      ],
                    ),
                  ),
               ),
             )
          ),

          // Vignette for readability
          Positioned.fill(
            child: Container(
              decoration: const BoxDecoration(
                gradient: LinearGradient(
                  begin: Alignment.centerLeft,
                  end: Alignment.centerRight,
                  colors: [
                    Colors.black87,
                    Colors.black54,
                  ],
                ),
              ),
            ),
          ),

          // Main Content Layer wrapped in 2.84 Squircle Clipper
          Positioned.fill(
            child: ClipPath(
              clipper: _NavigationSquircleClipper(cornerRadius: 16.0),
              child: AnimatedOpacity(
                duration: const Duration(milliseconds: 500),
                opacity: _isAodMode ? 0.5 : 1.0,
                child: Padding(
                  // 30% left camera cutoff zone
                  padding: EdgeInsets.only(left: screenWidth * 0.3),
                  child: Column(
                    mainAxisAlignment: MainAxisAlignment.center,
                    crossAxisAlignment: CrossAxisAlignment.start,
                    children: [
                       // Top Half: Direction Icon & Instructions
                       Expanded(
                         flex: 1,
                         child: Padding(
                           padding: const EdgeInsets.only(top: 16.0, right: 16.0),
                           child: Row(
                             crossAxisAlignment: CrossAxisAlignment.end,
                             children: [
                                if (_turnIconBytes != null)
                                   Image.memory(
                                     _turnIconBytes!,
                                     width: 48,
                                     height: 48,
                                     fit: BoxFit.contain,
                                     color: Colors.white,
                                   )
                                else
                                   const Icon(Icons.navigation, color: Colors.white, size: 48),
                                const SizedBox(width: 12),
                                Expanded(
                                  child: Column(
                                    mainAxisAlignment: MainAxisAlignment.end,
                                    crossAxisAlignment: CrossAxisAlignment.start,
                                    children: [
                                      Text(
                                        _distanceText,
                                        style: const TextStyle(
                                          color: Colors.white,
                                          fontSize: 24,
                                          fontWeight: FontWeight.w800,
                                        ),
                                        maxLines: 1,
                                        overflow: TextOverflow.ellipsis,
                                      ),
                                      Text(
                                        _instructionText,
                                        style: const TextStyle(
                                          color: Colors.white70,
                                          fontSize: 16,
                                          fontWeight: FontWeight.w600,
                                        ),
                                        maxLines: 1,
                                        overflow: TextOverflow.ellipsis,
                                      ),
                                    ],
                                  ),
                                ),
                             ],
                           ),
                         ),
                       ),

                       const Padding(
                         padding: EdgeInsets.only(right: 16.0),
                         child: Divider(color: Colors.white24, height: 24),
                       ),

                       // Bottom Half: ETA and Trip info
                       Expanded(
                         flex: 1,
                         child: Padding(
                           padding: const EdgeInsets.only(right: 16.0, bottom: 16.0),
                           child: Row(
                             mainAxisAlignment: MainAxisAlignment.spaceBetween,
                             crossAxisAlignment: CrossAxisAlignment.start,
                             children: [
                               Column(
                                 crossAxisAlignment: CrossAxisAlignment.start,
                                 children: [
                                   const Text("ETA & DISTANCE", style: TextStyle(color: Colors.white54, fontSize: 10, fontWeight: FontWeight.bold)),
                                   const SizedBox(height: 2),
                                   Text(
                                     _etaText,
                                     style: const TextStyle(color: Colors.white, fontSize: 14, fontWeight: FontWeight.w600),
                                   ),
                                 ],
                               ),
                               Column(
                                 crossAxisAlignment: CrossAxisAlignment.end,
                                 children: [
                                   const Text("TRIP TIME", style: TextStyle(color: Colors.white54, fontSize: 10, fontWeight: FontWeight.bold)),
                                   const SizedBox(height: 2),
                                   Text(
                                     _tripDurationStr,
                                     style: const TextStyle(color: Colors.white, fontSize: 14, fontWeight: FontWeight.w600),
                                   ),
                                 ],
                               ),
                             ],
                           ),
                         ),
                       )
                    ],
                  ),
                ),
              ),
            ),
          ),
        ],
      ),
    );
  }
}

/// 精确的超椭圆（Squircle）裁剪器
/// 使用2.84指数实现与屏幕圆角一致的平滑曲线
class _NavigationSquircleClipper extends CustomClipper<Path> {
  final double cornerRadius;
  static const double n = 2.84; // 超椭圆指数

  _NavigationSquircleClipper({required this.cornerRadius});

  @override
  Path getClip(Size size) {
    return _createSquirclePath(size, cornerRadius);
  }

  Path _createSquirclePath(Size size, double radius) {
    final w = size.width;
    final h = size.height;
    final r = radius;

    final path = Path();

    path.moveTo(0, r);
    _drawSquircleArc(path, r, r, r, math.pi, math.pi * 1.5);
    path.lineTo(w - r, 0);
    _drawSquircleArc(path, w - r, r, r, math.pi * 1.5, math.pi * 2);
    path.lineTo(w, h - r);
    _drawSquircleArc(path, w - r, h - r, r, 0, math.pi * 0.5);
    path.lineTo(r, h);
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
      final x = cx + radius * _sgn(cosA) * math.pow(cosA.abs(), 2.0 / n);
      final y = cy + radius * _sgn(sinA) * math.pow(sinA.abs(), 2.0 / n);
      path.lineTo(x, y);
    }
  }

  double _sgn(double x) => x < 0 ? -1.0 : 1.0;

  @override
  bool shouldReclip(_NavigationSquircleClipper oldClipper) =>
      oldClipper.cornerRadius != cornerRadius;
}
