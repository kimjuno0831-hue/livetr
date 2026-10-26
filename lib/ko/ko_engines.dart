// AI 번역기 여러 개를 돌려쓰기
// · 제미나이(구글 AI Studio 키) + OpenAI 호환 서비스(Groq, NVIDIA, Mistral, OpenRouter, Cloudflare, 직접 입력)
// · 번역기 하나에 모델을 여러 개 적으면(쉼표) 모델마다 따로 돌려씀 → 제미나이 키 하나로 모델 여러 개 돌려쓰기
// · 한도에 걸리면 잠깐 쉬게 하고 다음 것으로. 다 막히면 호출한 쪽이 구글 번역으로 감.
import 'dart:async';
import 'dart:convert';

import 'package:PiliPlus/utils/storage.dart';
import 'package:dio/dio.dart';

class KoPreset {
  const KoPreset(
    this.type,
    this.name,
    this.baseUrl,
    this.models,
    this.keyUrl,
    this.note,
  );
  final String type; // 'gemini' | 'openai' | 'cloudflare'
  final String name;
  final String baseUrl;
  final String models;
  final String keyUrl;
  final String note;
}

const koPresets = <KoPreset>[
  KoPreset(
    'gemini',
    '제미나이',
    '',
    'gemini-3.5-flash-lite',
    'https://aistudio.google.com/apikey',
    '모델을 쉼표로 여러 개 적으면 하나가 한도에 걸릴 때 다음 모델로 넘어가요. [모델 목록 불러오기]로 고를 수 있어요.',
  ),
  KoPreset(
    'openai',
    'Groq',
    'https://api.groq.com/openai/v1',
    '',
    'https://console.groq.com/keys',
    'Qwen(중국 알리바바 모델)이 중국어에 강해요. [모델 목록 불러오기]에서 qwen 이 들어간 걸 고르세요.',
  ),
  KoPreset(
    'openai',
    'NVIDIA',
    'https://integrate.api.nvidia.com/v1',
    'z-ai/glm-5.3-flash',
    'https://build.nvidia.com',
    'GLM·DeepSeek·Kimi 같은 중국 모델을 쓸 수 있어요. 403 오류가 나면 build.nvidia.com 의 그 모델 페이지에서 사용 신청을 하세요.',
  ),
  KoPreset(
    'openai',
    'Mistral',
    'https://api.mistral.ai/v1',
    'mistral-small-latest',
    'https://console.mistral.ai/api-keys',
    '양이 아주 넉넉해요(초당 1번까지). 설정 Privacy 에서 데이터 학습 사용을 꺼두세요.',
  ),
  KoPreset(
    'openai',
    'OpenRouter',
    'https://openrouter.ai/api/v1',
    '',
    'https://openrouter.ai/settings/keys',
    '무료 모델(이름 끝이 :free)은 하루 50번. [모델 목록 불러오기]에서 :free 모델을 고르세요.',
  ),
  KoPreset(
    'cloudflare',
    'Cloudflare',
    '',
    '@cf/meta/llama-3.3-70b-instruct-fp8-fast',
    'https://dash.cloudflare.com/profile/api-tokens',
    '토큰과 계정 ID(Account ID)가 둘 다 필요해요. 하루 사용량이 적어서 보조용으로 좋아요.',
  ),
  KoPreset(
    'openai',
    '직접 입력 (OpenAI 호환)',
    '',
    '',
    '',
    'OpenAI 호환 주소(…/v1)와 키, 모델 이름을 직접 적어요.',
  ),
];

class KoEngine {
  KoEngine({
    required this.id,
    required this.type,
    required this.name,
    this.baseUrl = '',
    this.key = '',
    this.models = '',
    this.accountId = '',
    this.on = true,
  });

  String id;
  String type;
  String name;
  String baseUrl;
  String key;
  String models; // 쉼표로 여러 개
  String accountId; // Cloudflare
  bool on;

