/*
 * Author: AntiOblivionis
 * QQ: 319641317
 * Github: https://github.com/GoldenglowSusie/
 * Bilibili: 罗德岛T0驭械术师澄闪
 * 
 * Chief Tester: 汐木泽
 * 
 * Co-developed with AI assistants:
 * - Cursor
 * - Claude-4.5-Sonnet
 * - GPT-5
 * - Gemini-2.5-Pro
 */

import 'package:flutter/material.dart';
import 'package:flutter/services.dart';
import 'dart:async';
import 'dart:ui';
import 'dart:math' as math;
import 'dart:typed_data';
import 'package:shared_preferences/shared_preferences.dart';
import 'package:flutter_localizations/flutter_localizations.dart';
import 'l10n/app_localizations.dart';
import 'rear_media_widget.dart';

void main() {
  // 设置沉浸式状态栏（透明状态栏）
  WidgetsFlutterBinding.ensureInitialized();
  SystemChrome.setSystemUIOverlayStyle(
    const SystemUiOverlayStyle(
      statusBarColor: Colors.transparent,
      statusBarIconBrightness: Brightness.light,
      systemNavigationBarColor: Colors.transparent,
      systemNavigationBarIconBrightness: Brightness.light,
    ),
  );
  SystemChrome.setEnabledSystemUIMode(SystemUiMode.edgeToEdge);

  runApp(const DisplaySwitcherApp());
}

class DisplaySwitcherApp extends StatelessWidget {
  const DisplaySwitcherApp({super.key});

  @override
  Widget build(BuildContext context) {
    return MaterialApp(
      title: 'MRSS',
      theme: ThemeData(
        colorScheme: ColorScheme.fromSeed(seedColor: Colors.blue),
        useMaterial3: true,
      ),
      supportedLocales: const [
        Locale('en', ''),
        Locale('zh', 'CN'),
        Locale('zh', 'TW'),
      ],
      localizationsDelegates: const [
        AppLocalizations.delegate,
        GlobalMaterialLocalizations.delegate,
        GlobalWidgetsLocalizations.delegate,
        GlobalCupertinoLocalizations.delegate,
      ],
      localeResolutionCallback: (locale, supportedLocales) {
        // Only support en, zh_CN, zh_TW. All others fallback to English.
        if (locale != null) {
          // Check for exact match
          for (var supportedLocale in supportedLocales) {
            if (supportedLocale.languageCode == locale.languageCode &&
                supportedLocale.countryCode == locale.countryCode) {
              return supportedLocale;
            }
          }

          // Check for language-only match
          if (locale.languageCode == 'zh') {
            // For Chinese, check country code
            if (locale.countryCode == 'TW' || locale.countryCode == 'HK') {
              return const Locale('zh', 'TW'); // Traditional Chinese
            } else {
              return const Locale('zh', 'CN'); // Simplified Chinese (default)
            }
          } else if (locale.languageCode == 'en') {
            return const Locale('en', '');
          }
        }

        // Fallback to English for all unsupported languages
        return const Locale('en', '');
      },
      initialRoute: '/',
      routes: {
        '/': (context) => const HomePage(),
        '/rearMedia': (context) => const RearMediaWidget(),
      },
    );
  }
}

class HomePage extends StatefulWidget {
  const HomePage({super.key});

  @override
  State<HomePage> createState() => _HomePageState();
}

enum ShizukuStatus { checking, running, error }

class _HomePageState extends State<HomePage> {
  static const platform = MethodChannel('com.display.switcher/task');

  // Status Enum
  ShizukuStatus _shizukuStatus = ShizukuStatus.checking;
  bool _shizukuRunning = false;
  bool _checkShizukuInFlight = false;
  bool _dpiQueryInFlight = false;
  bool _rotationQueryInFlight = false;
  Timer? _backendStatusDebounceTimer;
  String _executionBackend = 'shizuku'; // 'shizuku' or 'local_adb'
  String _localAdbStateCode = 'NEEDS_PAIRING'; // 'READY', 'WAITING_FOR_WIFI', 'NEEDS_PAIRING'
  bool _adbBusy = false;
  // String _statusMessage = 'Checking Shizuku...'; // Removed
  String _customErrorTitle = ''; // For specific error types
  bool _isLoading = false;
  bool _hasError = false; // 是否有错误
  String _errorDetail = ''; // 错误详情

  // V15: 背屏DPI相关
  int _currentRearDpi = 0;
  bool _dpiLoading = true; // DPI加载状态
  final TextEditingController _dpiController = TextEditingController();
  final FocusNode _dpiFocusNode = FocusNode();

  // V2.1: 显示控制相关
  int _currentRotation = 0; // 当前旋转方向 (0=0°, 1=90°, 2=180°, 3=270°)

  // V2.2: 接近传感器开关
  bool _proximitySensorEnabled = true; // 默认打开

  // V2.3: 充电动画开关
  bool _chargingAnimationEnabled = true; // 默认打开

  // V2.5: 背屏常亮开关
  bool _keepScreenOnEnabled = true; // 默认打开

  // V3.5: 未投放应用时常亮开关（与背屏常亮互斥）
  bool _alwaysWakeUpEnabled = false; // 默认关闭

  // V3.5: 充电动画常亮开关
  bool _chargingAlwaysOnEnabled = false; // 默认关闭

  // V2.4: 通知功能
  bool _notificationEnabled = false; // 默认关闭（需要授权）

  // V3.6: Smart Media Rear Controller
  bool _smartMediaEnabled = false; // Default disabled

  // V3.7: Media Pull Pill & Album Blur Customization (appears when _smartMediaEnabled is true)
  double _mediaAlbumBlur = 20.0; // 0.0 .. 50.0 px (0% .. 100%, step 5%)
  bool _mediaPillEnabled = true;
  double _mediaPillScale = 3.0; // 1.0x .. 5.0x (step 0.5)
  int _mediaPillColor = 0xFFD60A0A; // Default crimson liquid glass

  static const List<int> _mediaPillColorPresets = [
    0xFFD60A0A, // Crimson (default)
    0xFF06B6D4, // Cyan
    0xFF3B82F6, // Sapphire
    0xFF8B5CF6, // Violet
    0xFF10B981, // Emerald
    0xFFF59E0B, // Amber
    0xFFEC4899, // Rose
    0xFFE2E8F0, // Frost White
  ];

  @override
  void initState() {
    super.initState();
    _checkShizuku();
    _loadSettings(); // 加载所有设置
    _setupMethodCallHandler();
    _loadProximitySensorSetting(); // 加载接近传感器设置

    // 通知权限会在Shizuku授权完成后自动请求（见_checkShizuku）

    // 延迟获取DPI和旋转，等待TaskService连接
    Future.delayed(const Duration(seconds: 2), () {
      if (_shizukuRunning) {
        _getCurrentRearDpi();
        _getCurrentRotation();
      }
    });
  }

  @override
  void dispose() {
    _backendStatusDebounceTimer?.cancel();
    _dpiController.dispose();
    _dpiFocusNode.dispose();
    super.dispose();
  }

  void _setupMethodCallHandler() {
    platform.setMethodCallHandler((call) async {
      if (call.method == 'onShizukuPermissionChanged') {
        final granted = call.arguments as bool;
        print('Shizuku permission changed: $granted');
        final bool wasReady = _shizukuRunning;
        await _checkShizuku();

        if (granted) {
          print('✓ Shizuku已授权，立即请求通知权限');
          _requestNotificationPermission();
        }
        if (!wasReady && _shizukuRunning) {
          _getCurrentRearDpi();
          _getCurrentRotation();
        }
      } else if (call.method == 'onBackendStatusChanged') {
        final dynamic args = call.arguments;
        if (args is Map) {
          final String? evtBackend = args['backend']?.toString();
          final bool evtReady = args['ready'] == true;
          // Ignore duplicate status broadcasts that do not change readiness or backend mode
          if (_shizukuStatus != ShizukuStatus.checking &&
              _shizukuRunning == evtReady &&
              (evtBackend == null || evtBackend == _executionBackend)) {
            return;
          }
        }
        _backendStatusDebounceTimer?.cancel();
        _backendStatusDebounceTimer = Timer(const Duration(milliseconds: 250), () async {
          if (!mounted) return;
          final bool wasReady = _shizukuRunning;
          await _checkShizuku();
          // Only trigger DPI/rotation reload when backend readiness transitions from false -> true
          if (!wasReady && _shizukuRunning) {
            _getCurrentRearDpi();
            _getCurrentRotation();
          }
        });
      }
    });
  }

  Future<void> _requestNotificationPermission() async {
    // Android 13+ 需要请求通知权限
    try {
      await platform.invokeMethod('requestNotificationPermission');
      print('通知权限请求已发送');
    } catch (e) {
      print('请求通知权限失败: $e');
    }
  }

  // V15: 获取当前背屏DPI
  Future<void> _getCurrentRearDpi() async {
    if (_dpiQueryInFlight || !_shizukuRunning) return;
    _dpiQueryInFlight = true;
    if (mounted) {
      setState(() {
        _dpiLoading = true;
      });
    }

    try {
      // 最多重试3次，若后端未就绪则立即退出
      for (int i = 0; i < 3; i++) {
        if (!mounted || !_shizukuRunning) break;
        try {
          final int dpi = await platform.invokeMethod('getCurrentRearDpi');
          if (mounted) {
            setState(() {
              _currentRearDpi = dpi;
              _dpiController.text = dpi.toString();
              _dpiLoading = false;
            });
          }
          print('当前背屏DPI: $dpi');
          return; // 成功就退出
        } catch (e) {
          print('获取背屏DPI失败 (尝试 ${i + 1}/3): $e');
          if (e.toString().contains('NOT_READY') || e.toString().contains('fail-fast')) {
            break;
          }
          if (i < 2) {
            await Future.delayed(const Duration(seconds: 1));
          }
        }
      }

      if (mounted) {
        setState(() {
          _dpiLoading = false;
          _currentRearDpi = 0;
        });
      }
      print('获取背屏DPI最终失败');
    } finally {
      _dpiQueryInFlight = false;
    }
  }

  // V15: 设置背屏DPI
  Future<void> _setRearDpi(int dpi) async {
    if (_isLoading) return;

    setState(() {
      _isLoading = true;
    });

    try {
      // 先尝试重新连接TaskService，确保连接正常
      await platform.invokeMethod('ensureTaskServiceConnected');

      // 等待连接建立
      await Future.delayed(const Duration(milliseconds: 500));

      await platform.invokeMethod('setRearDpi', {'dpi': dpi});

      // 刷新当前DPI
      await _getCurrentRearDpi();

      if (mounted) {
        ScaffoldMessenger.of(context).showSnackBar(
          SnackBar(
            content: Text(
              '${AppLocalizations.of(context).translate('toast_dpi_set')} $dpi',
            ),
          ),
        );
      }
    } catch (e) {
      print('设置背屏DPI失败: $e');
      if (mounted) {
        ScaffoldMessenger.of(context).showSnackBar(
          SnackBar(
            content: Text(
              '${AppLocalizations.of(context).translate('toast_set_failed')} $e. ${AppLocalizations.of(context).translate('toast_ensure_shizuku')}',
            ),
          ),
        );
      }
    } finally {
      setState(() {
        _isLoading = false;
      });
    }
  }

  // V15: 还原背屏DPI
  Future<void> _resetRearDpi() async {
    if (_isLoading) return;

    setState(() {
      _isLoading = true;
    });

    try {
      // 先尝试重新连接TaskService，确保连接正常
      await platform.invokeMethod('ensureTaskServiceConnected');

      // 等待连接建立
      await Future.delayed(const Duration(milliseconds: 500));

      await platform.invokeMethod('resetRearDpi');

      // 刷新当前DPI
      await _getCurrentRearDpi();

      if (mounted) {
        ScaffoldMessenger.of(context).showSnackBar(
          SnackBar(
            content: Text(
              AppLocalizations.of(context).translate('toast_dpi_reset'),
            ),
          ),
        );
      }
    } catch (e) {
      print('还原背屏DPI失败: $e');
      if (mounted) {
        ScaffoldMessenger.of(context).showSnackBar(
          SnackBar(
            content: Text(
              '${AppLocalizations.of(context).translate('toast_reset_failed')} $e. ${AppLocalizations.of(context).translate('toast_ensure_shizuku')}',
            ),
          ),
        );
      }
    } finally {
      setState(() {
        _isLoading = false;
      });
    }
  }

  Future<void> _checkShizuku() async {
    if (_checkShizukuInFlight) return;
    _checkShizukuInFlight = true;
    if (!_shizukuRunning) {
      setState(() {
        _shizukuStatus = ShizukuStatus.checking;
        _hasError = false;
        _errorDetail = '';
      });
    }

    try {
      try {
        final String? backend = await platform.invokeMethod('getExecutionBackend');
        if (backend != null && backend.isNotEmpty) {
          _executionBackend = backend;
        }
        final dynamic detailed = await platform.invokeMethod('getBackendDetailedState');
        if (detailed is Map) {
          final String? sc = detailed['stateCode']?.toString();
          if (sc != null && sc.isNotEmpty) {
            _localAdbStateCode = sc;
          }
        }
      } catch (_) {}

      // 简化检查：直接调用Java层，增加超时
      final result = await platform
          .invokeMethod('checkShizuku')
          .timeout(const Duration(seconds: 4));

      try {
        final dynamic detailedAfter = await platform.invokeMethod('getBackendDetailedState');
        if (detailedAfter is Map) {
          final String? sc = detailedAfter['stateCode']?.toString();
          if (sc != null && sc.isNotEmpty) {
            _localAdbStateCode = sc;
          }
        }
      } catch (_) {}

      if (!mounted) return;

      setState(() {
        _shizukuRunning = result == true;
        _hasError = false;
        _errorDetail = '';

        if (_shizukuRunning) {
          _shizukuStatus = ShizukuStatus.running;
          _localAdbStateCode = 'READY';
          _requestNotificationPermission();
        } else {
          _hasError = true;
          _shizukuStatus = ShizukuStatus.error;
          _customErrorTitle = _executionBackend == 'local_adb'
              ? AppLocalizations.of(context).translate(
                  _localAdbStateCode == 'WAITING_FOR_WIFI'
                      ? 'local_adb_waiting_wifi'
                      : 'local_adb_not_connected',
                )
              : '';
          _errorDetail = _executionBackend == 'local_adb'
              ? AppLocalizations.of(context).translate('local_adb_setup_guide')
              : AppLocalizations.of(context).translate('shizuku_permission_denied');
          // 获取详细信息帮助诊断
          _getDetailedStatus();
        }
      });
    } catch (e) {
      if (!mounted) return;

      // 解析异常类型
      String errorType = '';
      String errorMsg = e.toString();

      if (errorMsg.contains('binder') || errorMsg.contains('Binder')) {
        errorType = AppLocalizations.of(
          context,
        ).translate('error_shizuku_communication');
        _errorDetail = AppLocalizations.of(
          context,
        ).translate('error_shizuku_service_crashed');
      } else if (errorMsg.contains('permission') ||
          errorMsg.contains('Permission')) {
        errorType = AppLocalizations.of(
          context,
        ).translate('error_permission_denied');
        _errorDetail = AppLocalizations.of(
          context,
        ).translate('error_grant_in_shizuku');
      } else if (errorMsg.contains('RemoteException')) {
        errorType = AppLocalizations.of(
          context,
        ).translate('error_service_call_failed');
        _errorDetail = AppLocalizations.of(
          context,
        ).translate('error_task_service_no_response');
      } else if (errorMsg.contains('TimeoutException')) {
        errorType = AppLocalizations.of(
          context,
        ).translate('error_check_timeout');
        _errorDetail = AppLocalizations.of(
          context,
        ).translate('error_shizuku_timeout');
      } else {
        errorType = AppLocalizations.of(context).translate('error_unknown');
        _errorDetail = errorMsg.length > 50
            ? '${errorMsg.substring(0, 50)}...'
            : errorMsg;
      }
      setState(() {
        _shizukuRunning = false;
        _hasError = true;
        _shizukuStatus = ShizukuStatus.error;
        _customErrorTitle = errorType;
      });
    } finally {
      _checkShizukuInFlight = false;
    }
  }

  Future<void> _getDetailedStatus() async {
    try {
      final info = await platform.invokeMethod('getShizukuInfo');
      setState(() {
        _errorDetail = info.toString();
      });
    } catch (e) {
      // 获取详细信息失败，保持当前错误信息
    }
  }

  // V2.1: 重启应用
  Future<void> _restartApp() async {
    if (_isLoading) return;

    setState(() => _isLoading = true);

    try {
      // 确保TaskService连接
      await platform.invokeMethod('ensureTaskServiceConnected');
      await Future.delayed(const Duration(milliseconds: 500));

      // 检查是否有应用在背屏
      final result = await platform.invokeMethod('returnRearAppAndRestart');

      if (result == true) {
        // 成功返回主屏，退出应用
        SystemNavigator.pop();
      } else {
        // 没有应用在背屏，直接退出
        SystemNavigator.pop();
      }
    } catch (e) {
      // 出错也退出
      SystemNavigator.pop();
    }
  }

  // V2.2: 加载所有设置
  Future<void> _loadSettings() async {
    try {
      final prefs = await SharedPreferences.getInstance();
      setState(() {
        _proximitySensorEnabled =
            prefs.getBool('proximity_sensor_enabled') ?? true;
        _chargingAnimationEnabled =
            prefs.getBool('charging_animation_enabled') ?? true;
        _chargingAlwaysOnEnabled =
            prefs.getBool('charging_always_on_enabled') ??
            false; // V3.5: 加载充电动画常亮开关状态
        _keepScreenOnEnabled = prefs.getBool('keep_screen_on_enabled') ?? true;
        _alwaysWakeUpEnabled =
            prefs.getBool('always_wakeup_enabled') ??
            false; // V3.5: 加载未投放应用时常亮开关状态

        _notificationEnabled =
            prefs.getBool('notification_service_enabled') ??
            false; // V2.4: 加载背屏通知开关状态
        _smartMediaEnabled =
            prefs.getBool('smart_media_enabled') ??
            false; // Smart Media Rear Controller
        _mediaAlbumBlur =
            (prefs.getDouble('media_album_blur') ?? 20.0).clamp(0.0, 50.0);
        _mediaPillEnabled = prefs.getBool('media_pill_enabled') ?? true;
        _mediaPillScale =
            (prefs.getDouble('media_pill_scale') ?? 3.0).clamp(1.0, 5.0);
        _mediaPillColor = prefs.getInt('media_pill_color') ?? 0xFFD60A0A;
        _executionBackend =
            prefs.getString('execution_backend') ?? 'shizuku';
      });

      // Sync persisted media shade & pill customization to native mrss_settings
      _syncMediaAlbumBlurToNative();
      _syncMediaPillSettingsToNative();

      // 启动充电服务（如果开关打开）
      if (_chargingAnimationEnabled) {
        _startChargingService();
      }

      // 检查通知监听权限（但不覆盖开关状态）
      _checkNotificationPermission();

      // V2.4: 如果通知开关开启，启动NotificationService
      if (_notificationEnabled) {
        _startNotificationService();
      }
    } catch (e) {
      print('加载设置失败: $e');
    }
  }

