// 한국어 번역 (PiliPlus 개조)
// 탄막·자막·라이브 탄막·제목·댓글의 중국어를 한국어로 바꿔서 보여주기 위한 번역 모듈.
// · 구글 번역: 무료, 키 필요 없음, 빠름
// · 제미나이: 무료 키 필요. 밈·은어를 뜻이 통하게 의역 (탄막에 특히 좋음)
// 번역 결과는 메모리에 캐시해서 같은 문장은 다시 번역하지 않음.
// 직접 고친 번역(문장 단위)과 용어집(공통 + 작품별)은 저장해 두고 계속 씀.
import 'dart:async';
import 'dart:collection';
import 'dart:convert';

import 'package:PiliPlus/utils/storage.dart';
import 'package:dio/dio.dart';
import 'package:flutter/foundation.dart' show ValueNotifier;

// 작품별 용어집
class KoWork {
  KoWork({
    required this.name,
    this.detect = '',
    this.glossary = '',
    this.mode = 0,
  });

  String name;
  // 영상 제목·태그에 이 말들 중 하나가 있으면 자동 적용 (쉼표/줄바꿈으로 구분)
  String detect;
  // "中文=한국어" 줄들
  String glossary;
  // 0: 자동(제목·태그로 판단) 1: 항상 적용 2: 끔
  int mode;

  List<String> get detectWords {
    final ws = detect
        .split(RegExp(r'[,，\n]'))
        .map((e) => e.trim())
        .where((e) => e.isNotEmpty)
        .toList();
    if (ws.isEmpty && name.trim().isNotEmpty) ws.add(name.trim());
    return ws;
  }

  Map<String, dynamic> toJson() => {
    'name': name,
    'detect': detect,
    'glossary': glossary,
    'mode': mode,
  };

