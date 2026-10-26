// 한국어 번역 (PiliPlus 개조)
// 탄막·자막·라이브 탄막의 중국어를 한국어로 바꿔서 보여주기 위한 번역 모듈.
// · 구글 번역: 무료, 키 필요 없음, 빠름
// · 제미나이: 무료 키 필요. 밈·은어를 뜻이 통하게 의역 (탄막에 특히 좋음)
// 번역 결과는 메모리에 캐시해서 같은 문장은 다시 번역하지 않음.
import 'dart:async';
import 'dart:collection';
import 'dart:convert';

import 'package:PiliPlus/utils/storage.dart';
import 'package:dio/dio.dart';
import 'package:flutter/foundation.dart' show ValueNotifier;

abstract final class KoTr {
  // ── 설정 키 ──
  static const kEnable = 'koTrEnable';
  static const kDm = 'koTrDm';
  static const kDmHide = 'koTrDmHide';
  static const kSub = 'koTrSub';
  static const kSubOrig = 'koTrSubOrig';
  static const kLive = 'koTrLive';
  static const kEngine = 'koTrEngine'; // 'google' | 'gemini'
  static const kKey = 'koTrGeminiKey';
  static const kModel = 'koTrGeminiModel';
  static const kGlossary = 'koTrGlossary';
  static const kText = 'koTrText'; // 제목·소개·게시글
  static const kReply = 'koTrReply'; // 댓글
  static const defaultModel = 'gemini-3.5-flash-lite';

  static T _get<T>(String k, T def) {
    try {
      return GStorage.setting.get(k, defaultValue: def) as T;
    } catch (_) {
      return def;
    }
  }

  static bool get enabled => _get(kEnable, true);
  static bool get dmOn => enabled && _get(kDm, true);
  static bool get dmHideUntranslated => _get(kDmHide, false);
  static bool get subOn => enabled && _get(kSub, true);
  static bool get subShowOrig => _get(kSubOrig, true);
  static bool get liveOn => enabled && _get(kLive, true);
  static bool get textOn => enabled && _get(kText, true);
  static bool get replyOn => enabled && _get(kReply, true);
  static String get engine => _get(kEngine, 'google');
  static String get geminiKey => (_get(kKey, '') as String).trim();
  static String get geminiModel {
    final m = (_get(kModel, '') as String).trim();
    return m.isEmpty ? defaultModel : m;
  }

  static bool get useGemini => engine == 'gemini' && geminiKey.isNotEmpty;

  // "中文=한국어" 줄들
  static List<MapEntry<String, String>> get glossary {
    final out = <MapEntry<String, String>>[];
    for (final l in (_get(kGlossary, '') as String).split('\n')) {
      final i = l.indexOf('=');
      if (i <= 0 || i >= l.length - 1) continue;
      final z = l.substring(0, i).trim();
      final k = l.substring(i + 1).trim();
      if (z.isNotEmpty && k.isNotEmpty) out.add(MapEntry(z, k));
    }
    out.sort((a, b) => b.key.length.compareTo(a.key.length));
    return out;
  }

  // ── 캐시 ──
  static final LinkedHashMap<String, String> _cache = LinkedHashMap();
  static final Set<String> _pending = HashSet();
  static const _cacheMax = 30000;

  static Timer? _notifyTimer;

  static void _put(String src, String ko) {
    _cache.remove(src);
    _cache[src] = ko;
    if (_cache.length > _cacheMax) {
      _cache.remove(_cache.keys.first);
    }
    _notifyTimer ??= Timer(const Duration(milliseconds: 100), () {
      _notifyTimer = null;
      updates.value++;
    });
  }

  static void clearCache() {
    _cache.clear();
    updates.value++;
  }

  // 번역이 새로 들어올 때마다 1씩 올라감 → 화면의 글자들이 다시 그려짐
  static final ValueNotifier<int> updates = ValueNotifier(0);

  // 댓글별 '원문 보기' 상태 (댓글 id)
  static final Set<Object> showOrig = HashSet();

  // ── 화면 글자(제목·댓글 등): 0.15초 모아서 한 번에 번역 ──
  static final List<String> _wantBuf = [];
  static Timer? _wantTimer;