  // V2.2: 加载接近传感器设置
  Future<void> _loadProximitySensorSetting() async {
    try {
      final prefs = await SharedPreferences.getInstance();
      setState(() {
        _proximitySensorEnabled =
            prefs.getBool('proximity_sensor_enabled') ?? true;
      });
    } catch (e) {
      print('加载接近传感器设置失败: $e');
    }
  }

  // V2.4: 检查通知监听权限
  Future<void> _checkNotificationPermission() async {
    try {
      final bool hasPermission = await platform.invokeMethod(
        'checkNotificationListenerPermission',
      );
      // 只更新权限状态，不覆盖开关状态
      // _notificationEnabled 现在由 SharedPreferences 中的开关状态控制
      print('通知监听权限状态: $hasPermission');
    } catch (e) {
      print('检查通知权限失败: $e');
    }
  }

  // V2.4: 启动通知服务
  Future<void> _startNotificationService() async {
    try {
      await platform.invokeMethod('startNotificationService');
      print('NotificationService已启动');
    } catch (e) {
      print('启动NotificationService失败: $e');
    }
  }

  // V2.4: 切换通知服务
  Future<void> _toggleNotificationService(bool enabled) async {
    if (enabled) {
      // 先检查权限
      final bool hasPermission = await platform.invokeMethod(
        'checkNotificationListenerPermission',
      );
      if (!hasPermission) {
        // 打开设置页面授权
        await platform.invokeMethod('openNotificationListenerSettings');
        return;
      }
    }

    try {
      // 先保存到SharedPreferences
      final prefs = await SharedPreferences.getInstance();
      await prefs.setBool('notification_service_enabled', enabled);

      // 通知Service更新状态
      await platform.invokeMethod('toggleNotificationService', {
        'enabled': enabled,
      });

      // 如果开启，启动NotificationService
      if (enabled) {
        await _startNotificationService();
      } else {
        // V3.6: Smart media controller requires notifications. If notifications are disabled,
        // force disable the smart media controller as well.
        if (_smartMediaEnabled) {
          await _toggleSmartMedia(false);
        }
      }

      setState(() {
        _notificationEnabled = enabled;
      });
      print('背屏通知服务已${enabled ? "启用" : "禁用"}');
    } catch (e) {
      print('切换背屏通知服务失败: $e');
      // 切换失败，恢复原状态
      setState(() {
        _notificationEnabled = !enabled;
      });
    }
  }

  // V2.4: 打开应用选择页面
  Future<void> _openAppSelectionPage() async {
    await Navigator.push(
      context,
      MaterialPageRoute(builder: (context) => const AppSelectionPage()),
    );
  }

  // V2.2: 切换接近传感器开关
  Future<void> _toggleProximitySensor(bool enabled) async {
    try {
      // 先保存到SharedPreferences
      final prefs = await SharedPreferences.getInstance();
      await prefs.setBool('proximity_sensor_enabled', enabled);

      // 通知Service更新状态
      await platform.invokeMethod('setProximitySensorEnabled', {
        'enabled': enabled,
      });

      setState(() {
        _proximitySensorEnabled = enabled;
      });
      print('接近传感器已${enabled ? "启用" : "禁用"}');
    } catch (e) {
      print('切换接近传感器失败: $e');
      // 切换失败，恢复原状态
      setState(() {
        _proximitySensorEnabled = !enabled;
      });
    }
  }

  // V2.3: 切换充电动画开关
  Future<void> _toggleChargingAnimation(bool enabled) async {
    try {
      // 先保存到SharedPreferences
      final prefs = await SharedPreferences.getInstance();
      await prefs.setBool('charging_animation_enabled', enabled);

      // 启动或停止充电服务
      await platform.invokeMethod('toggleChargingService', {
        'enabled': enabled,
      });

      setState(() {
        _chargingAnimationEnabled = enabled;
      });
      print('充电动画已${enabled ? "启用" : "禁用"}');
    } catch (e) {
      print('切换充电动画失败: $e');
      // 切换失败，恢复原状态
      setState(() {
        _chargingAnimationEnabled = !enabled;
      });
    }
  }

  // V2.3: 启动充电服务
  Future<void> _startChargingService() async {
    try {
      await platform.invokeMethod('toggleChargingService', {'enabled': true});
    } catch (e) {
      print('启动充电服务失败: $e');
    }
  }

  // V2.5: 切换背屏常亮开关
  Future<void> _toggleKeepScreenOn(bool enabled) async {
    try {
      final prefs = await SharedPreferences.getInstance();
      await prefs.setBool('keep_screen_on_enabled', enabled);

      // V3.5: 如果开启，则关闭未投放应用时常亮
      if (enabled && _alwaysWakeUpEnabled) {
        await prefs.setBool('always_wakeup_enabled', false);
        await platform.invokeMethod('setAlwaysWakeUpEnabled', {
          'enabled': false,
        });
      }

      // 通过Intent通知RearScreenKeeperService
      await platform.invokeMethod('setKeepScreenOnEnabled', {
        'enabled': enabled,
      });

      setState(() {
        _keepScreenOnEnabled = enabled;
        if (enabled) _alwaysWakeUpEnabled = false; // V3.5: 互斥关闭
      });
      print('背屏常亮已${enabled ? "启用" : "禁用"}');
    } catch (e) {
      print('切换背屏常亮失败: $e');
      // 切换失败，恢复原状态
      setState(() {
        _keepScreenOnEnabled = !enabled;
      });
    }
  }

  // V3.5: 切换未投放应用时常亮开关
  Future<void> _toggleAlwaysWakeUp(bool enabled) async {
    try {
      final prefs = await SharedPreferences.getInstance();
      await prefs.setBool('always_wakeup_enabled', enabled);

      // V3.5: 如果开启，则关闭背屏常亮
      if (enabled && _keepScreenOnEnabled) {
        await prefs.setBool('keep_screen_on_enabled', false);
        await platform.invokeMethod('setKeepScreenOnEnabled', {
          'enabled': false,
        });
      }

      // 通过Intent通知AlwaysWakeUpService
      await platform.invokeMethod('setAlwaysWakeUpEnabled', {
        'enabled': enabled,
      });

      setState(() {
        _alwaysWakeUpEnabled = enabled;
        if (enabled) _keepScreenOnEnabled = false; // V3.5: 互斥关闭
      });
      print('未投放应用时常亮已${enabled ? "启用" : "禁用"}');
    } catch (e) {
      print('切换未投放应用时常亮失败: $e');
      // 切换失败，恢复原状态
      setState(() {
        _alwaysWakeUpEnabled = !enabled;
      });
    }
  }

  // V3.5: 切换充电动画常亮开关
  Future<void> _toggleChargingAlwaysOn(bool enabled) async {
    try {
      final prefs = await SharedPreferences.getInstance();
      await prefs.setBool('charging_always_on_enabled', enabled);

      // 通过Intent通知ChargingAlwaysOnService
      await platform.invokeMethod('setChargingAlwaysOnEnabled', {
        'enabled': enabled,
      });

      setState(() {
        _chargingAlwaysOnEnabled = enabled;
      });
      print('充电动画常亮已${enabled ? "启用" : "禁用"}');
    } catch (e) {
      print('切换充电动画常亮失败: $e');
      // 切换失败，恢复原状态
      setState(() {
        _chargingAlwaysOnEnabled = !enabled;
      });
    }
  }

  // V3.6: Smart Media Rear Controller
  Future<void> _toggleSmartMedia(bool enabled) async {
    if (enabled) {
      // 先检查权限
      final bool hasPermission = await platform.invokeMethod(
        'checkNotificationListenerPermission',
      ) ?? false;
      if (!hasPermission) {
        // 打开设置页面授权
        await platform.invokeMethod('openNotificationListenerSettings');
        return;
      }
    }

    try {
      final prefs = await SharedPreferences.getInstance();
      await prefs.setBool('smart_media_enabled', enabled);

      // Notify native service to update listener states
      await platform.invokeMethod('setSmartMediaEnabled', {
        'enabled': enabled,
      });

      setState(() {
        _smartMediaEnabled = enabled;
      });
      if (enabled) {
        await _syncMediaAlbumBlurToNative();
        await _syncMediaPillSettingsToNative();
      }
      print('Smart Media Controller turned ${enabled ? "ON" : "OFF"}');
    } catch (e) {
      print('Failed to toggle Smart Media Controller: $e');
      setState(() {
        _smartMediaEnabled = !enabled;
      });
    }
  }

  Future<void> _syncMediaAlbumBlurToNative() async {
    try {
      await platform.invokeMethod('setMediaAlbumBlur', {
        'blur': _mediaAlbumBlur,
      });
    } catch (_) {}
  }

  Future<void> _updateMediaAlbumBlur(
    double blur, {
    bool syncNative = true,
  }) async {
    final double snapped = ((blur / 2.5).round() * 2.5).clamp(0.0, 50.0);
    setState(() {
      _mediaAlbumBlur = snapped;
    });

    if (!syncNative) return;

    try {
      final prefs = await SharedPreferences.getInstance();
      await prefs.setDouble('media_album_blur', snapped);
      await platform.invokeMethod('setMediaAlbumBlur', {
        'blur': snapped,
      });
    } catch (_) {}
  }

  Widget _buildAlbumBlurPreview() {
    // Map native 0..50px radius to preview sigma (scaled proportionally for compact preview card)
    final double previewSigma = _mediaAlbumBlur * 0.32;
    return ClipRRect(
      borderRadius: BorderRadius.circular(14),
      child: Container(
        height: 66,
        width: double.infinity,
        decoration: BoxDecoration(
          border: Border.all(color: Colors.white.withValues(alpha: 0.16)),
          borderRadius: BorderRadius.circular(14),
        ),
        child: Stack(
          fit: StackFit.expand,
          children: [
            // Stylized colorful album cover art blurred at the selected intensity
            ImageFiltered(
              imageFilter: ImageFilter.blur(
                sigmaX: previewSigma,
                sigmaY: previewSigma,
                tileMode: TileMode.clamp,
              ),
              child: CustomPaint(
                painter: _AlbumArtBlurPreviewPainter(),
              ),
            ),
            // Same dark top-to-bottom vignette scrim used in RearMediaShadeController
            DecoratedBox(
              decoration: BoxDecoration(
                gradient: LinearGradient(
                  begin: Alignment.topCenter,
                  end: Alignment.bottomCenter,
                  colors: [
                    Colors.black.withValues(alpha: 0.35),
                    Colors.black.withValues(alpha: 0.55),
                    Colors.black.withValues(alpha: 0.72),
                  ],
                ),
              ),
            ),
            // Mini rear media widget silhouette (title + progress + controls)
            Padding(
              padding: const EdgeInsets.symmetric(horizontal: 14, vertical: 10),
              child: Row(
                children: [
                  Expanded(
                    child: Column(
                      crossAxisAlignment: CrossAxisAlignment.start,
                      mainAxisAlignment: MainAxisAlignment.center,
                      children: [
                        Container(
                          width: 96,
                          height: 8,
                          decoration: BoxDecoration(
                            color: Colors.white.withValues(alpha: 0.92),
                            borderRadius: BorderRadius.circular(4),
                          ),
                        ),
                        const SizedBox(height: 6),
                        Container(
                          width: 58,
                          height: 5,
                          decoration: BoxDecoration(
                            color: Colors.white.withValues(alpha: 0.62),
                            borderRadius: BorderRadius.circular(3),
                          ),
                        ),
                        const SizedBox(height: 10),
                        ClipRRect(
                          borderRadius: BorderRadius.circular(2),
                          child: SizedBox(
                            height: 3,
                            child: Stack(
                              children: [
                                Container(
                                  color: Colors.white.withValues(alpha: 0.25),
                                ),
                                FractionallySizedBox(
                                  widthFactor: 0.58,
                                  child: Container(
                                    color: Colors.white.withValues(alpha: 0.9),
                                  ),
                                ),
                              ],
                            ),
                          ),
                        ),
                      ],
                    ),
                  ),
                  const SizedBox(width: 16),
                  Icon(
                    Icons.skip_previous_rounded,
                    size: 18,
                    color: Colors.white.withValues(alpha: 0.85),
                  ),
                  const SizedBox(width: 8),
                  Container(
                    width: 28,
                    height: 28,
                    decoration: BoxDecoration(
                      shape: BoxShape.circle,
                      color: Colors.white.withValues(alpha: 0.24),
                      border: Border.all(
                        color: Colors.white.withValues(alpha: 0.35),
                      ),
                    ),
                    child: const Icon(
                      Icons.pause_rounded,
                      size: 16,
                      color: Colors.white,
                    ),
                  ),
                  const SizedBox(width: 8),
                  Icon(
                    Icons.skip_next_rounded,
                    size: 18,
                    color: Colors.white.withValues(alpha: 0.85),
                  ),
                ],
              ),
            ),
          ],
        ),
      ),
    );
  }

  Future<void> _syncMediaPillSettingsToNative() async {
    try {
      await platform.invokeMethod('setMediaPillSettings', {
        'enabled': _mediaPillEnabled,
        'scale': _mediaPillScale,
        'color': _mediaPillColor,
      });
    } catch (e) {
      print('Failed syncing media pill settings: $e');
    }
  }

  Future<void> _updateMediaPillSettings({
    bool? enabled,
    double? scale,
    int? color,
    bool syncNative = true,
  }) async {
    final nextEnabled = enabled ?? _mediaPillEnabled;
    final nextScale = ((scale ?? _mediaPillScale) * 2).round() / 2.0;
    final clampedScale = nextScale.clamp(1.0, 5.0);
    final nextColor = color ?? _mediaPillColor;

    setState(() {
      _mediaPillEnabled = nextEnabled;
      _mediaPillScale = clampedScale;
      _mediaPillColor = nextColor;
    });

    if (!syncNative) return;

    try {
      final prefs = await SharedPreferences.getInstance();
      await prefs.setBool('media_pill_enabled', nextEnabled);
      await prefs.setDouble('media_pill_scale', clampedScale);
      await prefs.setInt('media_pill_color', nextColor);

      await platform.invokeMethod('setMediaPillSettings', {
        'enabled': nextEnabled,
        'scale': clampedScale,
        'color': nextColor,
      });
    } catch (e) {
      print('Failed updating media pill settings: $e');
    }
  }

  Color _derivePreviewGlassFill(int tintArgb) {
    if ((tintArgb & 0x00FFFFFF) == 0x00D60A0A) {
      return const Color(0x3DBF4545);
    }
    final r0 = (tintArgb >> 16) & 0xFF;
    final g0 = (tintArgb >> 8) & 0xFF;
    final b0 = tintArgb & 0xFF;
    final r = ((r0 * 0.78) + (115 * 0.22)).round().clamp(0, 255);
    final g = ((g0 * 0.78) + (115 * 0.22)).round().clamp(0, 255);
    final b = ((b0 * 0.78) + (115 * 0.22)).round().clamp(0, 255);
    return Color.fromARGB(0x3D, r, g, b);
  }

  Widget _buildMediaPillPreview() {
    final baseRgb = _mediaPillColor & 0x00FFFFFF;
    final strokeTop = Color(0xC8000000 | baseRgb);
    final strokeBottom = Color(0x34000000 | baseRgb);
    final fillColor = _derivePreviewGlassFill(_mediaPillColor);

    // Visual preview scale mapped gently (1.0x..5.0x -> 1.1x..1.85x in UI card)
    final double previewScale = 1.05 + ((_mediaPillScale - 1.0) / 4.0) * 0.80;
    final double w = 100.0 * previewScale;
    final double h = 24.0 * previewScale;

    return Container(
      width: double.infinity,
      padding: const EdgeInsets.symmetric(vertical: 14),
      decoration: BoxDecoration(
        color: Colors.black.withValues(alpha: 0.82),
        borderRadius: BorderRadius.circular(14),
        border: Border.all(color: Colors.white.withValues(alpha: 0.14)),
      ),
      child: Center(
        child: AnimatedContainer(
          duration: const Duration(milliseconds: 160),
          curve: Curves.easeOutCubic,
          width: w,
          height: h,
          child: CustomPaint(
            painter: _MediaPillPreviewPainter(
              fillColor: fillColor,
              strokeTopColor: strokeTop,
              strokeBottomColor: strokeBottom,
              scale: previewScale,
            ),
          ),
        ),
      ),
    );
  }

  Future<void> _switchExecutionBackend(String backend) async {
    if (_executionBackend == backend && !_hasError) return;
    setState(() {
      _executionBackend = backend;
      _shizukuStatus = ShizukuStatus.checking;
    });
    try {
      final prefs = await SharedPreferences.getInstance();
      await prefs.setString('execution_backend', backend);
      await platform.invokeMethod('setExecutionBackend', {'backend': backend});
      await _checkShizuku();
      if (_shizukuRunning) {
        _getCurrentRearDpi();
        _getCurrentRotation();
      }
    } catch (e) {
      print('Failed switching backend: $e');
    }
  }

  Future<void> _autoConnectLocalAdb({int? port}) async {
    if (_adbBusy) return;
    setState(() => _adbBusy = true);
    try {
      final bool ok = await platform.invokeMethod('connectLocalAdb', {
        if (port != null) 'port': port,
      }) ?? false;
      await _checkShizuku();
      if (ok) {
        _getCurrentRearDpi();
        _getCurrentRotation();
      }
      if (mounted) {
        ScaffoldMessenger.of(context).showSnackBar(
          SnackBar(
            content: Text(
              AppLocalizations.of(context).translate(
                ok ? 'toast_adb_connected' : 'toast_adb_connect_failed',
              ),
            ),
          ),
        );
      }
    } catch (e) {
      if (mounted) {
        ScaffoldMessenger.of(context).showSnackBar(
          SnackBar(content: Text('$e')),
        );
      }
    } finally {
      if (mounted) {
        setState(() => _adbBusy = false);
      }
    }
  }

