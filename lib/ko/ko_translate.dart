// 한국어 번역 (PiliPlus 개조)
// 탄막·자막·라이브 탄막·제목·댓글의 중국어를 한국어로 바꿔서 보여주기 위한 번역 모듈.
// · 구글 번역: 무료, 키 필요 없음, 빠름 (항상 마지막 대비책)
// · AI 번역기(제미나이·Groq·NVIDIA 등, ko_engines.dart): 밈·은어를 뜻이 통하게 의역. 여러 개를 돌려씀
// · 항목(탄막·라이브·자막·제목·댓글)마다 AI를 쓸지 구글을 쓸지 고를 수 있음
// 번역 결과는 메모리에 캐시해서 같은 문장은 다시 번역하지 않음.
// 직접 고친 번역(문장 단위)과 용어집(공통 + 작품별)은 저장해 두고 계속 씀.
import 'dart:async';
import 'dart:collection';
import 'dart:convert';

import 'package:PiliPlus/ko/ko_engines.dart';
import 'package:PiliPlus/ko/ko_memes.dart';
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
  static const kGlossary = 'koTrGlossary'; // 공통 용어집
  static const kText = 'koTrText'; // 제목·소개·게시글
  static const kReply = 'koTrReply'; // 댓글
  static const kSearch = 'koTrSearch'; // 검색 화면(인기 검색어·자동완성 등)
  static const kSearchKo = 'koTrSearchKo'; // 한국어로 검색하면 중국어로 바꿔서
  static const kOverrides = 'koTrOverrides'; // 직접 고친 번역 {원문: 번역}
  static const kWorks = 'koTrWorks'; // 작품별 용어집
  // 항목별로 AI 번역기를 쓸지 (끄면 구글 번역)
  static const kAiDm = 'koTrAiDm';
  static const kAiLive = 'koTrAiLive';
  static const kAiSub = 'koTrAiSub';
  static const kAiText = 'koTrAiText';
  static const kAiReply = 'koTrAiReply';

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

  // AI 번역기가 하나라도 쓸 수 있게 등록돼 있는지
  static bool get hasAi => KoEngines.any;

  // 항목: 'dm' 탄막, 'live' 라이브 탄막, 'sub' 자막, 'text' 제목·소개·검색, 'reply' 댓글
  static bool aiFor(String kind) {
    if (!hasAi) return false;
    return switch (kind) {
      'dm' => _get(kAiDm, false),
      'live' => _get(kAiLive, false),
      'sub' => _get(kAiSub, true),
      'reply' => _get(kAiReply, true),
      _ => _get(kAiText, true),
    };
  }

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
    translateAll(redo, kind: 'text').whenComplete(() => subRefresh.value++);
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
    subRefresh.value++;
    if (ko == null && !_cache.containsKey(src)) want(src);
  }

  static bool hasOverride(String s) => overrides.containsKey(s);

  // ── 캐시 ──
  static final LinkedHashMap<String, String> _cache = LinkedHashMap();
  static final LinkedHashMap<String, String> _rev = LinkedHashMap(); // 번역 → 원문
  static final LinkedHashMap<String, String> _by = LinkedHashMap(); // 원문 → 번역한 곳
  static final Set<String> _pending = HashSet();
  static const _cacheMax = 30000;

  static Timer? _notifyTimer;

  static void _put(String src, String ko, {bool viaGoogle = false, String? by}) {
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
    if (by != null) {
      _by.remove(src);
      _by[src] = by;
      if (_by.length > _cacheMax) _by.remove(_by.keys.first);
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
    subRefresh.value++;
  }

  // 번역이 새로 들어올 때마다 1씩 올라감 → 화면의 글자들이 다시 그려짐
  static final ValueNotifier<int> updates = ValueNotifier(0);

  // 용어집·고친 번역이 바뀌어서 다시 번역이 끝났을 때 → 켜져 있는 자막을 새로 만듦
  static final ValueNotifier<int> subRefresh = ValueNotifier(0);
  static Timer? _subTimer;

  // 자막 AI 다듬기가 여러 번에 나눠 끝나도 자막은 한 번만 새로 만들게 2초 모았다가
  static void _subRefreshSoon() {
    _subTimer?.cancel();
    _subTimer = Timer(const Duration(seconds: 2), () => subRefresh.value++);
  }

  // 댓글·제목별 '원문 보기' 상태 (댓글 id 또는 제목 글자)
  static final Set<Object> showOrig = HashSet();
  static int origVersion = 0;

  static void toggleOrig(Object id) {
    if (!showOrig.remove(id)) showOrig.add(id);
    origVersion++;
    updates.value++;
  }

  // AI 로 번역하려다 다 막혀서 구글로 대신 번역된 문장들
  static final Set<String> _viaGoogle = HashSet();
  static bool viaGoogle(String s) => _viaGoogle.contains(s) && !hasOverride(s);

  // 이 번역을 한 곳 (번역 고치기 창에 표시)
  static String? byOf(String s) =>
      hasOverride(s) ? '직접 고친 번역' : _by[s];

  static String? get lastAiError => KoEngines.lastError;

  // ── 화면 글자(제목·댓글 등): 0.3초 모아서 한 번에 번역 ──
  static final Map<String, List<String>> _wantBuf = {};
  static final Map<String, Timer> _wantTimer = {};

  static void want(String s, {String kind = 'text'}) {
    if (!needs(s) || isTranslated(s) || _pending.contains(s)) return;
    final m = KoMemes.lookup(s);
    if (m != null) {
      _put(s, m, by: '밈 사전');
      return;
    }
    (_wantBuf[kind] ??= []).add(s);
    _wantTimer[kind] ??= Timer(const Duration(milliseconds: 300), () {
      _wantTimer.remove(kind);
      final xs = List.of(_wantBuf[kind] ?? const <String>[]);
      _wantBuf[kind]?.clear();
      translateAll(xs, kind: kind);
    });
  }

  static bool isTranslated(String s) =>
      overrides.containsKey(s) || _cache.containsKey(s);

  // 화면에 보여줄 글자: 고친 번역 > 번역 > 원문
  static String show(String s) => overrides[s] ?? _cache[s] ?? s;

  // 번역문으로 원문 찾기 (탄막 메뉴·자막에서 '번역 고치기' 할 때)
  static String? origOf(String ko) => _rev[ko];

  // 중국어(한자)가 하나라도 있어야 번역 대상
  static final _cjk = RegExp(r'[㐀-䶿一-鿿]');
  static final _hangul = RegExp(r'[가-힣ㄱ-ㅎㅏ-ㅣ]');

  static bool hasHangul(String s) => _hangul.hasMatch(s);
  static bool hasCjk(String s) => _cjk.hasMatch(s);

  static bool needs(String s) {
    final t = s.trim();
    if (t.isEmpty || t.length > 1500) return false;
    if (_cjk.hasMatch(t)) return true;
    // '2333', 'awsl' 같은 밈은 사전으로 바꿈
    return KoMemes.lookup(t) != null;
  }

  // ── 일괄 번역 ──
  static final _dio = Dio(
    BaseOptions(
      connectTimeout: const Duration(seconds: 8),
      receiveTimeout: const Duration(seconds: 25),
      sendTimeout: const Duration(seconds: 10),
    ),
  );

  // ── 번역 줄 (서로 막지 않게 따로 세움) ──
  // 'ui': 화면에 보이는 제목·댓글·검색 (가장 먼저)
  // 'bg': 탄막·자막
  // 'refine': 구글로 먼저 보여준 걸 AI 번역으로 다듬기
  static final Map<String, Queue<_KoJob>> _lanes = {
    'ui': Queue(),
    'bg': Queue(),
    'refine': Queue(),
  };
  static final Map<String, int> _running = {'ui': 0, 'bg': 0, 'refine': 0};
  static const Map<String, int> _maxRun = {'ui': 3, 'bg': 3, 'refine': 3};

  static String _laneOf(String kind) =>
      (kind == 'dm' || kind == 'sub') ? 'bg' : 'ui';

  // 한 번에 보낼 줄 수: 탄막·자막은 크게(요청 수 절약), 댓글·제목은 작게(빨리 보이게)
  static int _chunkFor(String kind, bool ai) {
    if (!ai) return 80;
    return switch (kind) {
      'dm' => 200,
      'sub' => 120,
      'reply' => 15,
      _ => 20,
    };
  }

  // 먼저 구글로 보여주고 AI 번역이 오면 바꾸기
  static const kRefine = 'koTrAiRefine';
  static bool get refineOn => _get(kRefine, true);

  // texts 를 순서대로(앞쪽이 먼저) 번역해서 캐시에 넣음. 이미 된 것/진행 중인 것은 건너뜀.
  // 돌려주는 Future 는 '일단 보여줄 번역'(구글 먼저면 구글)이 다 들어오면 끝남.
  static Future<void> translateAll(
    List<String> texts, {
    int chunk = 0,
    String kind = 'dm',
  }) {
    final todo = <String>[];
    final seen = HashSet<String>();
    for (final t in texts) {
      if (!needs(t) ||
          isTranslated(t) ||
          _pending.contains(t) ||
          !seen.add(t)) {
        continue;
      }
      final m = KoMemes.lookup(t);
      if (m != null) {
        _put(t, m, by: '밈 사전');
        continue;
      }
      todo.add(t);
    }
    if (todo.isEmpty) return Future.value();
    _pending.addAll(todo);
    final ai = aiFor(kind);
    // 탄막은 양이 많아서 '구글 먼저'를 안 씀 (AI 를 쓰면 바로 AI 로)
    final refine = ai && refineOn && kind != 'dm' && !googleBlocked;
    final lane = _laneOf(kind);
    final size = refine
        ? 80
        : (chunk > 0 && !ai ? chunk : _chunkFor(kind, ai));
    final jobs = <_KoJob>[];
    for (var i = 0; i < todo.length; i += size) {
      jobs.add(
        _KoJob(
          kind,
          todo.sublist(i, i + size > todo.length ? todo.length : i + size),
          refine ? 'google' : (ai ? 'ai' : 'google'),
          refine,
        ),
      );
    }
    // 새로 들어온 요청을 줄 맨 앞에 (지금 화면·지금 장면이 먼저)
    final q = _lanes[lane]!;
    for (final j in jobs.reversed) {
      q.addFirst(j);
    }
    final done = Future.wait(jobs.map((j) => j.first.future));
    _pump();
    return done;
  }

  static void _pump() {
    for (final lane in _lanes.keys) {
      final q = _lanes[lane]!;
      while (_running[lane]! < _maxRun[lane]! && q.isNotEmpty) {
        final job = q.removeFirst();
        _running[lane] = _running[lane]! + 1;
        _runJob(job).whenComplete(() {
          _running[lane] = _running[lane]! - 1;
          _pump();
        });
      }
    }
  }

  static Future<void> _runJob(_KoJob job) async {
    var handedOff = false;
    try {
      final (out, viaG, by) = await _batch(
        job.texts,
        job.kind,
        mode: job.mode,
        googleFallback: !job.refining,
      );
      if (job.refining && viaG) {
        // AI 가 다 막힘 → 이미 보여준 구글 번역을 그대로 둠
        for (final t in job.texts) {
          _viaGoogle.add(t);
          _by[t] = '구글 번역 (AI가 막혀서)';
        }
        return;
      }
      for (var i = 0; i < job.texts.length; i++) {
        final r = i < out.length ? out[i] : null;
        if (r == null || r.trim().isEmpty) {
          // 구글이 막혀서 못 한 것: 풀리면 다시
          if (googleBlocked) {
            if (_retryLater.length > 5000) _retryLater.clear();
            _retryLater[job.texts[i]] = job.kind;
          }
          continue;
        }
        {
          _put(
            job.texts[i],
            r.trim(),
            viaGoogle: viaG,
            by: job.refineAfter && by.startsWith('구글')
                ? '구글 번역 (AI 번역 기다리는 중)'
                : by,
          );
        }
      }
      if (job.refineAfter && by.startsWith('구글')) {
        // 구글로 먼저 보여줬으니 AI 로 다듬기 (작게 나눠서, 별도 줄에서)
        final size = _chunkFor(job.kind, true);
        final parts = <_KoJob>[];
        for (var i = 0; i < job.texts.length; i += size) {
          parts.add(
            _KoJob(
              job.kind,
              job.texts.sublist(
                i,
                i + size > job.texts.length ? job.texts.length : i + size,
              ),
              'ai',
              false,
              refining: true,
            ),
          );
        }
        final q = _lanes['refine']!;
        for (final p in parts.reversed) {
          q.addFirst(p);
        }
        handedOff = true;
        job.first.complete();
        _pump();
        Future.wait(parts.map((p) => p.first.future)).whenComplete(() {
          _pending.removeAll(job.texts);
          if (job.kind == 'sub') _subRefreshSoon();
        });
      }
    } catch (_) {
    } finally {
      if (!job.first.isCompleted) job.first.complete();
      if (!handedOff) _pending.removeAll(job.texts);
    }
  }

  // ── 라이브 탄막: 0.3초 모아서 한 번에 ──
  static final List<(String, Completer<String>)> _liveBuf = [];
  static Timer? _liveTimer;

  static Future<String> live(String s) {
    if (!liveOn || !needs(s)) return Future.value(s);
    if (isTranslated(s)) return Future.value(show(s));
    final m = KoMemes.lookup(s);
    if (m != null) {
      _put(s, m, by: '밈 사전');
      return Future.value(m);
    }
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
    _batch(texts, 'live')
        .then((res) {
          final (out, viaG, by) = res;
          for (var i = 0; i < texts.length; i++) {
            final r = i < out.length ? out[i] : null;
            if (r != null && r.trim().isNotEmpty) {
              _put(texts[i], r.trim(), viaGoogle: viaG, by: by);
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

  // ── 엔진 ── 결과: (번역들, AI 대신 구글로 번역했는지, 번역한 곳)
  static Future<(List<String?>, bool, String)> _batch(
    List<String> xs,
    String kind, {
    String? mode,
    bool googleFallback = true,
  }) async {
    final useAi = mode == null ? aiFor(kind) : (mode == 'ai' && hasAi);
    if (useAi) {
      try {
        final (r, slot) = await KoEngines.rotate((s) => _aiTranslate(s, xs));
        return (r, false, slot.label);
      } catch (_) {
        if (!googleFallback) {
          return (List<String?>.filled(xs.length, null), true, '');
        }
        // AI 번역기가 다 막힘(한도 초과 등) → 구글로
        return (await _googleSafe(xs), true, '구글 번역 (AI 대신)');
      }
    }
    // 구글이 막혀 있으면 AI 번역기로 대신
    if (googleBlocked && hasAi) {
      try {
        final (r, slot) = await KoEngines.rotate((s) => _aiTranslate(s, xs));
        return (r, false, '${slot.label} (구글이 막혀서)');
      } catch (_) {}
    }
    final g = await _googleSafe(xs);
    if (hasAi && googleBlocked && g.every((e) => e == null)) {
      // 번역 도중 구글이 막힘 → AI 로 다시
      try {
        final (r, slot) = await KoEngines.rotate((s) => _aiTranslate(s, xs));
        return (r, false, '${slot.label} (구글이 막혀서)');
      } catch (_) {}
    }
    return (g, false, '구글 번역');
  }

  static String _applyGlossary(String s, List<MapEntry<String, String>> gl) {
    var r = s;
    for (final e in gl) {
      r = r.replaceAll(e.key, ' ${e.value} ');
    }
    return r.replaceAll(RegExp(r' {2,}'), ' ').trim();
  }

  // ── 구글 번역 + 용어집 ──
  // 용어집 단어를 구글이 손대지 않는 표시(QZ0Q)로 바꿔 보내고, 번역 뒤에 한국어로 되돌림.
  // 표시가 사라지거나 망가지면 그 줄만 예전 방식(한국어를 끼워 넣기)으로 다시 번역.
  static final _phRe = RegExp(r'Q\s*Z\s*(\d+)\s*Q', caseSensitive: false);
  static final _phMark = RegExp(
    '(\\d+)(?:(이|가|을|를|은|는|과|와)(?![가-힣]))?',
  );

  static (String, List<String>) _protect(
    String s,
    List<MapEntry<String, String>> gl,
  ) {
    final vals = <String>[];
    var r = s;
    for (final e in gl) {
      if (!r.contains(e.key)) continue;
      final i = vals.length;
      vals.add(e.value);
      r = r.replaceAll(e.key, ' QZ${i}Q ');
    }
    return (r.replaceAll(RegExp(r' {2,}'), ' ').trim(), vals);
  }

  static String? _restore(String out, List<String> vals) {
    if (vals.isEmpty) return out;
    final seen = <int>{};
    var r = out.replaceAllMapped(_phRe, (m) {
      final i = int.parse(m[1]!);
      if (i >= vals.length) return m[0]!;
      seen.add(i);
      return '$i';
    });
    if (seen.length != vals.length) return null;
    r = r.replaceAllMapped(_phMark, (m) {
      final w = vals[int.parse(m[1]!)];
      final p = m[2];
      return p == null ? w : w + _particle(w, p);
    });
    return r.replaceAll(RegExp(r' {2,}'), ' ').trim();
  }

  // 받침에 맞게 조사 고치기 (뇌횡가 → 뇌횡이)
  static String _particle(String word, String p) {
    if (word.isEmpty) return p;
    final c = word.codeUnitAt(word.length - 1);
    if (c < 0xAC00 || c > 0xD7A3) return p;
    final batchim = (c - 0xAC00) % 28 != 0;
    return switch (p) {
      '이' || '가' => batchim ? '이' : '가',
      '을' || '를' => batchim ? '을' : '를',
      '은' || '는' => batchim ? '은' : '는',
      '과' || '와' => batchim ? '과' : '와',
      _ => p,
    };
  }

  // 한 줄을 용어집 적용해서 번역 (표시 방식 → 안 되면 끼워 넣기 방식)
  static Future<String?> _googleOne(
    String s,
    List<MapEntry<String, String>> gl,
  ) async {
    final (p, vals) = _protect(s, gl);
    try {
      final r = _restore(await _google(p), vals);
      if (r != null) return r;
    } catch (_) {}
    try {
      return await _google(_applyGlossary(s, gl));
    } catch (_) {
      return null;
    }
  }

  // 여러 줄을 한 번에 보내고 줄 단위로 나눠 받음. 줄 수가 안 맞으면 잘게 나눠 다시 시도.
  static Future<List<String?>> _googleSafe(List<String> xs) async {
    // 여러 줄짜리(소개·댓글)는 줄 구분이 섞이지 않게 하나씩, 한 줄짜리는 묶어서
    final out = List<String?>.filled(xs.length, null);
    final singles = <int>[];
    final multis = <int>[];
    final gl = glossary;
    for (var i = 0; i < xs.length; i++) {
      (xs[i].contains('\n') ? multis : singles).add(i);
    }
    // 한 줄짜리 묶음과 여러 줄짜리(6개씩 동시에)를 함께 진행
    Future<void> runSingles() async {
      if (singles.isEmpty) return;
      final r = await _googleLines(singles.map((i) => xs[i]).toList(), gl);
      for (var k = 0; k < singles.length; k++) {
        out[singles[k]] = k < r.length ? r[k] : null;
      }
    }

    Future<void> runMultis() async {
      for (var k = 0; k < multis.length; k += 6) {
        final part = multis.sublist(k, k + 6 > multis.length ? multis.length : k + 6);
        await Future.wait(
          part.map((i) async => out[i] = await _googleOne(xs[i], gl)),
        );
      }
    }

    await Future.wait([runSingles(), runMultis()]);
    return out;
  }

  static Future<List<String?>> _googleLines(
    List<String> xs,
    List<MapEntry<String, String>> gl,
  ) async {
    final prot = xs.map((e) => _protect(e, gl)).toList();
    try {
      final out = await _google(prot.map((e) => e.$1).join('\n'));
      final lines = out.split('\n');
      if (lines.length == xs.length) {
        final res = <String?>[];
        for (var i = 0; i < xs.length; i++) {
          final r = _restore(lines[i], prot[i].$2);
          // 표시가 망가진 줄만 따로 다시
          res.add(r ?? await _googleOne(xs[i], gl));
        }
        return res;
      }
    } catch (_) {}
    if (xs.length <= 1) {
      return [await _googleOne(xs.first, gl)];
    }
    final mid = xs.length ~/ 2;
    final a = await _googleLines(xs.sublist(0, mid), gl);
    final b = await _googleLines(xs.sublist(mid), gl);
    return [...a, ...b];
  }

  // ── 구글 번역 통로 ──
  // 무료 통로라 한꺼번에 많이 보내면 구글이 잠깐 막음 → 동시에 2개, 0.35초 간격으로만 보냄.
  // 요청은 브라우저와 같은 모양으로 (짧은 글은 주소에 넣어 GET, 긴 글만 POST, 크롬 브라우저 정보).
  // 거절당하면 그 통로를 잠깐 쉬게 하고, 쉬는 시간이 끝나면 '你好' 하나로 가볍게 확인한 뒤 돌아옴.
  // 다 막히면 AI 번역기로 대신함.
  static final List<_GEndpoint> _gEndpoints = [
    _GEndpoint('기본', 'https://translate.googleapis.com/translate_a/single', 'gtx'),
    _GEndpoint('보조 1', 'https://translate.googleapis.com/translate_a/t', 'dict'),
    _GEndpoint('보조 2', 'https://clients5.google.com/translate_a/t', 'dict'),
  ];
  static const _ua =
      'Mozilla/5.0 (Linux; Android 10; K) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/130.0.0.0 Mobile Safari/537.36';
  static int _gActive = 0;
  static final Queue<Completer<void>> _gWait = Queue();
  static DateTime _gLast = DateTime(2000);

  static Future<void> _gAcquire() async {
    while (_gActive >= 2) {
      final c = Completer<void>();
      _gWait.add(c);
      await c.future;
    }
    _gActive++;
    final wait = 350 - DateTime.now().difference(_gLast).inMilliseconds;
    _gLast = DateTime.now().add(Duration(milliseconds: wait > 0 ? wait : 0));
    if (wait > 0) await Future.delayed(Duration(milliseconds: wait));
  }

  static void _gRelease() {
    _gActive--;
    if (_gWait.isNotEmpty) _gWait.removeFirst().complete();
  }

  // 지금 구글로 번역할 수 있는 통로가 하나도 없는지 (안 쓰는 통로는 빼고)
  static bool get googleBlocked =>
      _gEndpoints.where((e) => !e.unsupported).every((e) => e.blocked);

  static String _hm(DateTime t) =>
      '${t.hour.toString().padLeft(2, '0')}:${t.minute.toString().padLeft(2, '0')}';

  // 설정 화면에 보여줄 구글 상태
  static String get googleStatus {
    if (_gEndpoints.every((e) => e.strikes == 0 && e.reason == null)) return '정상';
    return _gEndpoints.map((e) {
      if (e.unsupported) return '${e.name}: 안 씀 (${e.reason ?? '지원 안 함'})';
      final checked = e.lastCheck == null ? '' : ', ${_hm(e.lastCheck!)} 확인';
      if (!e.blocked) {
        return e.reason == null ? '${e.name}: 정상' : '${e.name}: 정상 (최근: ${e.reason}$checked)';
      }
      final left = e.blockedUntil!.difference(DateTime.now());
      final m = left.inMinutes;
      return '${e.name}: 막힘 — ${e.reason ?? '거절'}$checked, ${m < 1 ? 1 : m}분 뒤 다시 확인';
    }).join('\n');
  }

  static void resetGoogle() {
    for (final e in _gEndpoints) {
      e
        ..blockedUntil = null
        ..strikes = 0
        ..unsupported = false
        ..reason = null;
    }
  }

  static void _gBlock(_GEndpoint e, String reason) {
    e.strikes++;
    e.reason = reason;
    e.lastCheck = DateTime.now();
    // 2분 → 3분 → 5분 (그 뒤로는 5분마다 가볍게 확인)
    const steps = [2, 3, 5];
    final m = steps[e.strikes - 1 < steps.length ? e.strikes - 1 : steps.length - 1];
    e.blockedUntil = DateTime.now().add(Duration(minutes: m));
  }

  // 거절 이유 알아보기. null 이면 '막힘'이 아님 (다른 오류)
  static String? _blockReason(Object e) {
    if (e is _GoogleBlocked) return '로봇 확인 화면';
    if (e is DioException) {
      final code = e.response?.statusCode;
      final body = (e.response?.data?.toString() ?? '').toLowerCase();
      if (body.contains('sorry') || body.contains('unusual traffic')) return '로봇 확인 화면 ($code)';
      if (code == 429) return '요청이 너무 많다고 거절 (429)';
      if (code == 503) return '잠시 거절 (503)';
      if (code == 403) return '접근 거절 (403)';
    }
    return null;
  }

  static Future<String> _google(
    String text, {
    String sl = 'auto',
    String tl = 'ko',
  }) async {
    if (googleBlocked) throw const _GoogleBlocked();
    await _gAcquire();
    try {
      final encLen = Uri.encodeQueryComponent(text).length;
      Object? lastErr;
      for (final ep in _gEndpoints) {
        if (ep.unsupported || ep.blocked) continue;
        // 보조 통로는 주소에 글을 넣어서만 보내니 짧은 글만
        if (ep.client == 'dict' && encLen > 1800) continue;
        // 쉬다가 돌아온 통로는 '你好' 하나로 먼저 확인 (큰 요청으로 또 막히지 않게)
        if (ep.strikes > 0) {
          try {
            await _callGoogle(ep, '你好', 'auto', 'ko', get: true);
            ep
              ..strikes = 0
              ..lastCheck = DateTime.now();
            if (_retryLater.isNotEmpty) Future.microtask(_retryFailed);
          } catch (e) {
            final why = _blockReason(e);
            if (why != null) {
              _gBlock(ep, why);
              lastErr = e;
              continue;
            }
          }
        }
        // 짧은 글은 브라우저처럼 GET, 긴 글은 POST. 한쪽이 거절당하면 다른 쪽으로 한 번 더.
        final preferGet = encLen <= 1800;
        final tries = ep.client == 'dict' ? [true] : (preferGet ? [true, false] : [false]);
        String? why;
        for (final get in tries) {
          try {
            final r = await _callGoogle(ep, text, sl, tl, get: get);
            ep.reason = null;
            return r;
          } catch (e) {
            lastErr = e;
            why = _blockReason(e);
            if (why == null) {
              final code = e is DioException ? e.response?.statusCode : null;
              if (e is DioException && code == null) rethrow; // 인터넷 문제
              // 보조 통로가 400/404 등을 주면 그 통로는 안 씀
              if (ep.client == 'dict' && code != null && code >= 400 && code < 500) {
                ep
                  ..unsupported = true
                  ..reason = '지원 안 함 ($code)';
              }
              break;
            }
          }
        }
        if (why != null) _gBlock(ep, why);
      }
      if (googleBlocked) throw const _GoogleBlocked();
      throw lastErr ?? Exception('구글 번역 실패');
    } finally {
      _gRelease();
    }
  }

  static Future<String> _callGoogle(
    _GEndpoint ep,
    String text,
    String sl,
    String tl, {
    required bool get,
  }) async {
    final Response<String> res;
    final params = ep.client == 'gtx'
        ? {'client': 'gtx', 'sl': sl, 'tl': tl, 'dt': 't'}
        : {'client': 'dict-chrome-ex', 'sl': sl, 'tl': tl};
    if (get) {
      res = await _dio.get<String>(
        ep.url,
        queryParameters: {...params, 'q': text},
        options: Options(
          responseType: ResponseType.plain,
          headers: {'User-Agent': _ua, 'Accept': '*/*'},
        ),
      );
    } else {
      res = await _dio.post<String>(
        ep.url,
        queryParameters: params,
        data: 'q=${Uri.encodeQueryComponent(text)}',
        options: Options(
          contentType: 'application/x-www-form-urlencoded;charset=utf-8',
          responseType: ResponseType.plain,
          headers: {'User-Agent': _ua, 'Accept': '*/*'},
        ),
      );
    }
    final raw = (res.data ?? '').trimLeft();
    if (raw.startsWith('<')) throw const _GoogleBlocked();
    final j = jsonDecode(raw);
    String out = '';
    if (ep.client == 'gtx') {
      final sb = StringBuffer();
      for (final seg in ((j as List)[0] as List)) {
        if (seg is List && seg.isNotEmpty && seg[0] is String) sb.write(seg[0]);
      }
      out = sb.toString();
    } else if (j is List && j.isNotEmpty) {
      final x = j[0];
      if (x is String) {
        out = x;
      } else if (x is List && x.isNotEmpty && x[0] is String) {
        out = x[0] as String;
      }
    }
    if (out.trim().isEmpty) throw Exception('empty');
    ep.lastCheck = DateTime.now();
    return out.trimRight();
  }

  // 구글이 막혀서 못 한 번역 (원문 → 항목)
  static final LinkedHashMap<String, String> _retryLater = LinkedHashMap();

  static void _retryFailed() {
    final byKind = <String, List<String>>{};
    _retryLater.forEach((t, k) => (byKind[k] ??= []).add(t));
    _retryLater.clear();
    byKind.forEach((k, ts) => translateAll(ts, kind: k));
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

  // ── AI 번역 (한 칸) ──
  static Future<List<String?>> _aiTranslate(KoSlot slot, List<String> xs) async {
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
        '영상 제목·댓글·게시글·검색어도 들어온다. [doge] 같은 대괄호 이모티콘, @닉네임, 링크, BV번호는 그대로 둬라. 줄바꿈은 유지하라. ',
      )
      ..write(
        '애칭은 글자 그대로 옮기지 마라: 老X(라오X)는 친근한 "X 형/X 씨" 느낌, 小X(샤오X)·阿X·XX(글자 반복)는 귀여워하는 애칭(예: 小堂→돈키), '
        'X子는 "X이/X 녀석" 같은 허물없는 별명이고 이름 글자에서 온 말장난일 수 있다(예: 림버스의 李箱을 箱子라 부름 → 이상). '
        '儿子·鹅子(팬이 아끼는 남캐)→우리 아들, 女儿→우리 딸, 崽·崽崽→우리 애기. 老·小·子를 "늙은/작은/아들"로 직역하지 마라. ',
      )
      ..write(
        '입력은 JSON 문자열 배열이다. 입력 배열과 같은 길이·같은 순서의 JSON 문자열 배열 하나로만 답하라. 설명이나 다른 글은 쓰지 마라.',
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
    final text = await KoEngines.ask(
      slot,
      system.toString(),
      jsonEncode(xs),
      json: true,
    );
    final a = text.indexOf('[');
    final b = text.lastIndexOf(']');
    if (a < 0 || b <= a) throw FormatException('형식');
    final arr = jsonDecode(text.substring(a, b + 1)) as List;
    if (arr.length != xs.length) {
      // 길이가 안 맞으면 반씩 나눠 다시 (작게 나눌수록 잘 맞음)
      if (xs.length <= 2) throw FormatException('줄 수 안 맞음');
      final mid = xs.length ~/ 2;
      return [
        ...await _aiTranslate(slot, xs.sublist(0, mid)),
        ...await _aiTranslate(slot, xs.sublist(mid)),
      ];
    }
    return arr.map((e) => e?.toString()).toList();
  }

  // AI 에게 묻기 (번역 고치기·용어집 만들기에서 씀). 등록된 AI 번역기를 돌려가며.
  static Future<String> ask(
    String system,
    String user, {
    bool json = false,
    double temperature = 0.3,
  }) async {
    if (!hasAi) throw Exception('AI 번역기가 필요해요 (설정 → AI 번역기)');
    final (r, _) = await KoEngines.rotate(
      (s) => KoEngines.ask(s, system, user, json: json, temperature: temperature),
    );
    return r;
  }

  // 설정 화면의 [번역 테스트]
  static Future<String> test() async {
    const samples = ['前方高能，太强了', '这也太好笑了吧', '泪目了家人们', '雷横好帅'];
    final sw = Stopwatch()..start();
    final sb = StringBuffer();
    try {
      final (r, viaG, by) = await _batch(samples, 'text');
      for (var i = 0; i < samples.length; i++) {
        sb.writeln(
          '${samples[i]} → ${r.length > i ? r[i] ?? '(실패)' : '(실패)'}',
        );
      }
      sb.write('\n$by · ${sw.elapsedMilliseconds}ms');
      if (viaG) sb.write('\n(AI 번역기 실패: ${lastAiError ?? '알 수 없음'})');
      if (!hasAi) sb.write('\n(AI 번역기가 없어서 구글로 번역했어요)');
    } catch (e) {
      sb.write('실패: $e');
    }
    return sb.toString();
  }
}

class _KoJob {
  _KoJob(
    this.kind,
    this.texts,
    this.mode,
    this.refineAfter, {
    this.refining = false,
  });
  final String kind;
  final List<String> texts;
  final String mode; // 'google' | 'ai'
  final bool refineAfter; // 구글로 끝낸 뒤 AI 로 다듬기를 이어서
  final bool refining; // 다듬기 작업 (실패해도 구글 번역이 이미 있음)
  final Completer<void> first = Completer<void>();
}

class _GEndpoint {
  _GEndpoint(this.name, this.url, this.client);
  final String name;
  final String url;
  final String client; // 'gtx' | 'dict'
  DateTime? blockedUntil;
  DateTime? lastCheck;
  int strikes = 0;
  bool unsupported = false;
  String? reason;
  bool get blocked =>
      blockedUntil != null && DateTime.now().isBefore(blockedUntil!);
}

class _GoogleBlocked implements Exception {
  const _GoogleBlocked();
  @override
  String toString() => '구글 번역이 잠깐 막힘';
}