  static void want(String s) {
    if (!needs(s) || _cache.containsKey(s) || _pending.contains(s)) return;
    _wantBuf.add(s);
    _wantTimer ??= Timer(const Duration(milliseconds: 150), () {
      _wantTimer = null;
      final xs = List.of(_wantBuf);
      _wantBuf.clear();
      translateAll(xs, chunk: useGemini ? 30 : 40);
    });
  }

  static bool isTranslated(String s) => _cache.containsKey(s);

  // 화면에 보여줄 탄막 글자: 번역이 있으면 번역, 없으면 원문
  static String show(String s) => _cache[s] ?? s;

  // 중국어(한자)가 하나라도 있어야 번역 대상
  static final _cjk = RegExp(r'[㐀-䶿一-鿿]');
  // 제미나이 모드에선 '2333', 'hhh' 같은 탄막 밈도 번역 대상
  static final _meme = RegExp(r'^(2{1}3{2,}|h{3,}|6{3,}|8{3,}|[xX]{2,}|awsl|yyds|nb|xswl|dbq|kswl|zqsg)$', caseSensitive: false);

  static bool needs(String s) {
    final t = s.trim();
    if (t.isEmpty || t.length > 1500) return false;
    if (_cjk.hasMatch(t)) return true;
    return useGemini && _meme.hasMatch(t);
  }

  // ── 일괄 번역 ──
  static final _dio = Dio(
    BaseOptions(
      connectTimeout: const Duration(seconds: 8),
      receiveTimeout: const Duration(seconds: 25),
      sendTimeout: const Duration(seconds: 10),
    ),
  );

  static int _running = 0;
  static final Queue<List<String>> _jobs = Queue();
  static final List<Completer<void>> _idleWaiters = [];

  // texts 를 순서대로(앞쪽이 먼저) 번역해서 캐시에 넣음. 이미 된 것/진행 중인 것은 건너뜀.
  static Future<void> translateAll(List<String> texts, {int chunk = 0}) {
    final todo = <String>[];
    final seen = HashSet<String>();
    for (final t in texts) {
      if (!needs(t) || _cache.containsKey(t) || _pending.contains(t) || !seen.add(t)) continue;
      todo.add(t);
    }
    if (todo.isEmpty) return Future.value();
    _pending.addAll(todo);
    final size = chunk > 0 ? chunk : (useGemini ? 60 : 80);
    for (var i = 0; i < todo.length; i += size) {
      _jobs.add(todo.sublist(i, i + size > todo.length ? todo.length : i + size));
    }
    final c = Completer<void>();
    _idleWaiters.add(c);
    _pump();
    return c.future;
  }

  static void _pump() {
    final maxConc = useGemini ? 2 : 3;
    while (_running < maxConc && _jobs.isNotEmpty) {
      final job = _jobs.removeFirst();
      _running++;
      _batch(job).then((res) {
        for (var i = 0; i < job.length; i++) {
          final r = i < res.length ? res[i] : null;
          if (r != null && r.trim().isNotEmpty) _put(job[i], r.trim());
        }
      }).catchError((_) {}).whenComplete(() {
        _pending.removeAll(job);
        _running--;
        if (_running == 0 && _jobs.isEmpty) {
          for (final w in _idleWaiters) {
            if (!w.isCompleted) w.complete();
          }
          _idleWaiters.clear();
        }
        _pump();
      });
    }
  }

  // ── 라이브 탄막: 0.3초 모아서 한 번에 ──
  static final List<(String, Completer<String>)> _liveBuf = [];
  static Timer? _liveTimer;

  static Future<String> live(String s) {
    if (!liveOn || !needs(s)) return Future.value(s);
    final hit = _cache[s];
    if (hit != null) return Future.value(hit);
    final c = Completer<String>();
    _liveBuf.add((s, c));
    _liveTimer ??= Timer(const Duration(milliseconds: 300), _flushLive);
    // 번역이 늦으면 4초 뒤 원문으로라도 보여줌
    return c.future.timeout(const Duration(seconds: 4), onTimeout: () => s);
  }