  Future<void> _showAutoActivateChecklistDialog() async {
    await showDialog(
      context: context,
      builder: (ctx) {
        return AlertDialog(
          title: Text(
            AppLocalizations.of(context).translate('auto_activate_dialog_title'),
            style: const TextStyle(fontSize: 17, fontWeight: FontWeight.bold),
          ),
          content: SingleChildScrollView(
            child: Column(
              crossAxisAlignment: CrossAxisAlignment.start,
              mainAxisSize: MainAxisSize.min,
              children: [
                Text(
                  AppLocalizations.of(context).translate('auto_activate_prereq_intro'),
                  style: const TextStyle(fontSize: 13, fontWeight: FontWeight.w600),
                ),
                const SizedBox(height: 10),
                Text(
                  AppLocalizations.of(context).translate('auto_activate_prereq_1'),
                  style: const TextStyle(fontSize: 13, height: 1.4),
                ),
                Text(
                  AppLocalizations.of(context).translate('auto_activate_prereq_2'),
                  style: const TextStyle(fontSize: 13, height: 1.4),
                ),
                Text(
                  AppLocalizations.of(context).translate('auto_activate_prereq_3'),
                  style: const TextStyle(fontSize: 13, height: 1.4),
                ),
                Text(
                  AppLocalizations.of(context).translate('auto_activate_prereq_4'),
                  style: const TextStyle(fontSize: 13, height: 1.4),
                ),
                const SizedBox(height: 12),
                Text(
                  AppLocalizations.of(context).translate('auto_activate_how_it_works'),
                  style: const TextStyle(fontSize: 12, color: Colors.black54, height: 1.35),
                ),
              ],
            ),
          ),
          actions: [
            TextButton(
              onPressed: () {
                platform.invokeMethod('openDeveloperOptions');
              },
              child: Text(AppLocalizations.of(context).translate('btn_open_dev_options')),
            ),
            ElevatedButton.icon(
              icon: const Icon(Icons.auto_fix_high_rounded, size: 18),
              label: Text(AppLocalizations.of(context).translate('btn_start_auto_activate')),
              onPressed: () async {
                Navigator.of(ctx).pop();
                try {
                  final String? action = await platform.invokeMethod('startLocalAdbAutoActivation');
                  if (mounted && action == 'OPENED_ACCESSIBILITY_SETTINGS') {
                    ScaffoldMessenger.of(context).showSnackBar(
                      SnackBar(
                        duration: const Duration(seconds: 6),
                        content: Text(
                          AppLocalizations.of(context).translate('toast_auto_activate_a11y'),
                        ),
                      ),
                    );
                  }
                } catch (e) {
                  if (mounted) {
                    ScaffoldMessenger.of(context).showSnackBar(
                      SnackBar(content: Text('$e')),
                    );
                  }
                }
              },
            ),
          ],
        );
      },
    );
  }

  Future<void> _startNotificationPairing() async {
    try {
      await platform.invokeMethod('startLocalAdbNotificationPairing');
      if (mounted) {
        ScaffoldMessenger.of(context).showSnackBar(
          SnackBar(
            duration: const Duration(seconds: 5),
            content: Text(
              AppLocalizations.of(context).translate('notif_pair_started_toast'),
            ),
          ),
        );
      }
      await platform.invokeMethod('openWirelessDebuggingSettings');
    } catch (e) {
      if (mounted) {
        ScaffoldMessenger.of(context).showSnackBar(
          SnackBar(content: Text('$e')),
        );
      }
    }
  }

  Future<void> _showManualAdbDialog() async {
    final pairingPortCtrl = TextEditingController();
    final pairingCodeCtrl = TextEditingController();
    final connectPortCtrl = TextEditingController();

    await showDialog(
      context: context,
      builder: (ctx) {
        return AlertDialog(
          title: Text(
            AppLocalizations.of(context).translate('manual_pair_dialog_title'),
            style: const TextStyle(fontSize: 17, fontWeight: FontWeight.bold),
          ),
          content: SingleChildScrollView(
            child: Column(
              mainAxisSize: MainAxisSize.min,
              children: [
                TextField(
                  controller: pairingPortCtrl,
                  keyboardType: TextInputType.number,
                  decoration: InputDecoration(
                    labelText: AppLocalizations.of(context).translate('pairing_port_label'),
                  ),
                ),
                const SizedBox(height: 10),
                TextField(
                  controller: pairingCodeCtrl,
                  keyboardType: TextInputType.number,
                  decoration: InputDecoration(
                    labelText: AppLocalizations.of(context).translate('pairing_code_label'),
                  ),
                ),
                const SizedBox(height: 12),
                SizedBox(
                  width: double.infinity,
                  child: ElevatedButton.icon(
                    icon: const Icon(Icons.link, size: 18),
                    label: Text(AppLocalizations.of(context).translate('btn_pair_now')),
                    onPressed: () async {
                      final pPort = int.tryParse(pairingPortCtrl.text.trim()) ?? 0;
                      final pCode = pairingCodeCtrl.text.trim();
                      if (pPort <= 0 || pCode.isEmpty) return;
                      Navigator.of(ctx).pop();
                      setState(() => _adbBusy = true);
                      try {
                        final bool ok = await platform.invokeMethod('pairLocalAdb', {
                          'port': pPort,
                          'code': pCode,
                        }) ?? false;
                        await _checkShizuku();
                        if (ok) {
                          _getCurrentRearDpi();
                          _getCurrentRotation();
                        }
                        if (mounted) {
                          ScaffoldMessenger.of(context).showSnackBar(
                            SnackBar(
                              content: Text(
                                AppLocalizations.of(context).translate(
                                  ok ? 'toast_adb_connected' : 'toast_adb_connect_failed',
                                ),
                              ),
                            ),
                          );
                        }
                      } catch (e) {
                        if (mounted) {
                          ScaffoldMessenger.of(context).showSnackBar(
                            SnackBar(content: Text('$e')),
                          );
                        }
                      } finally {
                        if (mounted) setState(() => _adbBusy = false);
                      }
                    },
                  ),
                ),
                const Divider(height: 24),
                TextField(
                  controller: connectPortCtrl,
                  keyboardType: TextInputType.number,
                  decoration: InputDecoration(
                    labelText: AppLocalizations.of(context).translate('connect_port_label'),
                  ),
                ),
                const SizedBox(height: 12),
                SizedBox(
                  width: double.infinity,
                  child: OutlinedButton.icon(
                    icon: const Icon(Icons.bolt, size: 18),
                    label: Text(AppLocalizations.of(context).translate('btn_connect_port')),
                    onPressed: () {
                      final cPort = int.tryParse(connectPortCtrl.text.trim());
                      Navigator.of(ctx).pop();
                      _autoConnectLocalAdb(port: cPort);
                    },
                  ),
                ),
              ],
            ),
          ),
          actions: [
            TextButton(
              onPressed: () => Navigator.of(ctx).pop(),
              child: Text(AppLocalizations.of(context).translate('cancel')),
            ),
          ],
        );
      },
    );
  }

  Widget _buildBackendOptionPill(String backendKey, String label, IconData icon) {
    final bool selected = _executionBackend == backendKey;
    return Expanded(
      child: GestureDetector(
        onTap: () => _switchExecutionBackend(backendKey),
        child: AnimatedContainer(
          duration: const Duration(milliseconds: 180),
          padding: const EdgeInsets.symmetric(vertical: 10, horizontal: 12),
          decoration: BoxDecoration(
            color: selected ? Colors.white.withOpacity(0.55) : Colors.black.withOpacity(0.06),
            borderRadius: BorderRadius.circular(12),
            border: Border.all(
              color: selected ? Colors.white : Colors.transparent,
              width: 1.2,
            ),
          ),
          child: Row(
            mainAxisAlignment: MainAxisAlignment.center,
            children: [
              Icon(
                icon,
                size: 18,
                color: selected ? Colors.black87 : Colors.black54,
              ),
              const SizedBox(width: 6),
              Flexible(
                child: Text(
                  label,
                  overflow: TextOverflow.ellipsis,
                  style: TextStyle(
                    fontSize: 13,
                    fontWeight: selected ? FontWeight.bold : FontWeight.w500,
                    color: selected ? Colors.black87 : Colors.black54,
                  ),
                ),
              ),
            ],
          ),
        ),
      ),
    );
  }

  String _getDisplayStatus(BuildContext context) {
    final bool isLocalAdb = _executionBackend == 'local_adb';
    switch (_shizukuStatus) {
      case ShizukuStatus.checking:
        return AppLocalizations.of(context).translate(
          isLocalAdb ? 'check_local_adb' : 'check_shizuku',
        );
      case ShizukuStatus.running:
        return AppLocalizations.of(context).translate(
          isLocalAdb ? 'local_adb_ready' : 'status_ready',
        );
      case ShizukuStatus.error:
        if (isLocalAdb) {
          if (_localAdbStateCode == 'WAITING_FOR_WIFI') {
            return AppLocalizations.of(context).translate('local_adb_waiting_wifi');
          }
          return AppLocalizations.of(context).translate('local_adb_not_connected');
        }
        return _customErrorTitle.isNotEmpty
            ? _customErrorTitle
            : AppLocalizations.of(context).translate('permission_required');
    }
  }