  List<String> get modelList => models
      .split(RegExp(r'[,\n]'))
      .map((e) => e.trim())
      .where((e) => e.isNotEmpty)
      .toList();

  bool get usable =>
      on &&
      key.trim().isNotEmpty &&
      modelList.isNotEmpty &&
      (type != 'openai' || baseUrl.trim().isNotEmpty) &&
      (type != 'cloudflare' || accountId.trim().isNotEmpty);

  String get chatUrl {
    if (type == 'cloudflare') {
      return 'https://api.cloudflare.com/client/v4/accounts/${accountId.trim()}/ai/v1/chat/completions';
    }
    var b = baseUrl.trim();
    while (b.endsWith('/')) {
      b = b.substring(0, b.length - 1);
    }
    return '$b/chat/completions';
  }

  Map<String, dynamic> toJson() => {
    'id': id,
    'type': type,
    'name': name,
    'baseUrl': baseUrl,
    'key': key,
    'models': models,
    'accountId': accountId,
    'on': on,
  };

  factory KoEngine.fromJson(Map m) => KoEngine(
    id: (m['id'] ?? DateTime.now().microsecondsSinceEpoch).toString(),
    type: (m['type'] ?? 'openai').toString(),
    name: (m['name'] ?? '').toString(),
    baseUrl: (m['baseUrl'] ?? '').toString(),
    key: (m['key'] ?? '').toString(),
    models: (m['models'] ?? '').toString(),
    accountId: (m['accountId'] ?? '').toString(),
    on: m['on'] != false,
  );
}

// 돌려쓰는 한 칸 = 번역기 + 모델 하나
class KoSlot {
  KoSlot(this.engine, this.model);
  final KoEngine engine;
  final String model;
  String get key => '${engine.id}|$model';
  String get label => '${engine.name} · $model';
}

class KoSlotState {
  DateTime? restUntil;
  int strikes = 0; // 연달아 한도에 걸린 횟수 (쉬는 시간이 점점 길어짐)
  int formatFails = 0;
  String? lastError;
  DateTime? lastErrorAt;
  int? avgMs; // 평균 응답 시간
}

class KoAiError implements Exception {
  KoAiError(this.message);
  final String message;
  @override
  String toString() => message;
}

abstract final class KoEngines {
  static const kEngines = 'koTrEngines';
  static const kStats = 'koTrEngineStats';

  static final _dio = Dio(
    BaseOptions(
      connectTimeout: const Duration(seconds: 8),
      receiveTimeout: const Duration(seconds: 45),
      sendTimeout: const Duration(seconds: 15),
    ),
  );

  static List<KoEngine>? _list;

  static List<KoEngine> get list {
    if (_list != null) return _list!;
    final out = <KoEngine>[];
    try {
      final raw = GStorage.setting.get(kEngines, defaultValue: '');
      if (raw is String && raw.isNotEmpty) {
        out.addAll((jsonDecode(raw) as List).whereType<Map>().map(KoEngine.fromJson));
      }
    } catch (_) {}
    if (out.isEmpty) {
      // 예전 버전의 제미나이 키를 옮겨옴
      try {
        final oldKey = (GStorage.setting.get('koTrGeminiKey', defaultValue: '') ?? '').toString().trim();
        if (oldKey.isNotEmpty) {
          final oldModel = (GStorage.setting.get('koTrGeminiModel', defaultValue: '') ?? '').toString().trim();
          out.add(
            KoEngine(
              id: 'gemini-old',
              type: 'gemini',
              name: '제미나이',
              key: oldKey,
              models: oldModel.isEmpty ? 'gemini-3.5-flash-lite' : oldModel,
            ),
          );
          _list = out;
          save();
        }
      } catch (_) {}
    }
    return _list = out;
  }

  static void save() {
    try {
      GStorage.setting.put(kEngines, jsonEncode(list.map((e) => e.toJson()).toList()));
    } catch (_) {}
  }

