// 용어 찾기 (실시간 번역 앱 livetr 의 Lookup 을 옮겨온 것)
// 앱이 웹 검색 결과 페이지를 가져오고(무료, 키 없음), 제미나이가 그 글을 읽고 후보와 근거를 뽑는다.
// 제미나이 무료 등급에는 검색 기능이 없어서, 검색은 앱이 하고 제미나이는 읽기만 함.
import 'dart:convert';

import 'package:PiliPlus/ko/ko_translate.dart';
import 'package:dio/dio.dart';

class KoCandidate {
  KoCandidate(this.zh, this.note, this.source, [this.evidence = '']);
  final String zh;
  final String note;
  final String source;
  final String evidence;
}

class KoTerm {
  KoTerm(this.ko, this.zh, this.note);
  final String ko;
  final String zh;
  final String note;
}

class KoCheck {
  KoCheck(this.index, this.zh, this.why);
  final int index;
  final String zh;
  final String why;
}

abstract final class KoLookup {
  static final _dio = Dio(
    BaseOptions(
      connectTimeout: const Duration(seconds: 7),
      receiveTimeout: const Duration(seconds: 9),
      headers: {
        'User-Agent':
            'Mozilla/5.0 (Linux; Android 10; Mobile) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0 Mobile Safari/537.36',
        'Accept-Language': 'ko,zh-CN;q=0.8,en;q=0.5',
      },
    ),
  );

  static const _engines = [
    'https://html.duckduckgo.com/html/?q=',
    'https://www.bing.com/search?setlang=ko&q=',
    'https://lite.duckduckgo.com/lite/?q=',
  ];

  static String _toText(String html) => html
      .replaceAll(
        RegExp(
          r'<(script|style|noscript|svg|head)[^>]*>[\s\S]*?</\1>',
          caseSensitive: false,
        ),
        ' ',
      )
      .replaceAll(
        RegExp(r'<br\s*/?>|</(p|div|li|h\d|tr|a)>', caseSensitive: false),
        '\n',
      )
      .replaceAll(RegExp(r'<[^>]+>'), ' ')
      .replaceAll('&nbsp;', ' ')
      .replaceAll('&amp;', '&')
      .replaceAll('&quot;', '"')
      .replaceAll('&#39;', "'")
      .replaceAll('&lt;', '<')
      .replaceAll('&gt;', '>')
      .replaceAll(RegExp(r'[ \t]+'), ' ')
      .replaceAll(RegExp(r'\n\s*\n+'), '\n')
      .trim();

  // 결과: 검색 결과 글 (못 가져오면 빈 문자열)
  static Future<String> search(String query, {int maxChars = 3500}) async {
    final q = Uri.encodeQueryComponent(query);
    for (final e in _engines) {
      try {
        final res = await _dio.get<String>(
          e + q,
          options: Options(responseType: ResponseType.plain),
        );
        if (res.statusCode != 200 || res.data == null) continue;
        final text = _toText(res.data!);
        final low = text.toLowerCase();
        if (text.length < 300 ||
            low.contains('captcha') ||
            low.contains('unusual traffic')) {
          continue;
        }
        return text.length > maxChars ? text.substring(0, maxChars) : text;
      } catch (_) {}
    }
    return '';
  }

  static List? _arr(String raw) {
    final a = raw.indexOf('[');
    final b = raw.lastIndexOf(']');
    if (a < 0 || b <= a) return null;
    try {
      return jsonDecode(raw.substring(a, b + 1)) as List;
    } catch (_) {
      return null;
    }
  }

  static Map? _obj(String raw) {
    final a = raw.indexOf('{');
    final b = raw.lastIndexOf('}');
    if (a < 0 || b <= a) return null;
    try {
      return jsonDecode(raw.substring(a, b + 1)) as Map;
    } catch (_) {
      return null;
    }
  }