  @override
  Widget build(BuildContext context) {
    return Scaffold(
      extendBodyBehindAppBar: true,
      appBar: AppBar(
        backgroundColor: Colors.transparent,
        foregroundColor: Colors.white,
        elevation: 0,
        scrolledUnderElevation: 0,
        surfaceTintColor: Colors.transparent,
        shadowColor: Colors.transparent,
        title: const Text(
          'MRSS',
          style: TextStyle(fontWeight: FontWeight.bold),
        ),
        actions: [
          IconButton(
            icon: const Icon(Icons.restart_alt),
            onPressed: _restartApp,
            tooltip: '重启软件',
          ),
        ],
      ),
      body: Container(
        width: double.infinity,
        height: double.infinity,
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
        child: SafeArea(
          child: SingleChildScrollView(
            padding: const EdgeInsets.all(20),
            physics: const BouncingScrollPhysics(), // 始终允许滑动
            child: Column(
              crossAxisAlignment: CrossAxisAlignment.stretch,
              children: [
                // 整合后的状态和权限卡片（毛玻璃效果）
                CustomPaint(
                  painter: _SquircleBorderPainter(
                    radius: _SquircleRadii.large,
                    color: Colors.white.withOpacity(0.5),
                    strokeWidth: 1.5,
                  ),
                  child: ClipPath(
                    clipper: _SquircleClipper(
                      cornerRadius: _SquircleRadii.large,
                    ),
                    child: BackdropFilter(
                      filter: ImageFilter.blur(sigmaX: 0, sigmaY: 0),
                      child: Container(
                        decoration: BoxDecoration(
                          color: Colors.white.withOpacity(0.25),
                        ),
                        padding: const EdgeInsets.all(16),
                        child: Column(
                          children: [
                            // Dual-Backend Segmented Switcher (Shizuku vs Local ADB)
                            Row(
                              children: [
                                _buildBackendOptionPill(
                                  'shizuku',
                                  AppLocalizations.of(context).translate('backend_shizuku'),
                                  Icons.security_rounded,
                                ),
                                const SizedBox(width: 10),
                                _buildBackendOptionPill(
                                  'local_adb',
                                  AppLocalizations.of(context).translate('backend_local_adb'),
                                  Icons.terminal_rounded,
                                ),
                              ],
                            ),
                            const SizedBox(height: 14),
                            Row(
                              mainAxisAlignment: MainAxisAlignment.center,
                              children: [
                                if (_adbBusy || _shizukuStatus == ShizukuStatus.checking) ...[
                                  const SizedBox(
                                    width: 22,
                                    height: 22,
                                    child: CircularProgressIndicator(
                                      strokeWidth: 2.2,
                                      valueColor: AlwaysStoppedAnimation<Color>(Colors.black87),
                                    ),
                                  ),
                                ] else ...[
                                  Icon(
                                    _shizukuRunning
                                        ? Icons.check_circle
                                        : (_hasError
                                              ? Icons.error_outline
                                              : Icons.warning_rounded),
                                    size: 28,
                                    color: _shizukuRunning
                                        ? Colors.green
                                        : (_hasError
                                              ? Colors.red
                                              : Colors.orange),
                                  ),
                                ],
                                const SizedBox(width: 10),
                                Flexible(
                                  child: Text(
                                    _getDisplayStatus(context),
                                    style: const TextStyle(
                                      fontSize: 16,
                                      color: Colors.black87,
                                      fontWeight: FontWeight.w600,
                                    ),
                                    textAlign: TextAlign.center,
                                  ),
                                ),
                              ],
                            ),
                            if (_executionBackend == 'local_adb' && !_shizukuRunning) ...[
                              const SizedBox(height: 8),
                              Text(
                                AppLocalizations.of(context).translate('local_adb_setup_guide'),
                                style: const TextStyle(
                                  fontSize: 12,
                                  color: Colors.black87,
                                  height: 1.35,
                                ),
                                textAlign: TextAlign.center,
                              ),
                            ],
                            if (_hasError && _errorDetail.isNotEmpty) ...[
                              const SizedBox(height: 8),
                              Text(
                                _errorDetail,
                                style: const TextStyle(
                                  fontSize: 12,
                                  color: Colors.black54,
                                  height: 1.3,
                                ),
                                textAlign: TextAlign.center,
                              ),
                            ],
                            if (_executionBackend == 'local_adb') ...[
                              const SizedBox(height: 12),
                              if (!_shizukuRunning) ...[
                                SizedBox(
                                  width: double.infinity,
                                  child: ElevatedButton.icon(
                                    onPressed: _adbBusy ? null : _showAutoActivateChecklistDialog,
                                    icon: const Icon(Icons.auto_fix_high_rounded, size: 18),
                                    label: Text(
                                      AppLocalizations.of(context).translate('btn_auto_activate'),
                                      style: const TextStyle(fontSize: 13, fontWeight: FontWeight.bold),
                                    ),
                                    style: ElevatedButton.styleFrom(
                                      backgroundColor: Colors.white.withOpacity(0.9),
                                      foregroundColor: Colors.black87,
                                      elevation: 0,
                                      padding: const EdgeInsets.symmetric(horizontal: 16, vertical: 11),
                                    ),
                                  ),
                                ),
                                const SizedBox(height: 8),
                              ],
                              Wrap(
                                spacing: 8,
                                runSpacing: 8,
                                alignment: WrapAlignment.center,
                                children: [
                                  ElevatedButton.icon(
                                    onPressed: _adbBusy ? null : _startNotificationPairing,
                                    icon: const Icon(Icons.notifications_active_outlined, size: 16),
                                    label: Text(
                                      AppLocalizations.of(context).translate('btn_notif_pair'),
                                      style: const TextStyle(fontSize: 12),
                                    ),
                                    style: ElevatedButton.styleFrom(
                                      backgroundColor: Colors.white.withOpacity(0.75),
                                      foregroundColor: Colors.black87,
                                      elevation: 0,
                                      padding: const EdgeInsets.symmetric(horizontal: 12, vertical: 8),
                                    ),
                                  ),
                                  ElevatedButton.icon(
                                    onPressed: _adbBusy ? null : () => _autoConnectLocalAdb(),
                                    icon: const Icon(Icons.flash_on_rounded, size: 16),
                                    label: Text(
                                      AppLocalizations.of(context).translate('btn_auto_connect'),
                                      style: const TextStyle(fontSize: 12),
                                    ),
                                    style: ElevatedButton.styleFrom(
                                      backgroundColor: Colors.white.withOpacity(0.75),
                                      foregroundColor: Colors.black87,
                                      elevation: 0,
                                      padding: const EdgeInsets.symmetric(horizontal: 12, vertical: 8),
                                    ),
                                  ),
                                  OutlinedButton.icon(
                                    onPressed: _adbBusy ? null : _showManualAdbDialog,
                                    icon: const Icon(Icons.tune_rounded, size: 16),
                                    label: Text(
                                      AppLocalizations.of(context).translate('btn_manual_pair'),
                                      style: const TextStyle(fontSize: 12),
                                    ),
                                    style: OutlinedButton.styleFrom(
                                      foregroundColor: Colors.black87,
                                      side: const BorderSide(color: Colors.black26),
                                      padding: const EdgeInsets.symmetric(horizontal: 12, vertical: 8),
                                    ),
                                  ),
                                  OutlinedButton.icon(
                                    onPressed: () => platform.invokeMethod('openWirelessDebuggingSettings'),
                                    icon: const Icon(Icons.developer_mode_rounded, size: 16),
                                    label: Text(
                                      AppLocalizations.of(context).translate('btn_wireless_settings'),
                                      style: const TextStyle(fontSize: 12),
                                    ),
                                    style: OutlinedButton.styleFrom(
                                      foregroundColor: Colors.black87,
                                      side: const BorderSide(color: Colors.black26),
                                      padding: const EdgeInsets.symmetric(horizontal: 12, vertical: 8),
                                    ),
                                  ),
                                ],
                              ),
                            ],
                          ],
                        ),
                      ),
                    ),
                  ),
                ),

                const SizedBox(height: 20),

                // V15: 背屏DPI调整卡片
                Stack(
                  children: [
                    CustomPaint(
                      painter: _SquircleBorderPainter(
                        radius: _SquircleRadii.large,
                        color: Colors.white.withOpacity(0.5),
                        strokeWidth: 1.5,
                      ),
                      child: ClipPath(
                        clipper: _SquircleClipper(
                          cornerRadius: _SquircleRadii.large,
                        ),
                        child: BackdropFilter(
                          filter: ImageFilter.blur(sigmaX: 0, sigmaY: 0),
                          child: Container(
                            decoration: BoxDecoration(
                              color: Colors.white.withOpacity(0.25),
                            ),
                            padding: const EdgeInsets.all(20),
                            child: Column(
                              crossAxisAlignment: CrossAxisAlignment.start,
                              children: [
                                Row(
                                  children: [
                                    Text(
                                      AppLocalizations.of(
                                        context,
                                      ).translate('dpi_settings'),
                                      style: Theme.of(context)
                                          .textTheme
                                          .titleMedium
                                          ?.copyWith(
                                            color: Colors.black87,
                                            fontWeight: FontWeight.bold,
                                          ),
                                    ),
                                    if (_dpiLoading) ...[
                                      const SizedBox(width: 12),
                                      const SizedBox(
                                        width: 16,
                                        height: 16,
                                        child: CircularProgressIndicator(
                                          strokeWidth: 2,
                                          valueColor:
                                              AlwaysStoppedAnimation<Color>(
                                                Colors.black54,
                                              ),
                                        ),
                                      ),
                                    ],
                                  ],
                                ),
                                const SizedBox(height: 8),
                                Text(
                                  _dpiLoading
                                      ? AppLocalizations.of(
                                          context,
                                        ).translate('checking_dpi')
                                      : '${AppLocalizations.of(context).translate('current_dpi').replaceAll('%d', _currentRearDpi.toString())}  ${AppLocalizations.of(context).translate('recommended_range')}',
                                  style: const TextStyle(
                                    color: Colors.black54,
                                    fontSize: 14,
                                  ),
                                ),
                                const SizedBox(height: 16),
                                Row(
                                  children: [
                                    Expanded(
                                      child: TextField(
                                        controller: _dpiController,
                                        focusNode: _dpiFocusNode,
                                        enabled: !_dpiLoading && !_isLoading,
                                        keyboardType: TextInputType.number,
                                        style: const TextStyle(
                                          color: Colors.black87,
                                        ),
                                        decoration: InputDecoration(
                                          labelText: AppLocalizations.of(
                                            context,
                                          ).translate('new_dpi'),
                                          labelStyle: const TextStyle(
                                            color: Colors.black54,
                                          ),
                                          hintText: AppLocalizations.of(
                                            context,
                                          ).translate('input_number'),
                                          hintStyle: const TextStyle(
                                            color: Colors.black38,
                                          ),
                                          border: const OutlineInputBorder(
                                            borderRadius: BorderRadius.all(
                                              Radius.circular(
                                                _SquircleRadii.small,
                                              ),
                                            ),
                                            borderSide: BorderSide(
                                              color: Colors.black26,
                                            ),
                                          ),
                                          enabledBorder:
                                              const OutlineInputBorder(
                                                borderRadius: BorderRadius.all(
                                                  Radius.circular(
                                                    _SquircleRadii.small,
                                                  ),
                                                ),
                                                borderSide: BorderSide(
                                                  color: Colors.black26,
                                                ),
                                              ),
                                          focusedBorder:
                                              const OutlineInputBorder(
                                                borderRadius: BorderRadius.all(
                                                  Radius.circular(
                                                    _SquircleRadii.small,
                                                  ),
                                                ),
                                                borderSide: BorderSide(
                                                  color: Colors.black54,
                                                  width: 2,
                                                ),
                                              ),
                                        ),
                                      ),
                                    ),
                                    const SizedBox(width: 12),
                                    ClipPath(
                                      clipper: _SquircleClipper(
                                        cornerRadius: _SquircleRadii.small,
                                      ),
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
                                        child: ElevatedButton(
                                          onPressed: (_isLoading || _dpiLoading)
                                              ? null
                                              : () {
                                                  final dpi = int.tryParse(
                                                    _dpiController.text,
                                                  );
                                                  if (dpi != null && dpi > 0) {
                                                    _setRearDpi(dpi);
                                                  } else {
                                                    ScaffoldMessenger.of(
                                                      context,
                                                    ).showSnackBar(
                                                      SnackBar(
                                                        content: Text(
                                                          AppLocalizations.of(
                                                            context,
                                                          ).translate(
                                                            'input_number',
                                                          ), // Reusing input_number or need invalid_input
                                                        ),
                                                      ),
                                                    );
                                                  }
                                                },
                                          style: ElevatedButton.styleFrom(
                                            backgroundColor: Colors.transparent,
                                            foregroundColor: Colors.white,
                                            shadowColor: Colors.transparent,
                                            padding: const EdgeInsets.symmetric(
                                              horizontal: 20,
                                              vertical: 16,
                                            ),
                                            shape: RoundedRectangleBorder(
                                              borderRadius:
                                                  BorderRadius.circular(
                                                    _SquircleRadii.small,
                                                  ),
                                            ),
                                          ),
                                          child: Text(
                                            AppLocalizations.of(
                                              context,
                                            ).translate('set_dpi'),
                                          ),
                                        ),
                                      ),
                                    ),
                                  ],
                                ),
                                const SizedBox(height: 12),
                                SizedBox(
                                  width: double.infinity,
                                  child: CustomPaint(
                                    painter: _SquircleBorderPainter(
                                      radius: _SquircleRadii.small,
                                      color: Colors.black26,
                                      strokeWidth: 1,
                                    ),
                                    child: ClipPath(
                                      clipper: _SquircleClipper(
                                        cornerRadius: _SquircleRadii.small,
                                      ),
                                      child: Material(
                                        color: Colors.transparent,
                                        child: InkWell(
                                          onTap: (_isLoading || _dpiLoading)
                                              ? null
                                              : _resetRearDpi,
                                          child: Padding(
                                            padding: EdgeInsets.symmetric(
                                              vertical: 12,
                                            ),
                                            child: Row(
                                              mainAxisAlignment:
                                                  MainAxisAlignment.center,
                                              children: [
                                                Icon(
                                                  Icons.restore,
                                                  color: Colors.black87,
                                                  size: 20,
                                                ),
                                                SizedBox(width: 8),
                                                Text(
                                                  AppLocalizations.of(
                                                    context,
                                                  ).translate(
                                                    'restore_default_dpi',
                                                  ),
                                                  style: TextStyle(
                                                    color: Colors.black87,
                                                    fontSize: 14,
                                                  ),
                                                ),
                                              ],
                                            ),
                                          ),
                                        ),
                                      ),
                                    ),
                                  ),
                                ),

                                const SizedBox(height: 16),
                                const Divider(color: Colors.black26, height: 1),
                                const SizedBox(height: 16),

                                // V2.1: 旋转控制
                                Row(
                                  children: [
                                    Text(
                                      AppLocalizations.of(
                                        context,
                                      ).translate('rotation_title'),
                                      style: TextStyle(
                                        fontSize: 13,
                                        color: Colors.black87,
                                        fontWeight: FontWeight.w500,
                                      ),
                                    ),
                                    const Spacer(),
                                    _buildRotationButton('0°', 0),
                                    const SizedBox(width: 6),
                                    _buildRotationButton('90°', 1),
                                    const SizedBox(width: 6),
                                    _buildRotationButton('180°', 2),
                                    const SizedBox(width: 6),
                                    _buildRotationButton('270°', 3),
                                  ],
                                ),
                              ],
                            ),
                          ),
                        ),
                      ),
                    ),
                  ],
                ),

                const SizedBox(height: 20),

                // V2.2: 背屏遮盖检测卡片（独立）
                Stack(
                  children: [
                    CustomPaint(
                      painter: _SquircleBorderPainter(
                        radius: _SquircleRadii.large,
                        color: Colors.white.withOpacity(0.5),
                        strokeWidth: 1.5,
                      ),
                      child: ClipPath(
                        clipper: _SquircleClipper(
                          cornerRadius: _SquircleRadii.large,
                        ),
                        child: BackdropFilter(
                          filter: ImageFilter.blur(sigmaX: 0, sigmaY: 0),
                          child: Container(
                            padding: const EdgeInsets.symmetric(
                              horizontal: 20,
                              vertical: 16,
                            ),
                            decoration: BoxDecoration(
                              color: Colors.white.withOpacity(0.25),
                            ),
                            child: Row(
                              children: [
                                Text(
                                  AppLocalizations.of(
                                    context,
                                  ).translate('rear_cover_detection_title'),
                                  style: TextStyle(
                                    fontSize: 16,
                                    fontWeight: FontWeight.bold,
                                    color: Colors.black87,
                                  ),
                                ),
                                const Spacer(),
                                _GradientToggle(
                                  value: _proximitySensorEnabled,
                                  onChanged: _toggleProximitySensor,
                                ),
                              ],
                            ),
                          ),
                        ),
                      ),
                    ),
                  ],
                ),

                const SizedBox(height: 20),

                // V2.5: 背屏常亮卡片
                CustomPaint(
                  painter: _SquircleBorderPainter(
                    radius: _SquircleRadii.large,
                    color: Colors.white.withOpacity(0.5),
                    strokeWidth: 1.5,
                  ),
                  child: ClipPath(
                    clipper: _SquircleClipper(
                      cornerRadius: _SquircleRadii.large,
                    ),
                    child: BackdropFilter(
                      filter: ImageFilter.blur(sigmaX: 0, sigmaY: 0),
                      child: Container(
                        padding: const EdgeInsets.symmetric(
                          horizontal: 20,
                          vertical: 16,
                        ),
                        decoration: BoxDecoration(
                          color: Colors.white.withOpacity(0.25),
                        ),
                        child: Column(
                          crossAxisAlignment: CrossAxisAlignment.start,
                          children: [
                            // 背屏常亮开关
                            Row(
                              children: [
                                Text(
                                  AppLocalizations.of(
                                    context,
                                  ).translate('rear_screen_always_on_title'),
                                  style: TextStyle(
                                    fontSize: 16,
                                    fontWeight: FontWeight.bold,
                                    color: Colors.black87,
                                  ),
                                ),
                                const Spacer(),
                                _GradientToggle(
                                  value: _keepScreenOnEnabled,
                                  onChanged: _toggleKeepScreenOn,
                                ),
                              ],
                            ),
                            const SizedBox(height: 12),
                            const Divider(color: Colors.black26, height: 1),
                            const SizedBox(height: 12),
                            // 未投放应用时常亮开关
                            Row(
                              children: [
                                Text(
                                  AppLocalizations.of(
                                    context,
                                  ).translate('always_wake_up_title'),
                                  style: TextStyle(
                                    fontSize: 16,
                                    fontWeight: FontWeight.bold,
                                    color: Colors.black87,
                                  ),
                                ),
                                const Spacer(),
                                _GradientToggle(
                                  value: _alwaysWakeUpEnabled,
                                  onChanged: _toggleAlwaysWakeUp,
                                ),
                              ],
                            ),
                            if (_alwaysWakeUpEnabled) ...[
                              const SizedBox(height: 12),
                              Container(
                                padding: const EdgeInsets.all(12),
                                decoration: BoxDecoration(
                                  color: Colors.orange.withOpacity(0.2),
                                  borderRadius: BorderRadius.circular(
                                    _SquircleRadii.small,
                                  ),
                                  border: Border.all(
                                    color: Colors.orange.withOpacity(0.4),
                                    width: 1,
                                  ),
                                ),
                                child: Row(
                                  children: [
                                    Expanded(
                                      child: Text(
                                        AppLocalizations.of(
                                          context,
                                        ).translate('warning_burn_in'),
                                        style: TextStyle(
                                          fontSize: 12,
                                          color: Colors.black87,
                                        ),
                                      ),
                                    ),
                                  ],
                                ),
                              ),
                            ],
                          ],
                        ),
                      ),
                    ),
                  ),
                ),

                const SizedBox(height: 20),

                // V2.3: 充电动画卡片（独立）
                CustomPaint(
                  painter: _SquircleBorderPainter(
                    radius: _SquircleRadii.large,
                    color: Colors.white.withOpacity(0.5),
                    strokeWidth: 1.5,
                  ),
                  child: ClipPath(
                    clipper: _SquircleClipper(
                      cornerRadius: _SquircleRadii.large,
                    ),
                    child: BackdropFilter(
                      filter: ImageFilter.blur(sigmaX: 0, sigmaY: 0),
                      child: Container(
                        padding: const EdgeInsets.symmetric(
                          horizontal: 20,
                          vertical: 16,
                        ),
                        decoration: BoxDecoration(
                          color: Colors.white.withOpacity(0.25),
                        ),
                        child: Column(
                          crossAxisAlignment: CrossAxisAlignment.start,
                          children: [
                            // 充电动画开关
                            Row(
                              children: [
                                Text(
                                  AppLocalizations.of(
                                    context,
                                  ).translate('charging_animation_title'),
                                  style: TextStyle(
                                    fontSize: 16,
                                    fontWeight: FontWeight.bold,
                                    color: Colors.black87,
                                  ),
                                ),
                                const Spacer(),
                                _GradientToggle(
                                  value: _chargingAnimationEnabled,
                                  onChanged: _toggleChargingAnimation,
                                ),
                              ],
                            ),
                            const SizedBox(height: 12),
                            const Divider(color: Colors.black26, height: 1),
                            const SizedBox(height: 12),
                            // 充电动画常亮开关
                            Row(
                              children: [
                                Text(
                                  AppLocalizations.of(
                                    context,
                                  ).translate('charging_always_on_title'),
                                  style: TextStyle(
                                    fontSize: 16,
                                    fontWeight: FontWeight.bold,
                                    color: Colors.black87,
                                  ),
                                ),
                                const Spacer(),
                                _GradientToggle(
                                  value: _chargingAlwaysOnEnabled,
                                  onChanged: _toggleChargingAlwaysOn,
                                ),
                              ],
                            ),
                            if (_chargingAlwaysOnEnabled) ...[
                              const SizedBox(height: 12),
                              Container(
                                padding: const EdgeInsets.all(12),
                                decoration: BoxDecoration(
                                  color: Colors.orange.withOpacity(0.2),
                                  borderRadius: BorderRadius.circular(
                                    _SquircleRadii.small,
                                  ),
                                  border: Border.all(
                                    color: Colors.orange.withOpacity(0.4),
                                    width: 1,
                                  ),
                                ),
                                child: Row(
                                  children: [
                                    Expanded(
                                      child: Text(
                                        AppLocalizations.of(
                                          context,
                                        ).translate('warning_burn_in'),
                                        style: TextStyle(
                                          fontSize: 12,
                                          color: Colors.black87,
                                        ),
                                      ),
                                    ),
                                  ],
                                ),
                              ),
                            ],
                          ],
                        ),
                      ),
                    ),
                  ),
                ),

                const SizedBox(height: 20),

                // V2.4: 通知功能卡片
                CustomPaint(
                  painter: _SquircleBorderPainter(
                    radius: _SquircleRadii.large,
                    color: Colors.white.withOpacity(0.5),
                    strokeWidth: 1.5,
                  ),
                  child: ClipPath(
                    clipper: _SquircleClipper(
                      cornerRadius: _SquircleRadii.large,
                    ),
                    child: BackdropFilter(
                      filter: ImageFilter.blur(sigmaX: 0, sigmaY: 0),
                      child: Container(
                        padding: const EdgeInsets.symmetric(
                          horizontal: 20,
                          vertical: 16,
                        ),
                        decoration: BoxDecoration(
                          color: Colors.white.withOpacity(0.25),
                        ),
                        child: Column(
                          crossAxisAlignment: CrossAxisAlignment.start,
                          children: [
                            // 标题行
                            Row(
                              children: [
                                Text(
                                  AppLocalizations.of(
                                    context,
                                  ).translate('notification_service_title'),
                                  style: TextStyle(
                                    fontSize: 16,
                                    fontWeight: FontWeight.bold,
                                    color: Colors.black87,
                                  ),
                                ),
                                const Spacer(),
                                // 三条杠按钮（选择应用）
                                IconButton(
                                  icon: const Icon(Icons.menu, size: 24),
                                  color: Colors.black87,
                                  onPressed: _openAppSelectionPage,
                                  tooltip: AppLocalizations.of(
                                    context,
                                  ).translate('select_apps'),
                                  padding: EdgeInsets.zero,
                                  constraints: const BoxConstraints(),
                                ),
                                const SizedBox(width: 8),
                                _GradientToggle(
                                  value: _notificationEnabled,
                                  onChanged: _toggleNotificationService,
                                ),
                              ],
                            ),
                          ],
                        ),
                      ),
                    ),
                  ),
                ),

                const SizedBox(height: 20),

                // V3.6: Smart Media Rear Controller Card
                if (_notificationEnabled) ...[
                  CustomPaint(
                    painter: _SquircleBorderPainter(
                      radius: _SquircleRadii.large,
                      color: Colors.white.withOpacity(0.5),
                      strokeWidth: 1.5,
                    ),
                    child: ClipPath(
                      clipper: _SquircleClipper(
                        cornerRadius: _SquircleRadii.large,
                      ),
                      child: BackdropFilter(
                        filter: ImageFilter.blur(sigmaX: 0, sigmaY: 0),
                        child: Container(
                          padding: const EdgeInsets.symmetric(
                            horizontal: 20,
                            vertical: 16,
                          ),
                          decoration: BoxDecoration(
                            color: Colors.white.withOpacity(0.25),
                          ),
                          child: Column(
                            crossAxisAlignment: CrossAxisAlignment.start,
                            children: [
                              Row(
                                children: [
                                  Expanded(
                                    child: Column(
                                      crossAxisAlignment: CrossAxisAlignment.start,
                                      children: [
                                        Text(
                                          AppLocalizations.of(context).translate('smart_media_title'),
                                          style: const TextStyle(
                                            fontSize: 16,
                                            fontWeight: FontWeight.bold,
                                            color: Colors.black87,
                                          ),
                                        ),
                                        const SizedBox(height: 2),
                                        Text(
                                          AppLocalizations.of(context).translate('smart_media_subtitle'),
                                          style: const TextStyle(
                                            fontSize: 12,
                                            color: Colors.black54,
                                          ),
                                        ),
                                      ],
                                    ),
                                  ),
                                  const SizedBox(width: 12),
                                  _GradientToggle(
                                    value: _smartMediaEnabled,
                                    onChanged: _toggleSmartMedia,
                                  ),
                                ],
                              ),
                              // V3.7: Rear Media Shade Customization (follows Rear Media Shade toggle)
                              AnimatedSize(
                                duration: const Duration(milliseconds: 240),
                                curve: Curves.easeOutCubic,
                                child: _smartMediaEnabled
                                    ? Column(
                                        crossAxisAlignment:
                                            CrossAxisAlignment.start,
                                        children: [
                                          const SizedBox(height: 14),
                                          Divider(
                                            height: 1,
                                            color: Colors.black
                                                .withValues(alpha: 0.08),
                                          ),
                                          const SizedBox(height: 14),
                                          // Album Art Blur customization (directly under Rear Media Shade)
                                          Row(
                                            children: [
                                              Expanded(
                                                child: Text(
                                                  AppLocalizations.of(context)
                                                      .translate(
                                                        'media_album_blur_title',
                                                      ),
                                                  style: const TextStyle(
                                                    fontSize: 14,
                                                    fontWeight: FontWeight.w600,
                                                    color: Colors.black87,
                                                  ),
                                                ),
                                              ),
                                              Container(
                                                padding:
                                                    const EdgeInsets.symmetric(
                                                      horizontal: 8,
                                                      vertical: 2,
                                                    ),
                                                decoration: BoxDecoration(
                                                  color: Colors.black
                                                      .withValues(alpha: 0.08),
                                                  borderRadius:
                                                      BorderRadius.circular(8),
                                                ),
                                                child: Text(
                                                  _mediaAlbumBlur <= 0.1
                                                      ? AppLocalizations.of(
                                                          context,
                                                        ).translate(
                                                          'media_album_blur_off',
                                                        )
                                                      : '${(_mediaAlbumBlur / 50.0 * 100).round()}%',
                                                  style: const TextStyle(
                                                    fontSize: 12,
                                                    fontWeight: FontWeight.bold,
                                                    color: Colors.black87,
                                                  ),
                                                ),
                                              ),
                                            ],
                                          ),
                                          const SizedBox(height: 10),
                                          _buildAlbumBlurPreview(),
                                          SliderTheme(
                                            data: SliderTheme.of(
                                              context,
                                            ).copyWith(
                                              activeTrackColor: const Color(
                                                0xFF6366F1,
                                              ),
                                              inactiveTrackColor: Colors.black
                                                  .withValues(alpha: 0.12),
                                              thumbColor: Colors.white,
                                              overlayColor: const Color(
                                                0x266366F1,
                                              ),
                                              trackHeight: 4,
                                            ),
                                            child: Slider(
                                              value: _mediaAlbumBlur,
                                              min: 0.0,
                                              max: 50.0,
                                              divisions: 20,
                                              label: _mediaAlbumBlur <= 0.1
                                                  ? AppLocalizations.of(
                                                      context,
                                                    ).translate(
                                                      'media_album_blur_off',
                                                    )
                                                  : '${(_mediaAlbumBlur / 50.0 * 100).round()}%',
                                              onChanged: (val) =>
                                                  _updateMediaAlbumBlur(
                                                    val,
                                                    syncNative: false,
                                                  ),
                                              onChangeEnd: (val) =>
                                                  _updateMediaAlbumBlur(
                                                    val,
                                                    syncNative: true,
                                                  ),
                                            ),
                                          ),
                                          const SizedBox(height: 6),
                                          Divider(
                                            height: 1,
                                            color: Colors.black
                                                .withValues(alpha: 0.08),
                                          ),
                                          const SizedBox(height: 14),
                                          // 1. Pill On/Off toggle
                                          Row(
                                            children: [
                                              Expanded(
                                                child: Text(
                                                  AppLocalizations.of(context)
                                                      .translate(
                                                        'media_pill_toggle_title',
                                                      ),
                                                  style: const TextStyle(
                                                    fontSize: 14,
                                                    fontWeight: FontWeight.w600,
                                                    color: Colors.black87,
                                                  ),
                                                ),
                                              ),
                                              _GradientToggle(
                                                value: _mediaPillEnabled,
                                                onChanged: (val) =>
                                                    _updateMediaPillSettings(
                                                      enabled: val,
                                                    ),
                                              ),
                                            ],
                                          ),
                                          const SizedBox(height: 12),
                                          IgnorePointer(
                                            ignoring: !_mediaPillEnabled,
                                            child: AnimatedOpacity(
                                              duration: const Duration(
                                                milliseconds: 180,
                                              ),
                                              opacity:
                                                  _mediaPillEnabled ? 1.0 : 0.42,
                                              child: Column(
                                                crossAxisAlignment:
                                                    CrossAxisAlignment.start,
                                                children: [
                                                  _buildMediaPillPreview(),
                                                  const SizedBox(height: 14),
                                                  // 2. Pill Size (x1.0 to x5.0, step 0.5)
                                                  Row(
                                                    children: [
                                                      Text(
                                                        AppLocalizations.of(
                                                          context,
                                                        ).translate(
                                                          'media_pill_size_title',
                                                        ),
                                                        style: const TextStyle(
                                                          fontSize: 13,
                                                          fontWeight:
                                                              FontWeight.w600,
                                                          color: Colors.black87,
                                                        ),
                                                      ),
                                                      const Spacer(),
                                                      Container(
                                                        padding:
                                                            const EdgeInsets.symmetric(
                                                              horizontal: 8,
                                                              vertical: 2,
                                                            ),
                                                        decoration:
                                                            BoxDecoration(
                                                              color: Colors.black
                                                                  .withValues(
                                                                    alpha: 0.08,
                                                                  ),
                                                              borderRadius:
                                                                  BorderRadius.circular(
                                                                    8,
                                                                  ),
                                                            ),
                                                        child: Text(
                                                          'x${_mediaPillScale.toStringAsFixed(1)}',
                                                          style: const TextStyle(
                                                            fontSize: 12,
                                                            fontWeight:
                                                                FontWeight.bold,
                                                            color:
                                                                Colors.black87,
                                                          ),
                                                        ),
                                                      ),
                                                    ],
                                                  ),
                                                  SliderTheme(
                                                    data: SliderTheme.of(
                                                      context,
                                                    ).copyWith(
                                                      activeTrackColor: Color(
                                                        0xFF000000 |
                                                            (_mediaPillColor &
                                                                0x00FFFFFF),
                                                      ),
                                                      inactiveTrackColor: Colors
                                                          .black
                                                          .withValues(
                                                            alpha: 0.12,
                                                          ),
                                                      thumbColor: Colors.white,
                                                      overlayColor: Color(
                                                        0x26000000 |
                                                            (_mediaPillColor &
                                                                0x00FFFFFF),
                                                      ),
                                                      trackHeight: 4,
                                                    ),
                                                    child: Slider(
                                                      value: _mediaPillScale,
                                                      min: 1.0,
                                                      max: 5.0,
                                                      divisions: 8,
                                                      label:
                                                          'x${_mediaPillScale.toStringAsFixed(1)}',
                                                      onChanged: (val) =>
                                                          _updateMediaPillSettings(
                                                            scale: val,
                                                            syncNative: false,
                                                          ),
                                                      onChangeEnd: (val) =>
                                                          _updateMediaPillSettings(
                                                            scale: val,
                                                            syncNative: true,
                                                          ),
                                                    ),
                                                  ),
                                                  const SizedBox(height: 6),
                                                  // 3. Overall Liquid Glass Color
                                                  Row(
                                                    children: [
                                                      Text(
                                                        AppLocalizations.of(
                                                          context,
                                                        ).translate(
                                                          'media_pill_color_title',
                                                        ),
                                                        style: const TextStyle(
                                                          fontSize: 13,
                                                          fontWeight:
                                                              FontWeight.w600,
                                                          color: Colors.black87,
                                                        ),
                                                      ),
                                                      const Spacer(),
                                                      Text(
                                                        '#${(_mediaPillColor & 0x00FFFFFF).toRadixString(16).padLeft(6, '0').toUpperCase()}',
                                                        style: const TextStyle(
                                                          fontSize: 11,
                                                          fontFamily:
                                                              'monospace',
                                                          color: Colors.black54,
                                                        ),
                                                      ),
                                                    ],
                                                  ),
                                                  const SizedBox(height: 10),
                                                  Wrap(
                                                    spacing: 10,
                                                    runSpacing: 10,
                                                    children:
                                                        _mediaPillColorPresets
                                                            .map((preset) {
                                                              final bool
                                                              selected =
                                                                  (_mediaPillColor &
                                                                      0x00FFFFFF) ==
                                                                  (preset &
                                                                      0x00FFFFFF);
                                                              return GestureDetector(
                                                                onTap: () =>
                                                                    _updateMediaPillSettings(
                                                                      color:
                                                                          preset,
                                                                    ),
                                                                child: AnimatedContainer(
                                                                  duration:
                                                                      const Duration(
                                                                        milliseconds:
                                                                            140,
                                                                      ),
                                                                  width: 28,
                                                                  height: 28,
                                                                  decoration: BoxDecoration(
                                                                    shape:
                                                                        BoxShape
                                                                            .circle,
                                                                    color: Color(
                                                                      preset,
                                                                    ),
                                                                    border: Border.all(
                                                                      color: selected
                                                                          ? Colors
                                                                              .black87
                                                                          : Colors
                                                                              .white
                                                                              .withValues(
                                                                                alpha:
                                                                                    0.85,
                                                                              ),
                                                                      width:
                                                                          selected
                                                                          ? 2.5
                                                                          : 1.5,
                                                                    ),
                                                                    boxShadow: [
                                                                      BoxShadow(
                                                                        color: Color(
                                                                          preset,
                                                                        ).withValues(
                                                                          alpha:
                                                                              0.35,
                                                                        ),
                                                                        blurRadius:
                                                                            selected
                                                                            ? 8
                                                                            : 3,
                                                                        spreadRadius:
                                                                            selected
                                                                            ? 1
                                                                            : 0,
                                                                      ),
                                                                    ],
                                                                  ),
                                                                  child: selected
                                                                      ? Icon(
                                                                          Icons
                                                                              .check,
                                                                          size:
                                                                              15,
                                                                          color:
                                                                              preset ==
                                                                                  0xFFE2E8F0
                                                                              ? Colors
                                                                                  .black87
                                                                              : Colors
                                                                                  .white,
                                                                        )
                                                                      : null,
                                                                ),
                                                              );
                                                            })
                                                            .toList(),
                                                  ),
                                                  const SizedBox(height: 10),
                                                  // Continuous 360-degree Hue bar for custom glass tint
                                                  SizedBox(
                                                    height: 26,
                                                    child: Stack(
                                                      alignment:
                                                          Alignment.center,
                                                      children: [
                                                        Container(
                                                          height: 8,
                                                          margin:
                                                              const EdgeInsets.symmetric(
                                                                horizontal: 12,
                                                              ),
                                                          decoration: BoxDecoration(
                                                            borderRadius:
                                                                BorderRadius.circular(
                                                                  4,
                                                                ),
                                                            gradient: const LinearGradient(
                                                              colors: [
                                                                Color(
                                                                  0xFFFF0000,
                                                                ),
                                                                Color(
                                                                  0xFFFFFF00,
                                                                ),
                                                                Color(
                                                                  0xFF00FF00,
                                                                ),
                                                                Color(
                                                                  0xFF00FFFF,
                                                                ),
                                                                Color(
                                                                  0xFF0000FF,
                                                                ),
                                                                Color(
                                                                  0xFFFF00FF,
                                                                ),
                                                                Color(
                                                                  0xFFFF0000,
                                                                ),
                                                              ],
                                                            ),
                                                          ),
                                                        ),
                                                        SliderTheme(
                                                          data: SliderTheme.of(
                                                            context,
                                                          ).copyWith(
                                                            activeTrackColor:
                                                                Colors
                                                                    .transparent,
                                                            inactiveTrackColor:
                                                                Colors
                                                                    .transparent,
                                                            thumbColor: Color(
                                                              0xFF000000 |
                                                                  (_mediaPillColor &
                                                                      0x00FFFFFF),
                                                            ),
                                                            overlayColor: Colors
                                                                .black
                                                                .withValues(
                                                                  alpha: 0.08,
                                                                ),
                                                            thumbShape:
                                                                const RoundSliderThumbShape(
                                                                  enabledThumbRadius:
                                                                      9,
                                                                ),
                                                          ),
                                                          child: Slider(
                                                            value: HSVColor.fromColor(
                                                              Color(
                                                                0xFF000000 |
                                                                    (_mediaPillColor &
                                                                        0x00FFFFFF),
                                                              ),
                                                            ).hue,
                                                            min: 0.0,
                                                            max: 360.0,
                                                            onChanged: (hue) {
                                                              final c =
                                                                  HSVColor.fromAHSV(
                                                                    1.0,
                                                                    hue,
                                                                    0.90,
                                                                    0.88,
                                                                  ).toColor();
                                                              _updateMediaPillSettings(
                                                                color:
                                                                    c.toARGB32(),
                                                                syncNative:
                                                                    false,
                                                              );
                                                            },
                                                            onChangeEnd: (hue) {
                                                              final c =
                                                                  HSVColor.fromAHSV(
                                                                    1.0,
                                                                    hue,
                                                                    0.90,
                                                                    0.88,
                                                                  ).toColor();
                                                              _updateMediaPillSettings(
                                                                color:
                                                                    c.toARGB32(),
                                                                syncNative:
                                                                    true,
                                                              );
                                                            },
                                                          ),
                                                        ),
                                                      ],
                                                    ),
                                                  ),
                                                ],
                                              ),
                                            ),
                                          ),
                                        ],
                                      )
                                    : const SizedBox.shrink(),
                              ),
                            ],
                          ),
                        ),
                      ),
                    ),
                  ),

                  const SizedBox(height: 20),
                ],

                // 使用教程 - 可点击跳转到酷安帖子
                CustomPaint(
                  painter: _SquircleBorderPainter(
                    radius: _SquircleRadii.large,
                    color: Colors.white.withOpacity(0.5),
                    strokeWidth: 1.5,
                  ),
                  child: ClipPath(
                    clipper: _SquircleClipper(
                      cornerRadius: _SquircleRadii.large,
                    ),
                    child: BackdropFilter(
                      filter: ImageFilter.blur(sigmaX: 0, sigmaY: 0),
                      child: Material(
                        color: Colors.transparent,
                        child: InkWell(
                          onTap: () async {
                            // 跳转到腾讯文档使用教程
                            try {
                              await platform.invokeMethod('openTutorial');
                            } catch (e) {
                              print('打开教程失败: $e');
                              if (context.mounted) {
                                ScaffoldMessenger.of(context).showSnackBar(
                                  const SnackBar(content: Text('打开失败')),
                                );
                              }
                            }
                          },
                          splashColor: Colors.white.withOpacity(0.3),
                          highlightColor: Colors.white.withOpacity(0.2),
                          child: Container(
                            decoration: BoxDecoration(
                              color: Colors.white.withOpacity(0.25),
                            ),
                            padding: const EdgeInsets.symmetric(
                              vertical: 12,
                              horizontal: 16,
                            ),
                            child: Row(
                              mainAxisAlignment: MainAxisAlignment.center,
                              children: [
                                const Text(
                                  '📖',
                                  style: TextStyle(fontSize: 20),
                                ),
                                const SizedBox(width: 8),
                                Text(
                                  AppLocalizations.of(
                                    context,
                                  ).translate('tutorial'),
                                  style: TextStyle(
                                    color: Colors.black87,
                                    fontSize: 14,
                                    fontWeight: FontWeight.w500,
                                  ),
                                ),
                                const SizedBox(width: 4),
                                Icon(
                                  Icons.open_in_new,
                                  size: 16,
                                  color: Colors.black54,
                                ),
                              ],
                            ),
                          ),
                        ),
                      ),
                    ),
                  ),
                ),

                const SizedBox(height: 16),

                // 底部作者信息 - 可点击跳转到酷安
                CustomPaint(
                  painter: _SquircleBorderPainter(
                    radius: _SquircleRadii.large,
                    color: Colors.white.withOpacity(0.5),
                    strokeWidth: 1.5,
                  ),
                  child: ClipPath(
                    clipper: _SquircleClipper(
                      cornerRadius: _SquircleRadii.large,
                    ),
                    child: BackdropFilter(
                      filter: ImageFilter.blur(sigmaX: 0, sigmaY: 0),
                      child: Material(
                        color: Colors.transparent,
                        child: InkWell(
                          onTap: () async {
                            // 跳转到酷安个人主页
                            try {
                              await platform.invokeMethod('openCoolApkProfile');
                            } catch (e) {
                              print('打开酷安主页失败: $e');
                              if (context.mounted) {
                                ScaffoldMessenger.of(context).showSnackBar(
                                  SnackBar(
                                    content: Text(
                                      AppLocalizations.of(
                                        context,
                                      ).translate('install_coolapk'),
                                    ),
                                  ),
                                );
                              }
                            }
                          },
                          splashColor: Colors.white.withOpacity(0.3),
                          highlightColor: Colors.white.withOpacity(0.2),
                          child: Container(
                            decoration: BoxDecoration(
                              color: Colors.white.withOpacity(0.25),
                            ),
                            padding: const EdgeInsets.symmetric(
                              vertical: 12,
                              horizontal: 16,
                            ),
                            child: Row(
                              mainAxisAlignment: MainAxisAlignment.center,
                              children: [
                                const Text(
                                  '👨‍💻',
                                  style: TextStyle(fontSize: 20),
                                ),
                                const SizedBox(width: 6),
                                Image.asset(
                                  'assets/kuan.png',
                                  width: 24,
                                  height: 24,
                                  errorBuilder: (context, error, stackTrace) {
                                    return const Icon(
                                      Icons.person,
                                      size: 24,
                                      color: Colors.black87,
                                    );
                                  },
                                ),
                                const SizedBox(width: 8),
                                Text(
                                  AppLocalizations.of(
                                    context,
                                  ).translate('author_anti'),
                                  style: TextStyle(
                                    color: Colors.black87,
                                    fontSize: 14,
                                    fontWeight: FontWeight.w500,
                                  ),
                                ),
                                const SizedBox(width: 4),
                                Icon(
                                  Icons.open_in_new,
                                  size: 16,
                                  color: Colors.black54,
                                ),
                              ],
                            ),
                          ),
                        ),
                      ),
                    ),
                  ),
                ),

                const SizedBox(height: 16),

                // 团队信息 - 可点击跳转到酷安
                CustomPaint(
                  painter: _SquircleBorderPainter(
                    radius: _SquircleRadii.large,
                    color: Colors.white.withOpacity(0.5),
                    strokeWidth: 1.5,
                  ),
                  child: ClipPath(
                    clipper: _SquircleClipper(
                      cornerRadius: _SquircleRadii.large,
                    ),
                    child: BackdropFilter(
                      filter: ImageFilter.blur(sigmaX: 0, sigmaY: 0),
                      child: Material(
                        color: Colors.transparent,
                        child: InkWell(
                          onTap: () async {
                            // 跳转到汐木泽酷安主页
                            try {
                              await platform.invokeMethod(
                                'openCoolApkProfileXmz',
                              );
                            } catch (e) {
                              print('打开酷安主页失败: $e');
                              if (context.mounted) {
                                ScaffoldMessenger.of(context).showSnackBar(
                                  SnackBar(
                                    content: Text(
                                      AppLocalizations.of(
                                        context,
                                      ).translate('install_coolapk'),
                                    ),
                                  ),
                                );
                              }
                            }
                          },
                          splashColor: Colors.white.withOpacity(0.3),
                          highlightColor: Colors.white.withOpacity(0.2),
                          child: Container(
                            decoration: BoxDecoration(
                              color: Colors.white.withOpacity(0.25),
                            ),
                            padding: const EdgeInsets.symmetric(
                              vertical: 12,
                              horizontal: 16,
                            ),
                            child: Row(
                              mainAxisAlignment: MainAxisAlignment.center,
                              children: [
                                const Text(
                                  '🧪',
                                  style: TextStyle(fontSize: 20),
                                ),
                                const SizedBox(width: 6),
                                Image.asset(
                                  'assets/kuan.png',
                                  width: 24,
                                  height: 24,
                                  errorBuilder: (context, error, stackTrace) {
                                    return const Icon(
                                      Icons.person,
                                      size: 24,
                                      color: Colors.black87,
                                    );
                                  },
                                ),
                                const SizedBox(width: 8),
                                Text(
                                  AppLocalizations.of(
                                    context,
                                  ).translate('author_xmz'),
                                  style: TextStyle(
                                    color: Colors.black87,
                                    fontSize: 14,
                                    fontWeight: FontWeight.w500,
                                  ),
                                ),
                                const SizedBox(width: 4),
                                Icon(
                                  Icons.open_in_new,
                                  size: 16,
                                  color: Colors.black54,
                                ),
                              ],
                            ),
                          ),
                        ),
                      ),
                    ),
                  ),
                ),

                const SizedBox(height: 16),

                // 打赏和交流群 - 两列布局
                Row(
                  children: [
                    // 请作者喝咖啡
                    Expanded(
                      child: CustomPaint(
                        painter: _SquircleBorderPainter(
                          radius: _SquircleRadii.large,
                          color: Colors.white.withOpacity(0.5),
                          strokeWidth: 1.5,
                        ),
                        child: ClipPath(
                          clipper: _SquircleClipper(
                            cornerRadius: _SquircleRadii.large,
                          ),
                          child: BackdropFilter(
                            filter: ImageFilter.blur(sigmaX: 0, sigmaY: 0),
                            child: Material(
                              color: Colors.transparent,
                              child: InkWell(
                                onTap: () async {
                                  // 打开打赏页面
                                  try {
                                    await platform.invokeMethod(
                                      'openDonationPage',
                                    );
                                  } catch (e) {
                                    print('打开打赏页面失败: $e');
                                    if (context.mounted) {
                                      ScaffoldMessenger.of(
                                        context,
                                      ).showSnackBar(
                                        SnackBar(
                                          content: Text(
                                            AppLocalizations.of(
                                              context,
                                            ).translate('open_failed'),
                                          ),
                                        ),
                                      );
                                    }
                                  }
                                },
                                splashColor: Colors.white.withOpacity(0.3),
                                highlightColor: Colors.white.withOpacity(0.2),
                                child: Container(
                                  decoration: BoxDecoration(
                                    color: Colors.white.withOpacity(0.25),
                                  ),
                                  padding: const EdgeInsets.symmetric(
                                    vertical: 12,
                                    horizontal: 12,
                                  ),
                                  child: Column(
                                    mainAxisSize: MainAxisSize.min,
                                    children: [
                                      Text('☕', style: TextStyle(fontSize: 24)),
                                      SizedBox(height: 4),
                                      Text(
                                        AppLocalizations.of(
                                          context,
                                        ).translate('buy_coffee'),
                                        style: TextStyle(
                                          color: Colors.black87,
                                          fontSize: 12,
                                          fontWeight: FontWeight.w500,
                                        ),
                                        textAlign: TextAlign.center,
                                      ),
                                    ],
                                  ),
                                ),
                              ),
                            ),
                          ),
                        ),
                      ),
                    ),

                    const SizedBox(width: 16),

                    // MRSS交流群
                    Expanded(
                      child: CustomPaint(
                        painter: _SquircleBorderPainter(
                          radius: _SquircleRadii.large,
                          color: Colors.white.withOpacity(0.5),
                          strokeWidth: 1.5,
                        ),
                        child: ClipPath(
                          clipper: _SquircleClipper(
                            cornerRadius: _SquircleRadii.large,
                          ),
                          child: BackdropFilter(
                            filter: ImageFilter.blur(sigmaX: 0, sigmaY: 0),
                            child: Material(
                              color: Colors.transparent,
                              child: InkWell(
                                onTap: () async {
                                  // 打开交流群页面
                                  try {
                                    await platform.invokeMethod('openQQGroup');
                                  } catch (e) {
                                    print('打开交流群页面失败: $e');
                                    if (context.mounted) {
                                      ScaffoldMessenger.of(
                                        context,
                                      ).showSnackBar(
                                        SnackBar(
                                          content: Text(
                                            AppLocalizations.of(
                                              context,
                                            ).translate('open_failed'),
                                          ),
                                        ),
                                      );
                                    }
                                  }
                                },
                                splashColor: Colors.white.withOpacity(0.3),
                                highlightColor: Colors.white.withOpacity(0.2),
                                child: Container(
                                  decoration: BoxDecoration(
                                    color: Colors.white.withOpacity(0.25),
                                  ),
                                  padding: const EdgeInsets.symmetric(
                                    vertical: 12,
                                    horizontal: 12,
                                  ),
                                  child: Column(
                                    mainAxisSize: MainAxisSize.min,
                                    children: [
                                      Text(
                                        '💬',
                                        style: TextStyle(fontSize: 24),
                                      ),
                                      SizedBox(height: 4),
                                      Text(
                                        AppLocalizations.of(
                                          context,
                                        ).translate('qq_group_title'),
                                        style: TextStyle(
                                          color: Colors.black87,
                                          fontSize: 12,
                                          fontWeight: FontWeight.w500,
                                        ),
                                        textAlign: TextAlign.center,
                                      ),
                                    ],
                                  ),
                                ),
                              ),
                            ),
                          ),
                        ),
                      ),
                    ),
                  ],
                ),

                const SizedBox(height: 20),
              ],
            ),
          ),
        ),
      ),
    );
  }

  // V2.1: 构建旋转按钮（精确超椭圆，统一12px圆角）
  Widget _buildRotationButton(String label, int rotation) {
    bool isSelected = _currentRotation == rotation;

    return SizedBox(
      width: 50,
      height: 32,
      child: ClipPath(
        clipper: _SquircleClipper(cornerRadius: _SquircleRadii.small),
        child: Container(
          decoration: BoxDecoration(
            gradient: isSelected
                ? const LinearGradient(
                    begin: Alignment.topLeft,
                    end: Alignment.bottomRight,
                    colors: [
                      Color(0xFFFF9D88), // 珊瑚橙
                      Color(0xFFFFB5C5), // 粉红
                      Color(0xFFE0B5DC), // 紫色
                      Color(0xFFA8C5E5), // 蓝色
                    ],
                  )
                : null,
            color: isSelected ? null : Colors.white70,
          ),
          child: Material(
            color: Colors.transparent,
            child: InkWell(
              onTap: (_isLoading || _dpiLoading)
                  ? null
                  : () => _setRotation(rotation),
              child: Center(
                child: Text(
                  label,
                  style: TextStyle(
                    fontSize: 12,
                    color: isSelected ? Colors.white : Colors.black54,
                    fontWeight: isSelected
                        ? FontWeight.w500
                        : FontWeight.normal,
                  ),
                ),
              ),
            ),
          ),
        ),
      ),
    );
  }

  // V2.1: 获取当前旋转方向
  Future<void> _getCurrentRotation() async {
    if (_rotationQueryInFlight || !_shizukuRunning) return;
    _rotationQueryInFlight = true;
    try {
      final rotation = await platform.invokeMethod('getDisplayRotation', {
        'displayId': 1,
      });
      if (mounted && rotation != null && rotation >= 0) {
        setState(() {
          _currentRotation = rotation;
        });
      }
    } catch (e) {
      print('获取旋转方向失败: $e');
    } finally {
      _rotationQueryInFlight = false;
    }
  }

  // V2.1: 设置旋转方向
  Future<void> _setRotation(int rotation) async {
    print('[Flutter] 🔄 开始设置旋转: $rotation (${rotation * 90}°)');

    if (!_shizukuRunning) {
      print('[Flutter] ❌ Shizuku未运行');
      return;
    }
    if (_isLoading) {
      print('[Flutter] ⚠️ 正在加载中，跳过');
      return;
    }

    setState(() => _isLoading = true);

    try {
      // 确保TaskService连接
      print('[Flutter] 🔗 确保TaskService连接...');
      final connected = await platform.invokeMethod(
        'ensureTaskServiceConnected',
      );
      print('[Flutter] 🔗 TaskService连接状态: $connected');
      await Future.delayed(const Duration(milliseconds: 500));

      print(
        '[Flutter] 📡 调用setDisplayRotation: displayId=1, rotation=$rotation',
      );
      final result = await platform.invokeMethod('setDisplayRotation', {
        'displayId': 1,
        'rotation': rotation,
      });
      print('[Flutter] 📡 setDisplayRotation返回: $result');

      if (result == true) {
        setState(() => _currentRotation = rotation);
        print('[Flutter] ✅ 旋转成功: ${rotation * 90}°');
        if (mounted) {
          ScaffoldMessenger.of(context).showSnackBar(
            SnackBar(
              content: Text(
                '${AppLocalizations.of(context).translate('toast_rotation_set')} ${rotation * 90}°',
              ),
              duration: const Duration(seconds: 1),
            ),
          );
        }
      } else {
        print('[Flutter] ❌ 旋转失败: result=$result');
        if (mounted) {
          ScaffoldMessenger.of(context).showSnackBar(
            SnackBar(
              content: Text(
                AppLocalizations.of(context).translate('toast_rotation_failed'),
              ),
            ),
          );
        }
      }
    } catch (e) {
      print('[Flutter] ❌ 旋转异常: $e');
      if (mounted) {
        ScaffoldMessenger.of(context).showSnackBar(
          SnackBar(
            content: Text(
              '${AppLocalizations.of(context).translate('toast_error')} $e',
            ),
          ),
        );
      }
    } finally {
      setState(() => _isLoading = false);
      print('[Flutter] 🏁 旋转操作结束');
    }
  }
}