  static void _flushLive() {
    _liveTimer = null;
    final buf = List.of(_liveBuf);
    _liveBuf.clear();
    if (buf.isEmpty) return;
    final texts = buf.map((e) => e.$1).toSet().toList();
    _batch(texts).then((res) {
      for (var i = 0; i < texts.length; i++) {
        final r = i < res.length ? res[i] : null;
        if (r != null && r.trim().isNotEmpty) _put(texts[i], r.trim());
      }
    }).catchError((_) {}).whenComplete(() {
      for (final (s, c) in buf) {
        if (!c.isCompleted) c.complete(_cache[s] ?? s);
      }
    });
  }

  // ── 엔진 ──
  static Future<List<String?>> _batch(List<String> xs) async {
    if (useGemini) {
      try {
        return await _gemini(xs);
      } catch (_) {
        // 제미나이 실패(한도 초과 등) → 구글로
      }
    }
    return _googleSafe(xs);
  }

  static String _applyGlossary(String s, List<MapEntry<String, String>> gl) {
    var r = s;
    for (final e in gl) {
      r = r.replaceAll(e.key, ' ${e.value} ');
    }
    return r.replaceAll(RegExp(r' {2,}'), ' ').trim();
  }

  // 여러 줄을 한 번에 보내고 줄 단위로 나눠 받음. 줄 수가 안 맞으면 잘게 나눠 다시 시도.
  static Future<List<String?>> _googleSafe(List<String> xs) async {
    // 여러 줄짜리(소개·댓글)는 줄 구분이 섞이지 않게 하나씩, 한 줄짜리는 묶어서
    final out = List<String?>.filled(xs.length, null);
    final singles = <int>[];
    final gl = glossary;
    for (var i = 0; i < xs.length; i++) {
      if (xs[i].contains('\n')) {
        try {
          out[i] = await _google(_applyGlossary(xs[i], gl));
        } catch (_) {}
      } else {
        singles.add(i);
      }
    }
    if (singles.isNotEmpty) {
      final r = await _googleLines(singles.map((i) => xs[i]).toList());
      for (var k = 0; k < singles.length; k++) {
        out[singles[k]] = k < r.length ? r[k] : null;
      }
    }
    return out;
  }

  static Future<List<String?>> _googleLines(List<String> xs) async {
    final gl = glossary;
    final src = xs.map((e) => _applyGlossary(e, gl)).toList();
    try {
      final out = await _google(src.join('\n'));
      final lines = out.split('\n');
      if (lines.length == src.length) return lines;
    } catch (_) {}
    if (xs.length <= 1) {
      try {
        return [await _google(src.first)];
      } catch (_) {
        return [null];
      }
    }
    final mid = xs.length ~/ 2;
    final a = await _googleLines(xs.sublist(0, mid));
    final b = await _googleLines(xs.sublist(mid));
    return [...a, ...b];
  }

  static Future<String> _google(String text) async {
    final res = await _dio.post<String>(
      'https://translate.googleapis.com/translate_a/single',
      queryParameters: {'client': 'gtx', 'sl': 'auto', 'tl': 'ko', 'dt': 't'},
      data: 'q=${Uri.encodeQueryComponent(text)}',
      options: Options(
        contentType: 'application/x-www-form-urlencoded;charset=utf-8',
        responseType: ResponseType.plain,
        headers: {'User-Agent': 'Mozilla/5.0'},
      ),
    );
    final j = jsonDecode(res.data!) as List;
    final sb = StringBuffer();
    for (final seg in (j[0] as List)) {
      if (seg is List && seg.isNotEmpty && seg[0] is String) sb.write(seg[0]);
    }
    final out = sb.toString();
    if (out.trim().isEmpty) throw Exception('empty');
    return out.trimRight();
  }