  static const _caution =
      '중요: 이건 위 작품 속 등장인물·지명·용어일 가능성이 높다. 같은 이름의 실존 인물, '
      '역사 인물, 문학 작품, 일반 단어와 헷갈리지 마라. 중국어 표기는 그 작품의 중국어판(간체)에서 실제로 쓰는 것으로.';

  static String get _works {
    final names = KoTr.activeWorks.map((e) => e.name).toList();
    return names.join(', ');
  }

  // ── ① 번역에서 고른 한국어 단어가 원문의 어느 부분인지 ──
  static Future<String?> findSource(String zh, String ko, String word) async {
    final system =
        '중국어 원문과 그 한국어 번역이 주어진다. 한국어 번역 속 특정 단어가 원문의 어느 부분을 번역한 것인지 찾아라. '
        '답은 원문에 실제로 들어 있는 글자 그대로(한 글자도 바꾸지 말고) 가장 짧게. '
        'JSON으로만 답하라: {"zh":"원문 속 해당 부분"}';
    final user = '[중국어 원문]\n$zh\n\n[한국어 번역]\n$ko\n\n[찾을 한국어 단어]\n$word';
    final o = _obj(await KoTr.ask(system, user, json: true, temperature: 0));
    final r = o?['zh']?.toString().trim();
    if (r == null || r.isEmpty) return null;
    return r;
  }

  // ── ② 한국어 이름으로 중국어 표기 찾기 ──
  static Future<List<KoCandidate>> find(
    String ko, {
    String context = '',
    String? work,
  }) async {
    final works = (work ?? _works).trim();
    final web = StringBuffer();
    final queries = works.isNotEmpty
        ? ['$works $ko 중국어', '$works $ko 中文名']
        : ['$ko 중국어 이름'];
    for (final q in queries) {
      final r = await search(q);
      if (r.isNotEmpty) web.write('### 검색어: $q\n$r\n\n');
    }
    final system =
        '너는 중국어-한국어 고유명사 전문가다. 사용자가 보는 작품의 중국어 영상·댓글에 나온 이름/용어의 '
        '중국어(간체) 표기를 찾는다. 웹 검색 결과가 주어지면 반드시 그것을 우선 근거로 삼고, '
        '근거 문장을 결과에서 그대로 짧게 인용하라. 검색 결과에 근거가 없으면 네 지식으로 추측하되 evidence는 빈 문자열로 둬라. '
        '$_caution\n후보는 가능성 높은 순서로 최대 3개, JSON 배열로만 답하라: '
        '[{"zh":"중국어","why":"어떤 작품의 무엇인지 한국어로 짧게","evidence":"근거 인용 또는 빈 문자열"}]';
    final user = StringBuffer()
      ..write('[지금 보는 작품]\n${works.isEmpty ? '(모름)' : works}\n\n');
    if (context.isNotEmpty) user.write('[이 말이 나온 원문]\n$context\n\n');
    user
      ..write('[찾을 한국어 이름]\n$ko\n\n')
      ..write('[웹 검색 결과]\n${web.isEmpty ? '(검색 결과를 가져오지 못함)' : web}');
    final arr = _arr(await KoTr.ask(system, user.toString(), json: true));
    if (arr == null) return [];
    final out = <KoCandidate>[];
    for (final o in arr.whereType<Map>()) {
      final zh = (o['zh'] ?? '').toString().trim();
      if (zh.isEmpty || out.any((c) => c.zh == zh)) continue;
      final ev = (o['evidence'] ?? '').toString().trim();
      out.add(
        KoCandidate(
          zh,
          (o['why'] ?? '').toString(),
          ev.isNotEmpty ? '웹 검색 근거' : 'AI 추측',
          ev,
        ),
      );
    }
    return out;
  }