  static bool get any => list.any((e) => e.usable);

  static List<KoSlot> get slots => [
    for (final e in list)
      if (e.usable)
        for (final m in e.modelList) KoSlot(e, m),
  ];

  // ── 상태 (쉬는 중, 오류, 오늘 사용 횟수) ──
  static final Map<String, KoSlotState> _state = {};
  static KoSlotState stateOf(String slotKey) => _state[slotKey] ??= KoSlotState();

  static bool resting(KoSlot s) {
    final u = _state[s.key]?.restUntil;
    return u != null && DateTime.now().isBefore(u);
  }

  static String _today() {
    final d = DateTime.now();
    return '${d.year}-${d.month}-${d.day}';
  }

  static Map<String, int>? _counts;
  static String _countsDay = '';
  static Timer? _saveTimer;

  static Map<String, int> get counts {
    final t = _today();
    if (_counts == null) {
      try {
        final raw = GStorage.setting.get(kStats, defaultValue: '');
        if (raw is String && raw.isNotEmpty) {
          final m = jsonDecode(raw) as Map;
          if (m['day'] == t) {
            _counts = (m['n'] as Map).map((k, v) => MapEntry(k.toString(), (v as num).toInt()));
          }
        }
      } catch (_) {}
      _counts ??= {};
      _countsDay = t;
    }
    if (_countsDay != t) {
      _counts = {};
      _countsDay = t;
    }
    return _counts!;
  }

  static void _count(String slotKey) {
    counts[slotKey] = (counts[slotKey] ?? 0) + 1;
    _saveTimer ??= Timer(const Duration(seconds: 10), () {
      _saveTimer = null;
      try {
        GStorage.setting.put(kStats, jsonEncode({'day': _countsDay, 'n': _counts}));
      } catch (_) {}
    });
  }

  static int todayCount(KoSlot s) => counts[s.key] ?? 0;

  static void _rest(KoSlot s, Duration d, String why) {
    final st = stateOf(s.key)
      ..restUntil = DateTime.now().add(d)
      ..lastError = why
      ..lastErrorAt = DateTime.now();
    st.formatFails = 0;
  }

  static void _ok(KoSlot s) {
    final st = stateOf(s.key);
    st
      ..strikes = 0
      ..formatFails = 0
      ..restUntil = null;
  }

  // 실패 원인에 따라 쉬는 시간 정하기
  static void _fail(KoSlot s, Object e) {
    final st = stateOf(s.key);
    if (e is DioException) {
      final code = e.response?.statusCode;
      final body = e.response?.data?.toString() ?? '';
      if (code == 429) {
        st.strikes++;
        // 1분 → 5분 → 30분 → 2시간 → 6시간
        const steps = [1, 5, 30, 120, 360];
        final m = steps[(st.strikes - 1) < steps.length ? st.strikes - 1 : steps.length - 1];
        final daily = body.toLowerCase().contains('per day') || body.toLowerCase().contains('perday');
        _rest(s, Duration(minutes: daily ? (m < 60 ? 60 : m) : m), '사용 한도 초과 (429)${daily ? ' · 하루 한도' : ''}${_serverMsg(e)}');
        return;
      }
      if (code == 401 || code == 403) {
        _rest(s, const Duration(hours: 6), '키가 잘못됐거나 권한 없음 ($code)${_serverMsg(e)}');
        return;
      }
      if (code == 404) {
        _rest(s, const Duration(hours: 6), '모델 이름을 찾을 수 없음 (404)${_serverMsg(e)}');
        return;
      }
      if (code == 400) {
        _rest(s, const Duration(minutes: 10), '요청 오류 (400)${_serverMsg(e)}');
        return;
      }
      if (code == 413) {
        _rest(s, const Duration(minutes: 1), '한 번에 보낸 양이 너무 많음 (413)${_serverMsg(e)}');
        return;
      }
      if (code != null) {
        _rest(s, const Duration(minutes: 1), '서버 오류 ($code)${_serverMsg(e)}');
        return;
      }
      _rest(s, const Duration(seconds: 30), '연결 실패');
      return;
    }
    // 답 형식이 이상한 경우: 한 번이면 30초, 두 번 연달아면 5분 쉼 (계속 시간 낭비 안 하게)
    st.formatFails++;
    final why = '답 형식 오류: ${_short(e.toString())}';
    final n = st.formatFails;
    _rest(s, Duration(seconds: n >= 2 ? 300 : 30), why);
    st.formatFails = n;
  }