  static Future<List<String?>> _gemini(List<String> xs) async {
    final gl = glossary;
    final joined = xs.join('\n');
    final used = gl.where((e) => joined.contains(e.key)).toList();
    final system = StringBuffer()
      ..write('너는 중국 동영상 사이트 빌리빌리의 탄막(화면에 흘러가는 짧은 댓글)과 자막을 한국어로 번역한다. ')
      ..write('한국 인터넷 커뮤니티 말투로 짧고 자연스럽게. 밈·은어·말장난은 직역하지 말고 한국 사람이 뜻을 바로 알 수 있게 풀어서 번역하라 ')
      ..write('(예: 2333→ㅋㅋㅋ, 前方高能→곧 대박 장면, awsl→귀여워 죽겠다, yyds→갓, 泪目→눈물 난다). ')
      ..write('영상 제목·댓글·게시글도 들어온다. [doge] 같은 대괄호 이모티콘, @닉네임, 링크, BV번호는 그대로 둬라. 줄바꿈은 유지하라. 입력 배열과 같은 길이·같은 순서의 JSON 문자열 배열로만 답하라.');
    if (used.isNotEmpty) {
      system.write('\n\n[용어집 - 반드시 이렇게 번역]\n');
      for (final e in used) {
        system.write('${e.key} → ${e.value}\n');
      }
    }
    final body = {
      'systemInstruction': {
        'parts': [
          {'text': system.toString()},
        ],
      },
      'contents': [
        {
          'role': 'user',
          'parts': [
            {'text': jsonEncode(xs)},
          ],
        },
      ],
      'generationConfig': {
        'temperature': 0.3,
        'responseMimeType': 'application/json',
        if (!_noThinking.contains(geminiModel))
          'thinkingConfig': {
            'thinkingLevel':
                geminiModel.contains('3.8') && !geminiModel.contains('lite') ? 'low' : 'minimal',
          },
      },
    };
    Response<String> res;
    try {
      res = await _dio.post<String>(
        'https://generativelanguage.googleapis.com/v1beta/models/$geminiModel:generateContent',
        data: jsonEncode(body),
        options: Options(
          contentType: 'application/json',
          responseType: ResponseType.plain,
          headers: {'x-goog-api-key': geminiKey},
        ),
      );
    } on DioException catch (e) {
      final msg = e.response?.data?.toString() ?? '';
      if (e.response?.statusCode == 400 && msg.toLowerCase().contains('think')) {
        _noThinking.add(geminiModel);
        return _gemini(xs);
      }
      rethrow;
    }
    final j = jsonDecode(res.data!) as Map<String, dynamic>;
    final parts = (((j['candidates'] as List).first as Map)['content'] as Map)['parts'] as List;
    final text = parts
        .where((p) => p is Map && p['thought'] != true)
        .map((p) => (p as Map)['text'] ?? '')
        .join();
    final a = text.indexOf('[');
    final b = text.lastIndexOf(']');
    if (a < 0 || b <= a) throw Exception('format');
    final arr = jsonDecode(text.substring(a, b + 1)) as List;
    if (arr.length != xs.length) {
      // 길이가 안 맞으면 반씩 나눠 다시 (작게 나눌수록 잘 맞음)
      if (xs.length <= 2) throw Exception('length');
      final mid = xs.length ~/ 2;
      return [...await _gemini(xs.sublist(0, mid)), ...await _gemini(xs.sublist(mid))];
    }
    return arr.map((e) => e?.toString()).toList();
  }

  static final Set<String> _noThinking = HashSet();

  // 설정 화면의 [번역 테스트]
  static Future<String> test() async {
    const samples = ['前方高能', '233333', '这也太好笑了吧', 'awsl', '泪目了家人们'];
    final sw = Stopwatch()..start();
    final sb = StringBuffer();
    try {
      final r = await _batch(samples);
      for (var i = 0; i < samples.length; i++) {
        sb.writeln('${samples[i]} → ${r.length > i ? r[i] ?? '(실패)' : '(실패)'}');
      }
      sb.write('\n${useGemini ? '제미나이' : '구글'} · ${sw.elapsedMilliseconds}ms');
      if (engine == 'gemini' && geminiKey.isEmpty) sb.write('\n(제미나이 키가 없어서 구글로 번역했어요)');
    } catch (e) {
      sb.write('실패: $e');
    }
    return sb.toString();
  }
}