  // ── 작품 용어집 자동 만들기 ──
  static Future<List<KoTerm>> buildWorkGlossary(String work) async {
    final web = StringBuffer();
    for (final q in [
      '$work 등장인물 중국어 이름',
      '$work 角色 中文名',
      '$work 용어 중국어',
    ]) {
      final r = await search(q, maxChars: 3000);
      if (r.isNotEmpty) web.write('### 검색어: $q\n$r\n\n');
    }
    const system =
        '너는 중국어-한국어 고유명사 전문가다. 한국인이 이 작품의 중국어 영상(빌리빌리)을 볼 때 쓸 용어집 초안을 만든다. '
        '주요 등장인물, 지명, 조직, 자주 나오는 고유 용어, 팬들이 자주 쓰는 별명을 골라라. 중국어는 그 작품 중국어판(간체)의 실제 표기, '
        '한국어는 그 작품 한국어판의 실제 표기로. 웹 검색 결과를 우선 근거로 삼고, 확신 없는 항목은 빼라. '
        '첫 항목은 반드시 작품 이름 자체로 하라. '
        '최대 40개, JSON 배열로만 답하라: [{"ko":"한국어","zh":"중국어","note":"짧은 설명(인물/지명 등)"}]';
    final user =
        '[작품]\n$work\n\n[웹 검색 결과]\n${web.isEmpty ? '(검색 결과를 가져오지 못함 — 네 지식으로만, 확신 있는 것만)' : web}';
    final arr = _arr(await KoTr.ask(system, user, json: true));
    if (arr == null) return [];
    final out = <KoTerm>[];
    for (final o in arr.whereType<Map>()) {
      final k = (o['ko'] ?? '').toString().trim();
      final z = (o['zh'] ?? '').toString().trim();
      if (k.isEmpty || z.isEmpty || z.contains('=') || out.any((t) => t.zh == z)) {
        continue;
      }
      out.add(KoTerm(k, z, (o['note'] ?? '').toString().trim()));
    }
    return out;
  }

  // ── 용어집 점검 ──
  static Future<List<KoCheck>> check(
    String? work,
    List<MapEntry<String, String>> entries,
  ) async {
    if (entries.isEmpty) return [];
    final w = (work ?? _works).trim();
    final web = w.isNotEmpty
        ? await search('$w 등장인물 중국어 이름', maxChars: 3000)
        : '';
    const system =
        '너는 중국어-한국어 고유명사 전문가다. 용어집의 각 줄은 "중국어=한국어"이다. '
        '중국어가 그 작품 중국어판(간체)의 실제 표기와 다르거나, 그 한국어 이름에 맞지 않는 줄만 골라 올바른 중국어를 제시하라. '
        '중요: 같은 이름의 실존 인물, 역사 인물, 일반 단어와 헷갈리지 마라. 이미 맞거나 확신이 없으면 빼라. '
        'JSON 배열로만 답하라: [{"i":줄번호,"zh":"올바른 중국어","why":"짧은 근거"}]';
    final user = StringBuffer()
      ..write('[작품]\n${w.isEmpty ? '(모름)' : w}\n\n[용어집]\n');
    for (var i = 0; i < entries.length; i++) {
      user.write('$i. ${entries[i].key}=${entries[i].value}\n');
    }
    if (web.isNotEmpty) user.write('\n[웹 검색 결과]\n$web');
    final arr = _arr(await KoTr.ask(system, user.toString(), json: true));
    if (arr == null) return [];
    final out = <KoCheck>[];
    for (final o in arr.whereType<Map>()) {
      final idx = o['i'] is int ? o['i'] as int : int.tryParse('${o['i']}') ?? -1;
      final zh = (o['zh'] ?? '').toString().trim();
      if (idx >= 0 &&
          idx < entries.length &&
          zh.isNotEmpty &&
          zh != entries[idx].key &&
          !zh.contains('=')) {
        out.add(KoCheck(idx, zh, (o['why'] ?? '').toString()));
      }
    }
    return out;
  }

  static String webSearchUrl(String ko, {String? work}) {
    final q = [
      (work ?? _works).trim(),
      ko,
      '중국어 이름',
    ].where((e) => e.isNotEmpty).join(' ');
    return 'https://www.google.com/search?q=${Uri.encodeQueryComponent(q)}';
  }
}