  factory KoWork.fromJson(Map m) => KoWork(
    name: (m['name'] ?? '').toString(),
    detect: (m['detect'] ?? '').toString(),
    glossary: (m['glossary'] ?? '').toString(),
    mode: (m['mode'] is int) ? m['mode'] as int : 0,
  );
}

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
  static const kGlossary = 'koTrGlossary'; // 공통 용어집
  static const kText = 'koTrText'; // 제목·소개·게시글
  static const kReply = 'koTrReply'; // 댓글
  static const kSearch = 'koTrSearch'; // 검색 화면(인기 검색어·자동완성 등)
  static const kSearchKo = 'koTrSearchKo'; // 한국어로 검색하면 중국어로 바꿔서
  static const kOverrides = 'koTrOverrides'; // 직접 고친 번역 {원문: 번역}
  static const kWorks = 'koTrWorks'; // 작품별 용어집
  static const defaultModel = 'gemini-3.5-flash-lite';

  static T _get<T>(String k, T def) {
    try {
      final v = GStorage.setting.get(k, defaultValue: def);
      return v is T ? v : def;
    } catch (_) {
      return def;
    }
  }

  static void _set(String k, Object v) {
    try {
      GStorage.setting.put(k, v);
    } catch (_) {}
  }

  static bool get enabled => _get(kEnable, true);
  static bool get dmOn => enabled && _get(kDm, true);
  static bool get dmHideUntranslated => _get(kDmHide, false);
  static bool get subOn => enabled && _get(kSub, true);
  static bool get subShowOrig => _get(kSubOrig, true);
  static bool get liveOn => enabled && _get(kLive, true);
  static bool get textOn => enabled && _get(kText, true);
  static bool get replyOn => enabled && _get(kReply, true);
  static bool get searchOn => enabled && _get(kSearch, true);
  static bool get searchKoOn => enabled && _get(kSearchKo, true);
  static String get engine => _get(kEngine, 'google');
  static String get geminiKey => _get(kKey, '').trim();
  static String get geminiModel {
    final m = _get(kModel, '').trim();
    return m.isEmpty ? defaultModel : m;
  }

  static bool get useGemini => engine == 'gemini' && geminiKey.isNotEmpty;
  static bool get hasGeminiKey => geminiKey.isNotEmpty;

  // ── 용어집 ──
  static List<MapEntry<String, String>> parseGlossary(String text) {
    final out = <MapEntry<String, String>>[];
    for (final l in text.split('\n')) {
      final i = l.indexOf('=');
      if (i <= 0 || i >= l.length - 1) continue;
      final z = l.substring(0, i).trim();
      final k = l.substring(i + 1).trim();
      if (z.isNotEmpty && k.isNotEmpty) out.add(MapEntry(z, k));
    }
    return out;
  }

  static String get commonGlossaryText => _get(kGlossary, '');

  // 번역에 쓰는 용어집: 공통 + 지금 적용 중인 작품들 (긴 단어 먼저)
  static List<MapEntry<String, String>> get glossary {
    final map = <String, String>{};
    for (final w in activeWorks) {
      for (final e in parseGlossary(w.glossary)) {
        map[e.key] = e.value;
      }
    }
    // 공통 용어집이 작품 용어집보다 우선하지 않도록 작품 쪽을 나중에 덮어씀
    final common = parseGlossary(commonGlossaryText);
    final out = <String, String>{for (final e in common) e.key: e.value, ...map};
    final list = out.entries.toList()
      ..sort((a, b) => b.key.length.compareTo(a.key.length));
    return list;
  }

  // ── 작품별 용어집 ──
  static List<KoWork>? _works;

  static List<KoWork> get works {
    if (_works != null) return _works!;
    try {
      final raw = _get(kWorks, '');
      if (raw.isNotEmpty) {
        _works = (jsonDecode(raw) as List)
            .whereType<Map>()
            .map(KoWork.fromJson)
            .toList();
      }
    } catch (_) {}
    return _works ??= [];
  }

  static void saveWorks() {
    _set(kWorks, jsonEncode(works.map((e) => e.toJson()).toList()));
    _refreshActive();
  }

  // 지금 보고 있는 영상 정보(제목·태그). 영상이 바뀌면 새로 모음.
  static String _ctxKey = '';
  static String _ctxText = '';

  static void addContext(String key, String? text) {
    if (key != _ctxKey) {
      _ctxKey = key;
      _ctxText = '';
    }
    if (text != null && text.isNotEmpty) _ctxText += '\n$text';
    _refreshActive();
  }

  static String get contextText => _ctxText;

  static Set<String> _activeNames = {};

  static bool isActive(KoWork w) {
    if (w.mode == 1) return true;
    if (w.mode == 2) return false;
    final t = _ctxText.toLowerCase();
    if (t.isEmpty) return false;
    return w.detectWords.any((d) => t.contains(d.toLowerCase()));
  }

  static List<KoWork> get activeWorks => works.where(isActive).toList();

  static void _refreshActive() {
    final now = activeWorks;
    final names = now.map((e) => e.name).toSet();
    if (names.length == _activeNames.length && names.containsAll(_activeNames)) {
      return;
    }
    // 새로 적용되거나 빠진 작품의 단어가 들어간 번역은 다시 번역
    final changed = works
        .where((w) => names.contains(w.name) != _activeNames.contains(w.name))
        .expand((w) => parseGlossary(w.glossary).map((e) => e.key))
        .toList();
    _activeNames = names;
    retranslateContaining(changed);
  }

  // 공통 용어집에 한 줄 추가 (같은 중국어가 있으면 바꿈)
  static String _upsertLine(String text, String zh, String ko) {
    final lines = text.split('\n').where((l) {
      final i = l.indexOf('=');
      return l.trim().isNotEmpty && (i <= 0 || l.substring(0, i).trim() != zh);
    }).toList()
      ..add('$zh=$ko');
    return lines.join('\n');
  }

  // target: null 이면 공통 용어집, 아니면 그 작품
  static void addGlossary(String zh, String ko, {KoWork? target}) {
    if (target == null) {
      _set(kGlossary, _upsertLine(commonGlossaryText, zh, ko));
    } else {
      target.glossary = _upsertLine(target.glossary, zh, ko);
      _set(kWorks, jsonEncode(works.map((e) => e.toJson()).toList()));
    }
    retranslateContaining([zh]);
  }

  static void setCommonGlossary(String text) {
    final before = parseGlossary(commonGlossaryText);
    _set(kGlossary, text);
    final after = parseGlossary(text);
    retranslateContaining(_diffKeys(before, after));
  }

  static void setWorkGlossary(KoWork w, String text) {
    final before = parseGlossary(w.glossary);
    w.glossary = text;
    saveWorks();
    retranslateContaining(_diffKeys(before, parseGlossary(text)));
  }

  static List<String> _diffKeys(
    List<MapEntry<String, String>> a,
    List<MapEntry<String, String>> b,
  ) {
    final ma = {for (final e in a) e.key: e.value};
    final mb = {for (final e in b) e.key: e.value};
    return {...ma.keys, ...mb.keys}.where((k) => ma[k] != mb[k]).toList();
  }

  // 이 단어들이 들어간 번역을 버리고 다시 번역
  static void retranslateContaining(List<String> keys) {
    if (keys.isEmpty) return;
    final redo = _cache.keys
        .where((s) => keys.any((k) => s.contains(k)))
        .toList();
    if (redo.isEmpty) return;
    for (final s in redo) {
      _cache.remove(s);
    }
    updates.value++;
    translateAll(redo, chunk: useGemini ? 30 : 40);
  }

  // ── 직접 고친 번역 (문장 단위) ──
  static Map<String, String>? _ov;

  static Map<String, String> get overrides {
    if (_ov != null) return _ov!;
    try {
      final raw = _get(kOverrides, '');
      if (raw.isNotEmpty) {
        _ov = (jsonDecode(raw) as Map).map(
          (k, v) => MapEntry(k.toString(), v.toString()),
        );
      }
    } catch (_) {}
    return _ov ??= {};
  }

  static void setOverride(String src, String? ko) {
    if (ko == null || ko.trim().isEmpty) {
      overrides.remove(src);
    } else {
      overrides[src] = ko.trim();
      _rev[ko.trim()] = src;
    }
    _set(kOverrides, jsonEncode(overrides));
    updates.value++;
    if (ko == null && !_cache.containsKey(src)) want(src);
  }

  static bool hasOverride(String s) => overrides.containsKey(s);

  // ── 캐시 ──
  static final LinkedHashMap<String, String> _cache = LinkedHashMap();
  static final LinkedHashMap<String, String> _rev = LinkedHashMap(); // 번역 → 원문
  static final Set<String> _pending = HashSet();
  static const _cacheMax = 30000;

  static Timer? _notifyTimer;

  static void _put(String src, String ko, {bool viaGoogle = false}) {
    _cache.remove(src);
    _cache[src] = ko;
    if (_cache.length > _cacheMax) {
      _cache.remove(_cache.keys.first);
    }
    _rev.remove(ko);
    _rev[ko] = src;
    if (_rev.length > _cacheMax) {
      _rev.remove(_rev.keys.first);
    }
    if (viaGoogle) {
      if (_viaGoogle.length > 20000) _viaGoogle.clear();
      _viaGoogle.add(src);
    } else {
      _viaGoogle.remove(src);
    }
    _notifyTimer ??= Timer(const Duration(milliseconds: 100), () {
      _notifyTimer = null;
      updates.value++;
    });
  }

  static void clearCache() {
    _cache.clear();
    _viaGoogle.clear();
    updates.value++;
  }

  // 번역이 새로 들어올 때마다 1씩 올라감 → 화면의 글자들이 다시 그려짐
  static final ValueNotifier<int> updates = ValueNotifier(0);

  // 댓글·제목별 '원문 보기' 상태 (댓글 id 또는 제목 글자)
  static final Set<Object> showOrig = HashSet();
  static int origVersion = 0;

  static void toggleOrig(Object id) {
    if (!showOrig.remove(id)) showOrig.add(id);
    origVersion++;
    updates.value++;
  }

  // 제미나이 모드인데 제미나이가 실패해서 구글로 대신 번역된 문장들
  static final Set<String> _viaGoogle = HashSet();
  static bool viaGoogle(String s) => _viaGoogle.contains(s) && !hasOverride(s);
  static String? lastGeminiError;
  static DateTime? lastGeminiErrorAt;

  // ── 화면 글자(제목·댓글 등): 0.15초 모아서 한 번에 번역 ──
  static final List<String> _wantBuf = [];
  static Timer? _wantTimer;

  static void want(String s) {
    if (!needs(s) || isTranslated(s) || _pending.contains(s)) return;
    _wantBuf.add(s);
    _wantTimer ??= Timer(const Duration(milliseconds: 150), () {
      _wantTimer = null;
      final xs = List.of(_wantBuf);
      _wantBuf.clear();
      translateAll(xs, chunk: useGemini ? 30 : 40);
    });
  }

  static bool isTranslated(String s) =>
      overrides.containsKey(s) || _cache.containsKey(s);

  // 화면에 보여줄 글자: 고친 번역 > 번역 > 원문
  static String show(String s) => overrides[s] ?? _cache[s] ?? s;

  // 번역문으로 원문 찾기 (탄막 메뉴에서 '번역 고치기' 할 때)
  static String? origOf(String ko) => _rev[ko];

  // 중국어(한자)가 하나라도 있어야 번역 대상
  static final _cjk = RegExp(r'[㐀-䶿一-鿿]');
  static final _hangul = RegExp(r'[가-힣ㄱ-ㅎㅏ-ㅣ]');
  // 제미나이 모드에선 '2333', 'hhh' 같은 탄막 밈도 번역 대상
  static final _meme = RegExp(
    r'^(2{1}3{2,}|h{3,}|6{3,}|8{3,}|[xX]{2,}|awsl|yyds|nb|xswl|dbq|kswl|zqsg)$',
    caseSensitive: false,
  );

  static bool hasHangul(String s) => _hangul.hasMatch(s);
  static bool hasCjk(String s) => _cjk.hasMatch(s);

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
      if (!needs(t) ||
          isTranslated(t) ||
          _pending.contains(t) ||
          !seen.add(t)) {
        continue;
      }
      todo.add(t);
    }
    if (todo.isEmpty) return Future.value();
    _pending.addAll(todo);
    final size = chunk > 0 ? chunk : (useGemini ? 60 : 80);
    for (var i = 0; i < todo.length; i += size) {
      _jobs.add(
        todo.sublist(i, i + size > todo.length ? todo.length : i + size),
      );
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
      _batch(job)
          .then((res) {
            for (var i = 0; i < job.length; i++) {
              final r = i < res.$1.length ? res.$1[i] : null;
              if (r != null && r.trim().isNotEmpty) {
                _put(job[i], r.trim(), viaGoogle: res.$2);
              }
            }
          })
          .catchError((_) {})
          .whenComplete(() {
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
    if (isTranslated(s)) return Future.value(show(s));
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
    _batch(texts)
        .then((res) {
          for (var i = 0; i < texts.length; i++) {
            final r = i < res.$1.length ? res.$1[i] : null;
            if (r != null && r.trim().isNotEmpty) {
              _put(texts[i], r.trim(), viaGoogle: res.$2);
            }
          }
        })
        .catchError((_) {})
        .whenComplete(() {
          for (final (s, c) in buf) {
            if (!c.isCompleted) c.complete(show(s));
          }
        });
  }

  // ── 엔진 ── 결과: (번역들, 제미나이 대신 구글로 번역했는지)
  static Future<(List<String?>, bool)> _batch(List<String> xs) async {
    if (useGemini) {
      try {
        final r = await _gemini(xs);
        lastGeminiError = null;
        return (r, false);
      } catch (e) {
        // 제미나이 실패(한도 초과 등) → 구글로
        lastGeminiError = _errText(e);
        lastGeminiErrorAt = DateTime.now();
        return (await _googleSafe(xs), true);
      }
    }
    return (await _googleSafe(xs), false);
  }

  static String _errText(Object e) {
    if (e is DioException) {
      final code = e.response?.statusCode;
      if (code == 429) return '사용 한도 초과 (429)';
      if (code == 403 || code == 401) return '키가 잘못됐거나 권한 없음 ($code)';
      if (code == 404) return '모델 이름을 찾을 수 없음 (404)';
      if (code != null) return '서버 오류 ($code)';
      return '연결 실패';
    }
    final s = e.toString();
    return s.length > 80 ? s.substring(0, 80) : s;
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

  static Future<String> _google(
    String text, {
    String sl = 'auto',
    String tl = 'ko',
  }) async {
    final res = await _dio.post<String>(
      'https://translate.googleapis.com/translate_a/single',
      queryParameters: {'client': 'gtx', 'sl': sl, 'tl': tl, 'dt': 't'},
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

  // 아무 말이나 한국어로 (번역 고치기 창에서 '선택한 원문 뜻 보기')
  static Future<String> quick(String text) => _google(text);

  // 한국어 검색어 → 중국어(간체)
  static Future<String?> toChinese(String ko) async {
    try {
      // 용어집에 있는 한국어는 그 중국어로 먼저 바꿔둠
      var q = ko;
      final rev = glossary.toList()
        ..sort((a, b) => b.value.length.compareTo(a.value.length));
      for (final e in rev) {
        if (q.contains(e.value)) q = q.replaceAll(e.value, e.key);
      }
      if (!hasHangul(q)) return q;
      return (await _google(q, sl: 'ko', tl: 'zh-CN')).trim();
    } catch (_) {
      return null;
    }
  }

  static Future<List<String?>> _gemini(List<String> xs) async {
    final gl = glossary;
    final joined = xs.join('\n');
    final used = gl.where((e) => joined.contains(e.key)).toList();
    final system = StringBuffer()
      ..write(
        '너는 중국 동영상 사이트 빌리빌리의 탄막(화면에 흘러가는 짧은 댓글)과 자막을 한국어로 번역한다. ',
      )
      ..write(
        '한국 인터넷 커뮤니티 말투로 짧고 자연스럽게. 밈·은어·말장난은 직역하지 말고 한국 사람이 뜻을 바로 알 수 있게 풀어서 번역하라 ',
      )
      ..write(
        '(예: 2333→ㅋㅋㅋ, 前方高能→곧 대박 장면, awsl→귀여워 죽겠다, yyds→갓, 泪目→눈물 난다). ',
      )
      ..write(
        '영상 제목·댓글·게시글·검색어도 들어온다. [doge] 같은 대괄호 이모티콘, @닉네임, 링크, BV번호는 그대로 둬라. 줄바꿈은 유지하라. 입력 배열과 같은 길이·같은 순서의 JSON 문자열 배열로만 답하라.',
      );
    final names = activeWorks.map((e) => e.name).toList();
    if (names.isNotEmpty) {
      system.write('\n\n[지금 보는 작품] ${names.join(', ')}');
    }
    if (used.isNotEmpty) {
      system.write('\n\n[용어집 - 반드시 이렇게 번역]\n');
      for (final e in used) {
        system.write('${e.key} → ${e.value}\n');
      }
    }
    final text = await ask(
      system.toString(),
      jsonEncode(xs),
      json: true,
    );
    final a = text.indexOf('[');
    final b = text.lastIndexOf(']');
    if (a < 0 || b <= a) throw Exception('format');
    final arr = jsonDecode(text.substring(a, b + 1)) as List;
    if (arr.length != xs.length) {
      // 길이가 안 맞으면 반씩 나눠 다시 (작게 나눌수록 잘 맞음)
      if (xs.length <= 2) throw Exception('length');
      final mid = xs.length ~/ 2;
      return [
        ...await _gemini(xs.sublist(0, mid)),
        ...await _gemini(xs.sublist(mid)),
      ];
    }
    return arr.map((e) => e?.toString()).toList();
  }

  static final Set<String> _noThinking = HashSet();

  // 제미나이에게 묻기 (번역 고치기·용어집 만들기에서도 씀)
  static Future<String> ask(
    String system,
    String user, {
    bool json = false,
    double temperature = 0.3,
  }) async {
    if (!hasGeminiKey) throw Exception('제미나이 키가 필요해요');
    final model = geminiModel;
    final body = {
      'systemInstruction': {
        'parts': [
          {'text': system},
        ],
      },
      'contents': [
        {
          'role': 'user',
          'parts': [
            {'text': user},
          ],
        },
      ],
      'generationConfig': {
        'temperature': temperature,
        if (json) 'responseMimeType': 'application/json',
        if (!_noThinking.contains(model))
          'thinkingConfig': {
            'thinkingLevel': model.contains('3.8') && !model.contains('lite')
                ? 'low'
                : 'minimal',
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
          headers: {'x-goog-api-key': geminiKey},
        ),
      );
    } on DioException catch (e) {
      final msg = e.response?.data?.toString() ?? '';
      if (e.response?.statusCode == 400 &&
          msg.toLowerCase().contains('think') &&
          !_noThinking.contains(model)) {
        _noThinking.add(model);
        return ask(system, user, json: json, temperature: temperature);
      }
      rethrow;
    }
    final j = jsonDecode(res.data!) as Map<String, dynamic>;
    final parts =
        (((j['candidates'] as List).first as Map)['content'] as Map)['parts']
            as List;
    return parts
        .where((p) => p is Map && p['thought'] != true)
        .map((p) => (p as Map)['text'] ?? '')
        .join();
  }

  // 설정 화면의 [번역 테스트]
  static Future<String> test() async {
    const samples = ['前方高能', '233333', '这也太好笑了吧', 'awsl', '泪目了家人们'];
    final sw = Stopwatch()..start();
    final sb = StringBuffer();
    try {
      final (r, viaG) = await _batch(samples);
      for (var i = 0; i < samples.length; i++) {
        sb.writeln(
          '${samples[i]} → ${r.length > i ? r[i] ?? '(실패)' : '(실패)'}',
        );
      }
      sb.write(
        '\n${useGemini && !viaG ? '제미나이' : '구글'} · ${sw.elapsedMilliseconds}ms',
      );
      if (viaG) sb.write('\n(제미나이 실패: $lastGeminiError → 구글로 대신 번역)');
      if (engine == 'gemini' && geminiKey.isEmpty) {
        sb.write('\n(제미나이 키가 없어서 구글로 번역했어요)');
      }
    } catch (e) {
      sb.write('실패: $e');
    }
    return sb.toString();
  }
}