  static String _short(String s) => s.length > 80 ? s.substring(0, 80) : s;

  // 서버가 보낸 오류 이유 (JSON 의 error.message 등)
  static String _serverMsg(DioException e) {
    final d = e.response?.data;
    try {
      final j = d is String ? jsonDecode(d) : d;
      if (j is Map) {
        final err = j['error'];
        final m = err is Map ? err['message'] : (j['message'] ?? err);
        if (m != null) return ' — ${_short(m.toString())}';
      }
      if (j is List && j.isNotEmpty && j.first is Map) {
        final m = ((j.first as Map)['error'] as Map?)?['message'];
        if (m != null) return ' — ${_short(m.toString())}';
      }
    } catch (_) {}
    return d == null ? '' : ' — ${_short(d.toString())}';
  }

  static String? get lastError {
    KoSlotState? best;
    for (final st in _state.values) {
      if (st.lastErrorAt != null && (best == null || st.lastErrorAt!.isAfter(best.lastErrorAt!))) {
        best = st;
      }
    }
    return best?.lastError;
  }

  // ── 돌려가며 묻기 ──
  // run 이 실패하면(예외) 다음 칸으로. 모두 실패하면 KoAiError.
  static Future<(T, KoSlot)> rotate<T>(
    Future<T> Function(KoSlot slot) run,
  ) async {
    final all = slots;
    if (all.isEmpty) throw KoAiError('AI 번역기가 없어요');
    final ready = all.where((s) => !resting(s)).toList();
    if (ready.isEmpty) throw KoAiError('모든 AI 번역기가 쉬는 중');
    Object? last;
    for (final s in ready) {
      final sw = Stopwatch()..start();
      try {
        final r = await run(s);
        _ok(s);
        final st = stateOf(s.key);
        final ms = sw.elapsedMilliseconds;
        st.avgMs = st.avgMs == null ? ms : (st.avgMs! * 0.7 + ms * 0.3).round();
        return (r, s);
      } catch (e) {
        last = e;
        _fail(s, e);
      }
    }
    throw KoAiError('모든 AI 번역기 실패: ${last is DioException ? (last.response?.statusCode ?? '연결') : last}');
  }

  static final Set<String> _noThinking = {};
  static final Set<String> _noReasoningParam = {};

  // 한 칸에게 묻기 (system + user → 답 글자)
  static Future<String> ask(
    KoSlot s,
    String system,
    String user, {
    bool json = false,
    double temperature = 0.3,
  }) async {
    _count(s.key);
    if (s.engine.type == 'gemini') return _gemini(s, system, user, json, temperature);
    return _openai(s, system, user, temperature);
  }