// 渐变开关，统一四段渐变样式，替代系统绿色Switch
class _GradientToggle extends StatefulWidget {
  final bool value;
  final ValueChanged<bool> onChanged;
  const _GradientToggle({required this.value, required this.onChanged});

  @override
  State<_GradientToggle> createState() => _GradientToggleState();
}

class _GradientToggleState extends State<_GradientToggle> {
  bool _pressed = false;

  @override
  Widget build(BuildContext context) {
    return Material(
      color: Colors.transparent,
      child: InkWell(
        onTap: () => widget.onChanged(!widget.value),
        onHighlightChanged: (h) => setState(() => _pressed = h),
        customBorder: _SquircleShapeBorder(cornerRadius: _SquircleRadii.tiny),
        splashColor: Colors.white.withOpacity(0.2),
        highlightColor: Colors.white.withOpacity(0.1),
        child: ClipPath(
          clipper: _SquircleClipper(cornerRadius: _SquircleRadii.tiny),
          child: SizedBox(
            width: 52,
            height: 30,
            child: Stack(
              children: [
                // Base background
                Container(color: Colors.white.withOpacity(0.25)),
                // Gradient overlay with fade
                AnimatedOpacity(
                  duration: const Duration(milliseconds: 220),
                  curve: Curves.easeOut,
                  opacity: widget.value ? 1.0 : 0.0,
                  child: Container(
                    decoration: const BoxDecoration(
                      gradient: LinearGradient(
                        begin: Alignment.topLeft,
                        end: Alignment.bottomRight,
                        colors: [
                          Color(0xFFFF9D88),
                          Color(0xFFFFB5C5),
                          Color(0xFFE0B5DC),
                          Color(0xFFA8C5E5),
                        ],
                      ),
                    ),
                  ),
                ),
                // Knob
                Padding(
                  padding: const EdgeInsets.symmetric(
                    horizontal: 4,
                    vertical: 4,
                  ),
                  child: AnimatedAlign(
                    duration: const Duration(milliseconds: 220),
                    curve: Curves.easeOut,
                    alignment: widget.value
                        ? Alignment.centerRight
                        : Alignment.centerLeft,
                    child: AnimatedScale(
                      duration: const Duration(milliseconds: 120),
                      scale: _pressed ? 0.95 : 1.0,
                      child: Container(
                        width: 22,
                        height: 22,
                        decoration: BoxDecoration(
                          color: Colors.white,
                          borderRadius: BorderRadius.circular(11),
                          boxShadow: [
                            BoxShadow(
                              color: Colors.black.withOpacity(0.15),
                              blurRadius: 3,
                              offset: const Offset(0, 1),
                            ),
                          ],
                        ),
                      ),
                    ),
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

// 应用列表项优化组件（减少重建）
class _AppListItem extends StatelessWidget {
  final String appName;
  final String packageName;
  final Uint8List? iconBytes;
  final bool isSelected;
  final VoidCallback onToggle;

  const _AppListItem({
    required this.appName,
    required this.packageName,
    required this.iconBytes,
    required this.isSelected,
    required this.onToggle,
  });

  @override
  Widget build(BuildContext context) {
    return Material(
      color: Colors.transparent,
      child: InkWell(
        onTap: onToggle,
        splashColor: const Color(0x20FFB5C5), // 浅浅的粉红色（四色渐变中间色）
        highlightColor: const Color(0x10E0B5DC), // 浅浅的紫色高光
        child: Padding(
          padding: const EdgeInsets.symmetric(horizontal: 20, vertical: 12),
          child: Row(
            children: [
              // 图标（全分辨率，不压缩不受损）
              if (iconBytes != null)
                Image.memory(
                  iconBytes!,
                  width: 48,
                  height: 48,
                  fit: BoxFit.contain,
                  gaplessPlayback: true,
                  filterQuality: FilterQuality.high,
                  isAntiAlias: true,
                )
              else
                const Icon(Icons.android, size: 48, color: Colors.white),
              const SizedBox(width: 12),
              // 文本
              Expanded(
                child: Column(
                  crossAxisAlignment: CrossAxisAlignment.start,
                  mainAxisAlignment: MainAxisAlignment.center,
                  children: [
                    Text(
                      appName,
                      maxLines: 1,
                      overflow: TextOverflow.ellipsis,
                      style: const TextStyle(color: Colors.white, fontSize: 15),
                    ),
                    const SizedBox(height: 2),
                    Text(
                      packageName,
                      style: const TextStyle(
                        fontSize: 11,
                        color: Colors.white70,
                      ),
                      maxLines: 1,
                      overflow: TextOverflow.ellipsis,
                    ),
                  ],
                ),
              ),
              const SizedBox(width: 8),
              // 渐变复选框
              _GradientCheckbox(
                value: isSelected,
                onChanged: (_) => onToggle(),
              ),
            ],
          ),
        ),
      ),
    );
  }
}

// 渐变复选框（替代绿色Checkbox）- 带过渡动画
class _GradientCheckbox extends StatefulWidget {
  final bool value;
  final ValueChanged<bool> onChanged;

  const _GradientCheckbox({required this.value, required this.onChanged});

  @override
  State<_GradientCheckbox> createState() => _GradientCheckboxState();
}

class _GradientCheckboxState extends State<_GradientCheckbox> {
  bool _pressed = false;

  @override
  Widget build(BuildContext context) {
    return GestureDetector(
      onTapDown: (_) => setState(() => _pressed = true),
      onTapUp: (_) => setState(() => _pressed = false),
      onTapCancel: () => setState(() => _pressed = false),
      onTap: () => widget.onChanged(!widget.value),
      child: AnimatedScale(
        duration: const Duration(milliseconds: 120),
        scale: _pressed ? 0.9 : 1.0,
        child: ClipPath(
          clipper: _SquircleClipper(cornerRadius: _SquircleRadii.checkbox),
          child: SizedBox(
            width: 24,
            height: 24,
            child: Stack(
              children: [
                // 底层半透明背景
                Container(color: Colors.white.withOpacity(0.25)),
                // 渐变层（淡入淡出）
                AnimatedOpacity(
                  duration: const Duration(milliseconds: 200),
                  opacity: widget.value ? 1.0 : 0.0,
                  child: Container(
                    decoration: const BoxDecoration(
                      gradient: LinearGradient(
                        begin: Alignment.topLeft,
                        end: Alignment.bottomRight,
                        colors: [
                          Color(0xFFFF9D88),
                          Color(0xFFFFB5C5),
                          Color(0xFFE0B5DC),
                          Color(0xFFA8C5E5),
                        ],
                      ),
                    ),
                  ),
                ),
                // 边框（渐隐）- 使用CustomPaint绘制超椭圆边框
                AnimatedOpacity(
                  duration: const Duration(milliseconds: 200),
                  opacity: widget.value ? 0.0 : 1.0,
                  child: CustomPaint(
                    painter: _SquircleBorderPainter(
                      radius: _SquircleRadii.checkbox,
                      color: Colors.white.withOpacity(0.4),
                      strokeWidth: 2,
                    ),
                  ),
                ),
                // 对勾（缩放弹出）
                Center(
                  child: AnimatedScale(
                    duration: const Duration(milliseconds: 200),
                    curve: Curves.easeOutBack,
                    scale: widget.value ? 1.0 : 0.0,
                    child: const Icon(
                      Icons.check,
                      size: 18,
                      color: Colors.white,
                    ),
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

/// 超椭圆圆角半径
/// 基于屏幕物理圆角半径16.4mm，超椭圆指数n=2.84
/// 使用固定值确保视觉一致性（基于标准DPI 420计算）
class _SquircleRadii {
  // 16.4mm @ 420dpi ≈ 27dp，实际屏幕略大，取32dp
  static const double large = 32.0; // 大卡片圆角
  static const double small = 12.0; // 小组件圆角 (large * 0.375)
  static const double tiny = 16.0; // 开关圆角
  static const double checkbox = 6.0; // 复选框圆角
}

/// 精确的超椭圆（Squircle）形状边框 - 用于InkWell水波纹
/// 使用2.84指数实现与屏幕圆角一致的平滑曲线
class _SquircleShapeBorder extends ShapeBorder {
  final double cornerRadius;
  static const double n = 2.84; // 超椭圆指数

  const _SquircleShapeBorder({required this.cornerRadius});

  @override
  EdgeInsetsGeometry get dimensions => EdgeInsets.zero;

  @override
  Path getInnerPath(Rect rect, {TextDirection? textDirection}) {
    return _createSquirclePath(rect.size, cornerRadius);
  }

  @override
  Path getOuterPath(Rect rect, {TextDirection? textDirection}) {
    return _createSquirclePath(rect.size, cornerRadius);
  }

  @override
  void paint(Canvas canvas, Rect rect, {TextDirection? textDirection}) {}

  @override
  ShapeBorder scale(double t) =>
      _SquircleShapeBorder(cornerRadius: cornerRadius * t);

  static Path _createSquirclePath(Size size, double radius) {
    final double width = size.width;
    final double height = size.height;
    final double effectiveRadius = radius.clamp(
      0.0,
      math.min(width, height) / 2,
    );

    final path = Path();

    // 顶部左侧圆角
    path.moveTo(0, effectiveRadius);
    for (double t = 0; t <= 1.0; t += 0.02) {
      final angle = (1 - t) * math.pi / 2;
      final x =
          effectiveRadius *
          (1 -
              math.pow(math.cos(angle).abs(), 2 / n) *
                  (math.cos(angle) >= 0 ? 1 : -1));
      final y =
          effectiveRadius *
          (1 -
              math.pow(math.sin(angle).abs(), 2 / n) *
                  (math.sin(angle) >= 0 ? 1 : -1));
      path.lineTo(x, y);
    }

    // 顶边
    path.lineTo(width - effectiveRadius, 0);

    // 顶部右侧圆角
    for (double t = 0; t <= 1.0; t += 0.02) {
      final angle = t * math.pi / 2;
      final x =
          width -
          effectiveRadius *
              (1 -
                  math.pow(math.cos(angle).abs(), 2 / n) *
                      (math.cos(angle) >= 0 ? 1 : -1));
      final y =
          effectiveRadius *
          (1 -
              math.pow(math.sin(angle).abs(), 2 / n) *
                  (math.sin(angle) >= 0 ? 1 : -1));
      path.lineTo(x, y);
    }

    // 右边
    path.lineTo(width, height - effectiveRadius);

    // 底部右侧圆角
    for (double t = 0; t <= 1.0; t += 0.02) {
      final angle = (1 - t) * math.pi / 2 + math.pi / 2;
      final x =
          width -
          effectiveRadius *
              (1 -
                  math.pow(math.cos(angle).abs(), 2 / n) *
                      (math.cos(angle) >= 0 ? 1 : -1));
      final y =
          height -
          effectiveRadius *
              (1 -
                  math.pow(math.sin(angle).abs(), 2 / n) *
                      (math.sin(angle) >= 0 ? 1 : -1));
      path.lineTo(x, y);
    }

    // 底边
    path.lineTo(effectiveRadius, height);

    // 底部左侧圆角
    for (double t = 0; t <= 1.0; t += 0.02) {
      final angle = t * math.pi / 2 + math.pi;
      final x =
          effectiveRadius *
          (1 -
              math.pow(math.cos(angle).abs(), 2 / n) *
                  (math.cos(angle) >= 0 ? 1 : -1));
      final y =
          height -
          effectiveRadius *
              (1 -
                  math.pow(math.sin(angle).abs(), 2 / n) *
                      (math.sin(angle) >= 0 ? 1 : -1));
      path.lineTo(x, y);
    }

    path.close();
    return path;
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

/// 精确的超椭圆边框绘制器
/// 用于绘制带边框的超椭圆
class _SquircleBorderPainter extends CustomPainter {
  final double radius;
  final Color color;
  final double strokeWidth;
  static const double n = 2.84; // 超椭圆指数

  _SquircleBorderPainter({
    required this.radius,
    required this.color,
    required this.strokeWidth,
  });

  @override
  void paint(Canvas canvas, Size size) {
    final paint = Paint()
      ..color = color
      ..style = PaintingStyle.stroke
      ..strokeWidth = strokeWidth;

    final path = _createSquirclePath(size, radius);
    canvas.drawPath(path, paint);
  }

  Path _createSquirclePath(Size size, double r) {
    final w = size.width;
    final h = size.height;

    final path = Path();
    path.moveTo(0, r);

    // 左上角
    _drawSquircleArc(path, r, r, r, math.pi, math.pi * 1.5);
    path.lineTo(w - r, 0);

    // 右上角
    _drawSquircleArc(path, w - r, r, r, math.pi * 1.5, math.pi * 2);
    path.lineTo(w, h - r);

    // 右下角
    _drawSquircleArc(path, w - r, h - r, r, 0, math.pi * 0.5);
    path.lineTo(r, h);

    // 左下角
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
  bool shouldRepaint(_SquircleBorderPainter oldDelegate) {
    return oldDelegate.radius != radius ||
        oldDelegate.color != color ||
        oldDelegate.strokeWidth != strokeWidth;
  }
}

/// V2.4: 应用选择页面
class AppSelectionPage extends StatefulWidget {
  const AppSelectionPage({super.key});

  @override
  State<AppSelectionPage> createState() => _AppSelectionPageState();
}

class _AppSelectionPageState extends State<AppSelectionPage> {
  static const platform = MethodChannel(
    'com.display.switcher/task',
  ); // ✅ 修正channel名称

  List<Map<String, dynamic>> _apps = [];
  List<Map<String, dynamic>> _visibleApps = [];
  Set<String> _selectedApps = {};
  bool _isLoading = true;

  bool _includeSystemApps = false; // 是否显示系统应用
  final TextEditingController _searchController = TextEditingController();

  @override
  void initState() {
    super.initState();
    _loadApps();
  }

  @override
  void dispose() {
    _searchController.dispose();
    super.dispose();
  }

  // 启动权限检查循环（后台异步）
  void _startPermissionCheckLoop() async {
    print('→ 启动权限检查循环');
    int checkAttempts = 0;

    while (checkAttempts < 30 && mounted) {
      // 最多检查30次（30秒）
      await Future.delayed(const Duration(seconds: 1));

      if (!mounted) break; // 页面已销毁，退出循环

      try {
        final bool granted = await platform.invokeMethod(
          'checkQueryAllPackagesPermission',
        );
        if (granted) {
          print('✓ 权限已授予，自动刷新应用列表');

          // 权限已授予，刷新列表
          if (mounted) {
            setState(() {
              _isLoading = true;
            });

            await _loadAppsInternal();

            ScaffoldMessenger.of(context).showSnackBar(
              SnackBar(
                content: Text(
                  AppLocalizations.of(
                    context,
                  ).translate('permission_granted_refresh'),
                ),
              ),
            );
          }
          return; // 成功，退出循环
        }
      } catch (e) {
        print('权限检查失败: $e');
      }

      checkAttempts++;
    }

    print('⚠ 权限检查超时（30秒），用户可能未授予权限');

    if (mounted) {
      ScaffoldMessenger.of(context).showSnackBar(
        SnackBar(
          content: Text(
            AppLocalizations.of(context).translate('grant_permission_manual'),
          ),
        ),
      );
    }
  }

  // 内部加载方法（不检查权限，直接加载）
  Future<void> _loadAppsInternal() async {
    try {
      // 加载已选择的应用
      final List<dynamic> selectedApps = await platform.invokeMethod(
        'getSelectedNotificationApps',
      );
      _selectedApps = selectedApps.cast<String>().toSet();

      // 加载所有应用
      final List<dynamic> apps = await platform.invokeMethod(
        'getInstalledApps',
      );

      setState(() {
        _apps = apps.map((app) => Map<String, dynamic>.from(app)).toList();
        _isLoading = false;
      });

      _applyFilters();

      print('已加载 ${_apps.length} 个应用');
    } catch (e) {
      print('加载应用列表失败: $e');
      setState(() {
        _isLoading = false;
      });
    }
  }

  void _applyFilters() {
    final String q = _searchController.text.trim().toLowerCase();
    List<Map<String, dynamic>> filtered = _apps.where((app) {
      final String name = (app['appName'] ?? '').toString().toLowerCase();
      final String pkg = (app['packageName'] ?? '').toString().toLowerCase();
      final bool matchesQuery =
          q.isEmpty || name.contains(q) || pkg.contains(q);
      if (!_includeSystemApps && _isSystemApp(app)) {
        return false;
      }
      return matchesQuery;
    }).toList();

    // 排序：选中的应用置顶，然后按应用名排序
    filtered.sort((a, b) {
      final String pkgA = a['packageName'] ?? '';
      final String pkgB = b['packageName'] ?? '';
      final bool selectedA = _selectedApps.contains(pkgA);
      final bool selectedB = _selectedApps.contains(pkgB);

      // 如果一个是选中的，一个是未选中的，选中的排在前面
      if (selectedA && !selectedB) return -1;
      if (!selectedA && selectedB) return 1;

      // 如果都是选中或都是未选中，按应用名排序
      final String nameA = (a['appName'] ?? '').toString().toLowerCase();
      final String nameB = (b['appName'] ?? '').toString().toLowerCase();
      return nameA.compareTo(nameB);
    });

    setState(() {
      _visibleApps = filtered;
    });
  }

  bool _isSystemApp(Map<String, dynamic> app) {
    final pkg = (app['packageName'] ?? '').toString();
    final dynamic flag1 = app['isSystem'];
    final dynamic flag2 = app['isSystemApp'];
    if (flag1 == true || flag2 == true) return true;
    return pkg.startsWith('com.android.') ||
        pkg.startsWith('com.google.android.') ||
        pkg.startsWith('android');
  }

  Future<void> _selectAllVisible() async {
    setState(() {
      for (final app in _visibleApps) {
        final String pkg = app['packageName'];
        _selectedApps.add(pkg);
      }
    });
    // 重新应用过滤器以更新排序
    _applyFilters();
    try {
      await platform.invokeMethod(
        'setSelectedNotificationApps',
        _selectedApps.toList(),
      );
    } catch (e) {
      print('批量全选保存失败: $e');
    }
  }

  Future<void> _deselectAllVisible() async {
    setState(() {
      for (final app in _visibleApps) {
        final String pkg = app['packageName'];
        _selectedApps.remove(pkg);
      }
    });
    // 重新应用过滤器以更新排序
    _applyFilters();
    try {
      await platform.invokeMethod(
        'setSelectedNotificationApps',
        _selectedApps.toList(),
      );
    } catch (e) {
      print('批量全不选保存失败: $e');
    }
  }

  Future<void> _loadApps() async {
    setState(() => _isLoading = true);

    try {
      // ✅ 主动检查QUERY_ALL_PACKAGES权限
      print('🔍 开始检查QUERY_ALL_PACKAGES权限...');
      final bool hasPermission = await platform.invokeMethod(
        'checkQueryAllPackagesPermission',
      );
      print('🔍 权限检查结果: $hasPermission');

      if (!hasPermission) {
        print('❌ 没有QUERY_ALL_PACKAGES权限，显示弹窗');
        // 没有权限，弹窗提示并跳转到设置
        setState(() => _isLoading = false);

        if (mounted) {
          final shouldOpenSettings = await showDialog<bool>(
            context: context,
            builder: (context) => AlertDialog(
              title: Text(
                AppLocalizations.of(
                  context,
                ).translate('no_permission_dialog_title'),
              ),
              content: Text(
                AppLocalizations.of(
                  context,
                ).translate('no_permission_dialog_content'),
              ),
              actions: [
                TextButton(
                  onPressed: () => Navigator.pop(context, false),
                  child: Text(AppLocalizations.of(context).translate('cancel')),
                ),
                TextButton(
                  onPressed: () => Navigator.pop(context, true),
                  child: Text(
                    AppLocalizations.of(context).translate('go_to_settings'),
                  ),
                ),
              ],
            ),
          );

          if (shouldOpenSettings == true) {
            await platform.invokeMethod('requestQueryAllPackagesPermission');

            // 启动后台检查任务（不阻塞UI）
            _startPermissionCheckLoop();
          }
        }
        return;
      }

      // ✅ 有权限，继续加载
      await _loadAppsInternal();
    } catch (e) {
      print('加载应用列表失败: $e');
      setState(() => _isLoading = false);
    }
  }

  Future<void> _toggleApp(String packageName, bool selected) async {
    setState(() {
      if (selected) {
        _selectedApps.add(packageName);
      } else {
        _selectedApps.remove(packageName);
      }
    });

    // 重新应用过滤器以更新排序（选中的应用置顶）
    _applyFilters();

    // 保存到后台
    try {
      await platform.invokeMethod(
        'setSelectedNotificationApps',
        _selectedApps.toList(),
      );
    } catch (e) {
      print('保存选择失败: $e');
    }
  }

  @override
  Widget build(BuildContext context) {
    return Scaffold(
      appBar: AppBar(
        title: Text(
          '${AppLocalizations.of(context).translate('select_app_title')} (${_selectedApps.length})',
        ),
        backgroundColor: Colors.transparent,
        foregroundColor: Colors.white,
        elevation: 0,
        scrolledUnderElevation: 0,
        surfaceTintColor: Colors.transparent,
        shadowColor: Colors.transparent,
        actions: [
          IconButton(
            icon: const Icon(Icons.settings),
            onPressed: () {
              Navigator.push(
                context,
                MaterialPageRoute(
                  builder: (context) => const NotificationSettingsPage(),
                ),
              );
            },
            tooltip: AppLocalizations.of(
              context,
            ).translate('notification_settings_tooltip'),
          ),
        ],
      ),
      extendBodyBehindAppBar: true,
      body: Container(
        width: double.infinity,
        height: double.infinity,
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
        child: SafeArea(
          child: _isLoading
              ? const Center(
                  child: CircularProgressIndicator(color: Colors.white),
                )
              : Padding(
                  padding: const EdgeInsets.all(20),
                  child: Column(
                    children: [
                      // 筛选与批量操作卡片
                      CustomPaint(
                        painter: _SquircleBorderPainter(
                          radius: 32,
                          color: Colors.white.withOpacity(0.5),
                          strokeWidth: 1.5,
                        ),
                        child: ClipPath(
                          clipper: _SquircleClipper(
                            cornerRadius: _SquircleRadii.large,
                          ),
                          child: BackdropFilter(
                            filter: ImageFilter.blur(sigmaX: 0, sigmaY: 0),
                            child: Container(
                              padding: const EdgeInsets.symmetric(
                                horizontal: 20,
                                vertical: 12,
                              ),
                              decoration: BoxDecoration(
                                color: Colors.white.withOpacity(0.25),
                              ),
                              child: Column(
                                children: [
                                  TextField(
                                    controller: _searchController,
                                    onChanged: (_) => _applyFilters(),
                                    style: const TextStyle(
                                      color: Colors.black87,
                                    ),
                                    decoration: InputDecoration(
                                      hintText: AppLocalizations.of(
                                        context,
                                      ).translate('search_hint'),
                                      hintStyle: const TextStyle(
                                        color: Colors.black45,
                                      ),
                                      prefixIcon: Icon(
                                        Icons.search,
                                        color: Colors.black54,
                                      ),
                                      border: OutlineInputBorder(
                                        borderRadius: BorderRadius.all(
                                          Radius.circular(_SquircleRadii.small),
                                        ),
                                        borderSide: BorderSide(
                                          color: Colors.black26,
                                        ),
                                      ),
                                      enabledBorder: OutlineInputBorder(
                                        borderRadius: BorderRadius.all(
                                          Radius.circular(_SquircleRadii.small),
                                        ),
                                        borderSide: BorderSide(
                                          color: Colors.black26,
                                        ),
                                      ),
                                      focusedBorder: OutlineInputBorder(
                                        borderRadius: BorderRadius.all(
                                          Radius.circular(_SquircleRadii.small),
                                        ),
                                        borderSide: BorderSide(
                                          color: Colors.black54,
                                          width: 2,
                                        ),
                                      ),
                                    ),
                                  ),
                                  const SizedBox(height: 10),
                                  Row(
                                    children: [
                                      // 全选/全不选
                                      ClipPath(
                                        clipper: _SquircleClipper(
                                          cornerRadius: _SquircleRadii.small,
                                        ),
                                        child: Container(
                                          decoration: const BoxDecoration(
                                            gradient: LinearGradient(
                                              begin: Alignment.topLeft,
                                              end: Alignment.bottomRight,
                                              colors: [
                                                Color(0xFFFF9D88),
                                                Color(0xFFFFB5C5),
                                                Color(0xFFE0B5DC),
                                                Color(0xFFA8C5E5),
                                              ],
                                            ),
                                          ),
                                          child: Material(
                                            color: Colors.transparent,
                                            child: InkWell(
                                              onTap: _selectAllVisible,
                                              child: Padding(
                                                padding:
                                                    const EdgeInsets.symmetric(
                                                      horizontal: 12,
                                                      vertical: 8,
                                                    ),
                                                child: Text(
                                                  AppLocalizations.of(
                                                    context,
                                                  ).translate('select_all'),
                                                  style: const TextStyle(
                                                    color: Colors.white,
                                                  ),
                                                ),
                                              ),
                                            ),
                                          ),
                                        ),
                                      ),
                                      const SizedBox(width: 8),
                                      ClipPath(
                                        clipper: _SquircleClipper(
                                          cornerRadius: _SquircleRadii.small,
                                        ),
                                        child: Container(
                                          decoration: const BoxDecoration(
                                            gradient: LinearGradient(
                                              begin: Alignment.topLeft,
                                              end: Alignment.bottomRight,
                                              colors: [
                                                Color(0xFFFF9D88),
                                                Color(0xFFFFB5C5),
                                                Color(0xFFE0B5DC),
                                                Color(0xFFA8C5E5),
                                              ],
                                            ),
                                          ),
                                          child: Material(
                                            color: Colors.transparent,
                                            child: InkWell(
                                              onTap: _deselectAllVisible,
                                              child: Padding(
                                                padding:
                                                    const EdgeInsets.symmetric(
                                                      horizontal: 12,
                                                      vertical: 8,
                                                    ),
                                                child: Text(
                                                  AppLocalizations.of(
                                                    context,
                                                  ).translate('deselect_all'),
                                                  style: const TextStyle(
                                                    color: Colors.white,
                                                  ),
                                                ),
                                              ),
                                            ),
                                          ),
                                        ),
                                      ),
                                      const Spacer(),
                                      Text(
                                        AppLocalizations.of(
                                          context,
                                        ).translate('show_system_apps'),
                                        style: const TextStyle(
                                          color: Colors.black87,
                                          fontSize: 11,
                                        ),
                                      ),
                                      const SizedBox(width: 6),
                                      _GradientToggle(
                                        value: _includeSystemApps,
                                        onChanged: (v) {
                                          setState(
                                            () => _includeSystemApps = v,
                                          );
                                          _applyFilters();
                                        },
                                      ),
                                    ],
                                  ),
                                ],
                              ),
                            ),
                          ),
                        ),
                      ),
                      const SizedBox(height: 20),
                      // 应用列表
                      Expanded(
                        child: ListView.builder(
                          itemCount: _visibleApps.length,
                          padding: const EdgeInsets.symmetric(vertical: 8),
                          itemExtent: 72,
                          cacheExtent: 500,
                          addAutomaticKeepAlives: false,
                          addRepaintBoundaries: true,
                          physics: const ClampingScrollPhysics(),
                          itemBuilder: (context, index) {
                            final app = _visibleApps[index];
                            final String appName = app['appName'];
                            final String packageName = app['packageName'];
                            final Uint8List? iconBytes = app['icon'];
                            final bool isSelected = _selectedApps.contains(
                              packageName,
                            );
                            return _AppListItem(
                              appName: appName,
                              packageName: packageName,
                              iconBytes: iconBytes,
                              isSelected: isSelected,
                              onToggle: () =>
                                  _toggleApp(packageName, !isSelected),
                            );
                          },
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

/// V3.4: 通知设置页面
class NotificationSettingsPage extends StatefulWidget {
  const NotificationSettingsPage({super.key});

  @override
  State<NotificationSettingsPage> createState() =>
      _NotificationSettingsPageState();
}

class _NotificationSettingsPageState extends State<NotificationSettingsPage> {
  static const platform = MethodChannel('com.display.switcher/task');

  bool _privacyHideTitle = false;
  bool _privacyHideContent = false;
  bool _followDndMode = true;
  bool _onlyWhenLocked = false;
  bool _notificationDarkMode = false;
  int _notificationDuration = 10;
  final TextEditingController _durationController = TextEditingController();
  final FocusNode _durationFocusNode = FocusNode();

  @override
  void initState() {
    super.initState();
    _loadAllSettings();
  }

  @override
  void dispose() {
    _durationController.dispose();
    _durationFocusNode.dispose();
    super.dispose();
  }

  Future<void> _loadAllSettings() async {
    try {
      final prefs = await SharedPreferences.getInstance();
      setState(() {
        _privacyHideTitle =
            prefs.getBool('notification_privacy_hide_title') ?? false;
        _privacyHideContent =
            prefs.getBool('notification_privacy_hide_content') ?? false;
        _followDndMode = prefs.getBool('notification_follow_dnd_mode') ?? true;
        _onlyWhenLocked =
            prefs.getBool('notification_only_when_locked') ?? false;
        _notificationDarkMode =
            prefs.getBool('notification_dark_mode') ?? false;
        _notificationDuration = prefs.getInt('notification_duration') ?? 10;
        _durationController.text = _notificationDuration.toString();
      });
    } catch (e) {
      print('加载通知设置失败: $e');
    }
  }

  Future<void> _togglePrivacyHideTitle(bool enabled) async {
    try {
      await platform.invokeMethod('setNotificationPrivacyHideTitle', {
        'enabled': enabled,
      });
      final prefs = await SharedPreferences.getInstance();
      await prefs.setBool('notification_privacy_hide_title', enabled);
      setState(() {
        _privacyHideTitle = enabled;
      });
    } catch (e) {
      print('切换隐藏标题失败: $e');
    }
  }

  Future<void> _togglePrivacyHideContent(bool enabled) async {
    try {
      await platform.invokeMethod('setNotificationPrivacyHideContent', {
        'enabled': enabled,
      });
      final prefs = await SharedPreferences.getInstance();
      await prefs.setBool('notification_privacy_hide_content', enabled);
      setState(() {
        _privacyHideContent = enabled;
      });
    } catch (e) {
      print('切换隐藏内容失败: $e');
    }
  }

  Future<void> _toggleFollowDndMode(bool enabled) async {
    try {
      await platform.invokeMethod('setFollowDndMode', {'enabled': enabled});
      final prefs = await SharedPreferences.getInstance();
      await prefs.setBool('notification_follow_dnd_mode', enabled);
      setState(() {
        _followDndMode = enabled;
      });
    } catch (e) {
      print('切换勿扰模式设置失败: $e');
    }
  }

  Future<void> _toggleOnlyWhenLocked(bool enabled) async {
    try {
      await platform.invokeMethod('setOnlyWhenLocked', {'enabled': enabled});
      final prefs = await SharedPreferences.getInstance();
      await prefs.setBool('notification_only_when_locked', enabled);
      setState(() {
        _onlyWhenLocked = enabled;
      });
    } catch (e) {
      print('切换倒扣手机通知设置失败: $e');
    }
  }

  Future<void> _toggleNotificationDarkMode(bool enabled) async {
    try {
      await platform.invokeMethod('setNotificationDarkMode', {
        'enabled': enabled,
      });
      final prefs = await SharedPreferences.getInstance();
      await prefs.setBool('notification_dark_mode', enabled);
      setState(() {
        _notificationDarkMode = enabled;
      });
    } catch (e) {
      print('切换通知暗夜模式失败: $e');
    }
  }

  Future<void> _setNotificationDuration(int seconds) async {
    try {
      await platform.invokeMethod('setNotificationDuration', {
        'duration': seconds,
      });
      final prefs = await SharedPreferences.getInstance();
      await prefs.setInt('notification_duration', seconds);
      setState(() {
        _notificationDuration = seconds;
      });
      if (mounted) {
        ScaffoldMessenger.of(context).showSnackBar(
          SnackBar(
            content: Text(
              AppLocalizations.of(
                context,
              ).translate('toast_duration_set').replaceAll('{0}', '$seconds'),
            ),
          ),
        );
      }
    } catch (e) {
      print('设置通知销毁时间失败: $e');
    }
  }

  @override
  Widget build(BuildContext context) {
    return Scaffold(
      appBar: AppBar(
        title: Text(
          AppLocalizations.of(context).translate('notification_settings_title'),
        ),
        backgroundColor: Colors.transparent,
        foregroundColor: Colors.white,
        elevation: 0,
        scrolledUnderElevation: 0,
        surfaceTintColor: Colors.transparent,
        shadowColor: Colors.transparent,
      ),
      extendBodyBehindAppBar: true,
      body: Container(
        width: double.infinity,
        height: double.infinity,
        decoration: const BoxDecoration(
          gradient: LinearGradient(
            begin: Alignment.topLeft,
            end: Alignment.bottomRight,
            colors: [
              Color(0xFFFF9D88),
              Color(0xFFFFB5C5),
              Color(0xFFE0B5DC),
              Color(0xFFA8C5E5),
            ],
          ),
        ),
        child: SafeArea(
          child: ListView(
            padding: const EdgeInsets.all(20),
            children: [
              // 隐私模式卡片
              CustomPaint(
                painter: _SquircleBorderPainter(
                  radius: 32,
                  color: Colors.white.withOpacity(0.5),
                  strokeWidth: 1.5,
                ),
                child: ClipPath(
                  clipper: _SquircleClipper(cornerRadius: _SquircleRadii.large),
                  child: BackdropFilter(
                    filter: ImageFilter.blur(sigmaX: 0, sigmaY: 0),
                    child: Container(
                      padding: const EdgeInsets.symmetric(
                        horizontal: 20,
                        vertical: 16,
                      ),
                      decoration: BoxDecoration(
                        color: Colors.white.withOpacity(0.25),
                      ),
                      child: Column(
                        children: [
                          Row(
                            children: [
                              const Icon(
                                Icons.lock_outline,
                                size: 20,
                                color: Colors.black54,
                              ),
                              const SizedBox(width: 8),
                              Text(
                                AppLocalizations.of(
                                  context,
                                ).translate('hide_notification_title'),
                                style: const TextStyle(
                                  fontSize: 14,
                                  color: Colors.black87,
                                  fontWeight: FontWeight.w500,
                                ),
                              ),
                              const Spacer(),
                              _GradientToggle(
                                value: _privacyHideTitle,
                                onChanged: _togglePrivacyHideTitle,
                              ),
                            ],
                          ),
                          const SizedBox(height: 12),
                          const Divider(color: Colors.black26, height: 1),
                          const SizedBox(height: 12),
                          Row(
                            children: [
                              const Icon(
                                Icons.lock_outline,
                                size: 20,
                                color: Colors.black54,
                              ),
                              const SizedBox(width: 8),
                              Text(
                                AppLocalizations.of(
                                  context,
                                ).translate('hide_notification_content'),
                                style: const TextStyle(
                                  fontSize: 14,
                                  color: Colors.black87,
                                  fontWeight: FontWeight.w500,
                                ),
                              ),
                              const Spacer(),
                              _GradientToggle(
                                value: _privacyHideContent,
                                onChanged: _togglePrivacyHideContent,
                              ),
                            ],
                          ),
                        ],
                      ),
                    ),
                  ),
                ),
              ),

              const SizedBox(height: 20),

              // 跟随系统勿扰模式
              CustomPaint(
                painter: _SquircleBorderPainter(
                  radius: 32,
                  color: Colors.white.withOpacity(0.5),
                  strokeWidth: 1.5,
                ),
                child: ClipPath(
                  clipper: _SquircleClipper(cornerRadius: _SquircleRadii.large),
                  child: BackdropFilter(
                    filter: ImageFilter.blur(sigmaX: 0, sigmaY: 0),
                    child: Container(
                      padding: const EdgeInsets.symmetric(
                        horizontal: 20,
                        vertical: 16,
                      ),
                      decoration: BoxDecoration(
                        color: Colors.white.withOpacity(0.25),
                      ),
                      child: Row(
                        children: [
                          const Icon(
                            Icons.notifications_paused,
                            size: 20,
                            color: Colors.black54,
                          ),
                          const SizedBox(width: 8),
                          Text(
                            AppLocalizations.of(
                              context,
                            ).translate('follow_system_dnd'),
                            style: const TextStyle(
                              fontSize: 14,
                              color: Colors.black87,
                              fontWeight: FontWeight.w500,
                            ),
                          ),
                          const Spacer(),
                          _GradientToggle(
                            value: _followDndMode,
                            onChanged: _toggleFollowDndMode,
                          ),
                        ],
                      ),
                    ),
                  ),
                ),
              ),

              const SizedBox(height: 20),

              // 仅倒扣手机时通知
              CustomPaint(
                painter: _SquircleBorderPainter(
                  radius: 32,
                  color: Colors.white.withOpacity(0.5),
                  strokeWidth: 1.5,
                ),
                child: ClipPath(
                  clipper: _SquircleClipper(cornerRadius: _SquircleRadii.large),
                  child: BackdropFilter(
                    filter: ImageFilter.blur(sigmaX: 0, sigmaY: 0),
                    child: Container(
                      padding: const EdgeInsets.symmetric(
                        horizontal: 20,
                        vertical: 16,
                      ),
                      decoration: BoxDecoration(
                        color: Colors.white.withOpacity(0.25),
                      ),
                      child: Row(
                        children: [
                          const Icon(
                            Icons.flip_camera_android,
                            size: 20,
                            color: Colors.black54,
                          ),
                          const SizedBox(width: 8),
                          Text(
                            AppLocalizations.of(
                              context,
                            ).translate('only_when_locked'),
                            style: const TextStyle(
                              fontSize: 14,
                              color: Colors.black87,
                              fontWeight: FontWeight.w500,
                            ),
                          ),
                          const Spacer(),
                          _GradientToggle(
                            value: _onlyWhenLocked,
                            onChanged: _toggleOnlyWhenLocked,
                          ),
                        ],
                      ),
                    ),
                  ),
                ),
              ),

              const SizedBox(height: 20),

              // 通知暗夜模式
              CustomPaint(
                painter: _SquircleBorderPainter(
                  radius: 32,
                  color: Colors.white.withOpacity(0.5),
                  strokeWidth: 1.5,
                ),
                child: ClipPath(
                  clipper: _SquircleClipper(cornerRadius: _SquircleRadii.large),
                  child: BackdropFilter(
                    filter: ImageFilter.blur(sigmaX: 0, sigmaY: 0),
                    child: Container(
                      padding: const EdgeInsets.symmetric(
                        horizontal: 20,
                        vertical: 16,
                      ),
                      decoration: BoxDecoration(
                        color: Colors.white.withOpacity(0.25),
                      ),
                      child: Row(
                        children: [
                          const Icon(
                            Icons.dark_mode,
                            size: 20,
                            color: Colors.black54,
                          ),
                          const SizedBox(width: 8),
                          Text(
                            AppLocalizations.of(
                              context,
                            ).translate('notification_dark_mode'),
                            style: const TextStyle(
                              fontSize: 14,
                              color: Colors.black87,
                              fontWeight: FontWeight.w500,
                            ),
                          ),
                          const Spacer(),
                          _GradientToggle(
                            value: _notificationDarkMode,
                            onChanged: _toggleNotificationDarkMode,
                          ),
                        ],
                      ),
                    ),
                  ),
                ),
              ),

              const SizedBox(height: 20),

              // 自动销毁时间
              CustomPaint(
                painter: _SquircleBorderPainter(
                  radius: 32,
                  color: Colors.white.withOpacity(0.5),
                  strokeWidth: 1.5,
                ),
                child: ClipPath(
                  clipper: _SquircleClipper(cornerRadius: _SquircleRadii.large),
                  child: BackdropFilter(
                    filter: ImageFilter.blur(sigmaX: 0, sigmaY: 0),
                    child: Container(
                      padding: const EdgeInsets.all(20),
                      decoration: BoxDecoration(
                        color: Colors.white.withOpacity(0.25),
                      ),
                      child: Column(
                        crossAxisAlignment: CrossAxisAlignment.start,
                        children: [
                          Row(
                            children: [
                              const Icon(
                                Icons.timer_outlined,
                                size: 20,
                                color: Colors.black54,
                              ),
                              const SizedBox(width: 8),
                              Text(
                                AppLocalizations.of(
                                  context,
                                ).translate('auto_destroy_time'),
                                style: const TextStyle(
                                  fontSize: 14,
                                  color: Colors.black87,
                                  fontWeight: FontWeight.w500,
                                ),
                              ),
                            ],
                          ),
                          const SizedBox(height: 12),
                          Row(
                            children: [
                              Expanded(
                                child: TextField(
                                  controller: _durationController,
                                  focusNode: _durationFocusNode,
                                  keyboardType: TextInputType.number,
                                  style: const TextStyle(color: Colors.black87),
                                  decoration: InputDecoration(
                                    labelText: AppLocalizations.of(
                                      context,
                                    ).translate('new_time_seconds'),
                                    labelStyle: const TextStyle(
                                      color: Colors.black54,
                                    ),
                                    hintText: AppLocalizations.of(
                                      context,
                                    ).translate('input_seconds'),
                                    hintStyle: const TextStyle(
                                      color: Colors.black38,
                                    ),
                                    border: OutlineInputBorder(
                                      borderRadius: BorderRadius.all(
                                        Radius.circular(_SquircleRadii.small),
                                      ),
                                      borderSide: BorderSide(
                                        color: Colors.black26,
                                      ),
                                    ),
                                    enabledBorder: OutlineInputBorder(
                                      borderRadius: BorderRadius.all(
                                        Radius.circular(_SquircleRadii.small),
                                      ),
                                      borderSide: BorderSide(
                                        color: Colors.black26,
                                      ),
                                    ),
                                    focusedBorder: OutlineInputBorder(
                                      borderRadius: BorderRadius.all(
                                        Radius.circular(_SquircleRadii.small),
                                      ),
                                      borderSide: BorderSide(
                                        color: Colors.black54,
                                        width: 2,
                                      ),
                                    ),
                                  ),
                                ),
                              ),
                              const SizedBox(width: 12),
                              ClipPath(
                                clipper: _SquircleClipper(
                                  cornerRadius: _SquircleRadii.small,
                                ),
                                child: Container(
                                  decoration: const BoxDecoration(
                                    gradient: LinearGradient(
                                      begin: Alignment.topLeft,
                                      end: Alignment.bottomRight,
                                      colors: [
                                        Color(0xFFFF9D88),
                                        Color(0xFFFFB5C5),
                                        Color(0xFFE0B5DC),
                                        Color(0xFFA8C5E5),
                                      ],
                                    ),
                                  ),
                                  child: ElevatedButton(
                                    onPressed: () {
                                      final seconds = int.tryParse(
                                        _durationController.text,
                                      );
                                      if (seconds != null && seconds > 0) {
                                        _setNotificationDuration(seconds);
                                      } else {
                                        ScaffoldMessenger.of(
                                          context,
                                        ).showSnackBar(
                                          SnackBar(
                                            content: Text(
                                              AppLocalizations.of(
                                                context,
                                              ).translate('input_valid_number'),
                                            ),
                                          ),
                                        );
                                      }
                                    },
                                    style: ElevatedButton.styleFrom(
                                      backgroundColor: Colors.transparent,
                                      foregroundColor: Colors.white,
                                      shadowColor: Colors.transparent,
                                      padding: const EdgeInsets.symmetric(
                                        horizontal: 20,
                                        vertical: 12,
                                      ),
                                    ),
                                    child: Text(
                                      AppLocalizations.of(
                                        context,
                                      ).translate('confirm'),
                                    ),
                                  ),
                                ),
                              ),
                            ],
                          ),
                        ],
                      ),
                    ),
                  ),
                ),
              ),
            ],
          ),
        ),
      ),
    );
  }
}

class _MediaPillPreviewPainter extends CustomPainter {
  final Color fillColor;
  final Color strokeTopColor;
  final Color strokeBottomColor;
  final double scale;

  const _MediaPillPreviewPainter({
    required this.fillColor,
    required this.strokeTopColor,
    required this.strokeBottomColor,
    required this.scale,
  });

  @override
  void paint(Canvas canvas, Size size) {
    final s = scale;
    final inset = 1.25 * s;
    final corner = Radius.circular(11.0 * s);
    final capsuleRect = Rect.fromLTRB(
      inset,
      inset,
      size.width - inset,
      size.height - inset,
    );
    final capsuleRRect = RRect.fromRectAndRadius(capsuleRect, corner);

    // 1. Base liquid-glass fill
    final fillPaint = Paint()
      ..style = PaintingStyle.fill
      ..color = fillColor;
    canvas.drawRRect(capsuleRRect, fillPaint);

    // 2. Top specular glass sheen
    final sheenPaint = Paint()
      ..style = PaintingStyle.fill
      ..shader = LinearGradient(
        begin: Alignment.topCenter,
        end: Alignment.bottomCenter,
        colors: const [
          Color(0x24FFFFFF),
          Color(0x05FFFFFF),
          Color(0x2E000000),
        ],
        stops: const [0.0, 0.45, 1.0],
      ).createShader(Rect.fromLTRB(0, inset, size.width, size.height));
    canvas.drawRRect(capsuleRRect, sheenPaint);

    // 3. Directional top-lit rim stroke
    final strokePaint = Paint()
      ..style = PaintingStyle.stroke
      ..strokeWidth = 2.5 * s
      ..shader = LinearGradient(
        begin: Alignment.topCenter,
        end: Alignment.bottomCenter,
        colors: [strokeTopColor, strokeBottomColor],
      ).createShader(capsuleRect);
    canvas.drawRRect(capsuleRRect, strokePaint);

    // 4. 3-bar mini equalizer (playing reference heights {6, 9, 5})
    const refHeights = [6.0, 9.0, 5.0];
    final eqPaint = Paint()
      ..style = PaintingStyle.fill
      ..color = const Color(0xFF05FF44);
    final centerY = 12.0 * s;
    final barCorner = Radius.circular(1.0 * s);
    for (int i = 0; i < 3; i++) {
      final left = (12.0 + i * 3.5) * s;
      final halfH = (refHeights[i] * s) * 0.5;
      final barRect = Rect.fromLTRB(
        left,
        centerY - halfH,
        left + 2.0 * s,
        centerY + halfH,
      );
      canvas.drawRRect(RRect.fromRectAndRadius(barRect, barCorner), eqPaint);
    }

    // 5. Grab bar
    final grabPaint = Paint()
      ..style = PaintingStyle.fill
      ..color = const Color(0xD6FFFFFF);
    final grabRect = Rect.fromLTRB(
      31.5 * s,
      10.5 * s,
      82.5 * s,
      13.5 * s,
    );
    canvas.drawRRect(
      RRect.fromRectAndRadius(grabRect, Radius.circular(1.5 * s)),
      grabPaint,
    );
  }

  @override
  bool shouldRepaint(covariant _MediaPillPreviewPainter oldDelegate) {
    return oldDelegate.fillColor != fillColor ||
        oldDelegate.strokeTopColor != strokeTopColor ||
        oldDelegate.strokeBottomColor != strokeBottomColor ||
        oldDelegate.scale != scale;
  }
}

class _AlbumArtBlurPreviewPainter extends CustomPainter {
  @override
  void paint(Canvas canvas, Size size) {
    final rect = Offset.zero & size;
    final bgPaint = Paint()
      ..shader = const LinearGradient(
        begin: Alignment.topLeft,
        end: Alignment.bottomRight,
        colors: [
          Color(0xFF1E1B4B),
          Color(0xFF7C3AED),
          Color(0xFFEC4899),
          Color(0xFFF59E0B),
        ],
        stops: [0.0, 0.38, 0.72, 1.0],
      ).createShader(rect);
    canvas.drawRect(rect, bgPaint);

    // Crisp geometric artwork accents so blur differences (0% -> 100%) are immediately visible
    canvas.drawCircle(
      Offset(size.width * 0.22, size.height * 0.35),
      size.height * 0.34,
      Paint()..color = const Color(0xFF38BDF8),
    );
    canvas.drawRRect(
      RRect.fromRectAndRadius(
        Rect.fromCenter(
          center: Offset(size.width * 0.54, size.height * 0.56),
          width: size.height * 0.62,
          height: size.height * 0.62,
        ),
        const Radius.circular(8),
      ),
      Paint()..color = const Color(0xFFFDE047),
    );
    canvas.drawCircle(
      Offset(size.width * 0.80, size.height * 0.28),
      size.height * 0.26,
      Paint()..color = const Color(0xFF34D399),
    );
    // Crisp high-contrast rings & diagonal bar to make 0% sharpness unmistakable
    canvas.drawCircle(
      Offset(size.width * 0.22, size.height * 0.35),
      size.height * 0.16,
      Paint()
        ..color = Colors.white
        ..style = PaintingStyle.stroke
        ..strokeWidth = 2.5,
    );
    canvas.drawLine(
      Offset(size.width * 0.42, size.height * 0.15),
      Offset(size.width * 0.68, size.height * 0.85),
      Paint()
        ..color = const Color(0xFF0F172A)
        ..strokeWidth = 3.5,
    );
  }

  @override
  bool shouldRepaint(covariant _AlbumArtBlurPreviewPainter oldDelegate) => false;
}