  static Future<String> _gemini(
    KoSlot s,
    String system,
    String user,
    bool json,
    double temperature,
  ) async {
    final model = s.model;
    final isGemma = model.toLowerCase().contains('gemma');
    final body = {
      // Gemma 는 system 지시를 따로 못 받아서 본문 앞에 붙임
      if (!isGemma)
        'systemInstruction': {
          'parts': [
            {'text': system},
          ],
        },
      'contents': [
        {
          'role': 'user',
          'parts': [
            {'text': isGemma ? '$system\n\n$user' : user},
          ],
        },
      ],
      'generationConfig': {
        'temperature': temperature,
        if (json && !isGemma) 'responseMimeType': 'application/json',
        if (!isGemma && !_noThinking.contains(model))
          'thinkingConfig': {
            'thinkingLevel': model.contains('3.8') && !model.contains('lite') ? 'low' : 'minimal',
          },
      },
    };
    Response<String> res;
    try {
      res = await _dio.post<String>(
        'https://generativelanguage.googleapis.com/v1beta/models/$model:generateContent',
        data: jsonEncode(body),
        options: Options(
          contentType: 'application/json',
          responseType: ResponseType.plain,
          headers: {'x-goog-api-key': s.engine.key.trim()},
        ),
      );
    } on DioException catch (e) {
      final msg = e.response?.data?.toString() ?? '';
      if (e.response?.statusCode == 400 &&
          msg.toLowerCase().contains('think') &&
          !_noThinking.contains(model)) {
        _noThinking.add(model);
        return _gemini(s, system, user, json, temperature);
      }
      rethrow;
    }
    final j = jsonDecode(res.data!) as Map<String, dynamic>;
    final parts = (((j['candidates'] as List).first as Map)['content'] as Map)['parts'] as List;
    return parts
        .where((p) => p is Map && p['thought'] != true)
        .map((p) => (p as Map)['text'] ?? '')
        .join();
  }

  static Future<String> _openai(
    KoSlot s,
    String system,
    String user,
    double temperature,
  ) async {
    // 답 길이 상한: 보낸 글 길이에 맞게 (안 정하면 Groq 등은 모델 최대치로 계산해서 한도 초과가 남)
    final est = (system.length ~/ 4) + user.length * 2 + 256;
    final maxTokens = est < 512 ? 512 : (est > 6000 ? 6000 : est);
    final url = s.engine.chatUrl;
    final m = s.model.toLowerCase();
    // Groq 의 Qwen 은 '생각하기'를 끔 (빠르고 토큰 절약)
    final noThink = url.contains('groq.com') && m.contains('qwen') && !_noReasoningParam.contains(s.key);
    Response<String> res;
    try {
      res = await _dio.post<String>(
        url,
        data: jsonEncode({
          'model': s.model,
          'messages': [
            {'role': 'system', 'content': system},
            {'role': 'user', 'content': user},
          ],
          'temperature': temperature,
          'max_tokens': maxTokens,
          'stream': false,
          if (noThink) 'reasoning_effort': 'none',
        }),
        options: Options(
          contentType: 'application/json',
          responseType: ResponseType.plain,
          headers: {'Authorization': 'Bearer ${s.engine.key.trim()}'},
        ),
      );
    } on DioException catch (e) {
      final body = e.response?.data?.toString().toLowerCase() ?? '';
      if (noThink && e.response?.statusCode == 400 && body.contains('reasoning')) {
        _noReasoningParam.add(s.key);
        return _openai(s, system, user, temperature);
      }
      rethrow;
    }
    final j = jsonDecode(res.data!) as Map<String, dynamic>;
    final msg = ((j['choices'] as List).first as Map)['message'] as Map;
    final c = msg['content'];
    var text = c is String
        ? c
        : c is List
        ? c.map((p) => p is Map ? (p['text'] ?? '') : '').join()
        : '';
    // 생각하는 모델(Qwen 등)의 <think> 부분 지우기
    text = text.replaceAll(RegExp(r'<think>[\s\S]*?</think>'), '');
    final i = text.indexOf('</think>');
    if (i >= 0) text = text.substring(i + 8);
    return text.trim();
  }

  // 번역에 못 쓰는 모델(음성·이미지·유료 전용·종료된 것) 거르기
  static final _geminiSkip = RegExp(
    r'(live|tts|transcribe|image|banana|embed|aqa|imagen|veo|-pro|argon|omni|robotics|computer-use|audio|learnlm|thinking-exp|^gemini-1\.|^gemini-2\.)',
    caseSensitive: false,
  );
  static final _openaiSkip = RegExp(
    r'(whisper|tts|guard|embed|playai|orpheus|moderation|rerank|compound|transcri|speech|image|flux|stable-diffusion)',
    caseSensitive: false,
  );

  static bool usableModel(KoEngine e, String m) =>
      e.type == 'gemini' ? !_geminiSkip.hasMatch(m) : !_openaiSkip.hasMatch(m);

  // 모델 옆에 붙일 설명 (무료 여부·대략 한도)
  static String? modelNote(KoEngine e, String m) {
    final x = m.toLowerCase();
    if (e.type == 'gemini') {
      if (x.contains('gemma')) {
        return x.contains('e2b') || x.contains('e4b') || (x.contains('1b') && !x.contains('31b'))
            ? '무료 · 작은 모델(품질 낮음)'
            : '무료';
      }
      if (x.contains('flash-lite')) return '무료 · 하루 약 500회';
      if (x.contains('flash')) return '무료 · 하루 약 20회 (적음)';
      return null;
    }
    if (e.chatUrl.contains('openrouter.ai')) {
      return x.endsWith(':free') ? '무료 · 하루 50회' : '유료';
    }
    return null;
  }

  // 설정 화면: 모델 목록 불러오기
  static Future<List<String>> fetchModels(KoEngine e) async {
    final all = await _fetchModelsRaw(e);
    return all.where((m) => usableModel(e, m)).toList();
  }

  static Future<List<String>> _fetchModelsRaw(KoEngine e) async {
    if (e.type == 'gemini') {
      final res = await _dio.get<String>(
        'https://generativelanguage.googleapis.com/v1beta/models',
        queryParameters: {'pageSize': 200},
        options: Options(
          responseType: ResponseType.plain,
          headers: {'x-goog-api-key': e.key.trim()},
        ),
      );
      final j = jsonDecode(res.data!) as Map;
      return ((j['models'] as List?) ?? [])
          .whereType<Map>()
          .where((m) => ((m['supportedGenerationMethods'] as List?) ?? []).contains('generateContent'))
          .map((m) => m['name'].toString().replaceFirst('models/', ''))
          .toList();
    }
    final url = e.type == 'cloudflare'
        ? 'https://api.cloudflare.com/client/v4/accounts/${e.accountId.trim()}/ai/models/search?task=Text%20Generation&per_page=100'
        : '${e.chatUrl.replaceFirst(RegExp(r'/chat/completions$'), '')}/models';
    final res = await _dio.get<String>(
      url,
      options: Options(
        responseType: ResponseType.plain,
        headers: {'Authorization': 'Bearer ${e.key.trim()}'},
      ),
    );
    final j = jsonDecode(res.data!);
    final items = j is Map ? (j['data'] ?? j['result'] ?? []) as List : j as List;
    final out = items
        .whereType<Map>()
        .map((m) => (m['id'] ?? m['name'] ?? '').toString())
        .where((s) => s.isNotEmpty)
        .toList()
      ..sort();
    return out;
  }

  // 설정 화면: 연결 테스트 (모델마다)
  static Future<String> test(KoEngine e) async {
    final sb = StringBuffer();
    for (final m in e.modelList) {
      final s = KoSlot(e, m);
      final sw = Stopwatch()..start();
      try {
        final r = await ask(s, '중국어를 한국어 인터넷 말투로 짧게 번역하라. 번역문만 답하라.', '前方高能，awsl');
        _ok(s);
        sb.writeln('✓ $m (${sw.elapsedMilliseconds}ms)\n   前方高能，awsl → ${r.trim()}');
      } catch (err) {
        _fail(s, err);
        sb.writeln('✗ $m: ${stateOf(s.key).lastError ?? err}');
      }
    }
    return sb.toString().trim();
  }
}
