// 작품별 용어집 화면 + 용어집 자동 만들기·점검
import 'package:PiliPlus/ko/ko_lookup.dart';
import 'package:PiliPlus/ko/ko_translate.dart';
import 'package:flutter_smart_dialog/flutter_smart_dialog.dart';
import 'package:get/get.dart';
import 'package:material_ui/material_ui.dart';

const _modeNames = ['자동 (제목·태그로 판단)', '항상 적용', '끔'];

class KoWorksPage extends StatefulWidget {
  const KoWorksPage({super.key});

  @override
  State<KoWorksPage> createState() => _KoWorksPageState();
}

class _KoWorksPageState extends State<KoWorksPage> {
  Future<void> _add() async {
    final name = await _askText(context, '작품 이름', hint: '예) 원신, 명일방주');
    if (name == null || name.trim().isEmpty) return;
    if (KoTr.works.any((w) => w.name == name.trim())) {
      SmartDialog.showToast('이미 있는 작품이에요');
      return;
    }
    final w = KoWork(name: name.trim());
    KoTr.works.add(w);
    KoTr.saveWorks();
    setState(() {});
    if (KoTr.hasAi && mounted) {
      final ok = await showDialog<bool>(
        context: context,
        builder: (context) => AlertDialog(
          title: const Text('용어집 자동 만들기'),
          content: Text(
            "'${w.name}'의 등장인물·지명 같은 용어를 웹 검색과 AI로 모아서 용어집 초안을 만들까요? (30초쯤 걸려요)",
          ),
          actions: [
            TextButton(onPressed: () => Get.back(result: false), child: const Text('나중에')),
            TextButton(onPressed: () => Get.back(result: true), child: const Text('만들기')),
          ],
        ),
      );
      if (ok == true && mounted) await autoBuild(context, w);
    }
    if (mounted) setState(() {});
  }

  @override
  Widget build(BuildContext context) {
    final cs = ColorScheme.of(context);
    final works = KoTr.works;
    return Scaffold(
      appBar: AppBar(
        title: const Text('작품별 용어집'),
        actions: [
          IconButton(
            tooltip: '작품 추가',
            icon: const Icon(Icons.add),
            onPressed: _add,
          ),
        ],
      ),
      body: SafeArea(
        child: ListView(
          padding: const EdgeInsets.only(bottom: 32),
          children: [
            Padding(
              padding: const EdgeInsets.fromLTRB(16, 8, 16, 8),
              child: Text(
                '작품마다 용어집을 따로 둘 수 있어요. 영상 제목이나 태그에 그 작품을 알아볼 말(예: 明日方舟)이 있으면 자동으로 적용돼요.'
                '${KoTr.contextText.trim().isEmpty ? '' : '\n지금 적용 중: ${KoTr.activeWorks.isEmpty ? '없음' : KoTr.activeWorks.map((e) => e.name).join(', ')}'}',
                style: TextStyle(fontSize: 13, color: cs.outline),
              ),
            ),
            if (works.isEmpty)
              const Padding(
                padding: EdgeInsets.all(24),
                child: Center(child: Text('아직 등록한 작품이 없어요. 오른쪽 위 + 로 추가하세요.')),
              ),
            for (final w in works)
              ListTile(
                title: Text(
                  '${w.name}${KoTr.isActive(w) ? '  · 적용 중' : ''}',
                ),
                subtitle: Text(
                  '${_modeNames[(w.mode < 0 || w.mode > 2) ? 0 : w.mode]} · 용어 ${KoTr.parseGlossary(w.glossary).length}개'
                  '${w.mode == 0 ? '\n알아볼 말: ${w.detectWords.join(', ')}' : ''}',
                ),
                isThreeLine: w.mode == 0,
                onTap: () async {
                  await Get.to(() => KoWorkEditPage(work: w));
                  if (mounted) setState(() {});
                },
              ),
          ],
        ),
      ),
    );
  }
}

class KoWorkEditPage extends StatefulWidget {
  const KoWorkEditPage({super.key, required this.work});

  final KoWork work;

  @override
  State<KoWorkEditPage> createState() => _KoWorkEditPageState();
}

class _KoWorkEditPageState extends State<KoWorkEditPage> {
  late final _name = TextEditingController(text: widget.work.name);
  late final _detect = TextEditingController(text: widget.work.detect);
  late final _gloss = TextEditingController(text: widget.work.glossary);
  late int _mode = widget.work.mode;

  @override
  void dispose() {
    _name.dispose();
    _detect.dispose();
    _gloss.dispose();
    super.dispose();
  }

  void _save({bool back = true}) {
    final w = widget.work;
    final n = _name.text.trim();
    if (n.isEmpty) {
      SmartDialog.showToast('작품 이름을 적어주세요');
      return;
    }
    w
      ..name = n
      ..detect = _detect.text.trim()
      ..mode = _mode;
    KoTr.setWorkGlossary(w, _gloss.text.trim());
    if (back) {
      SmartDialog.showToast('저장했어요');
      Get.back();
    }
  }

  @override
  Widget build(BuildContext context) {
    final cs = ColorScheme.of(context);
    final label = TextStyle(fontSize: 13, color: cs.primary);
    return Scaffold(
      appBar: AppBar(
        title: Text('${widget.work.name} 용어집'),
        actions: [
          IconButton(
            tooltip: '삭제',
            icon: const Icon(Icons.delete_outline),
            onPressed: () async {
              final ok = await showDialog<bool>(
                context: context,
                builder: (context) => AlertDialog(
                  title: Text("'${widget.work.name}'와 그 용어집을 지울까요?"),
                  actions: [
                    TextButton(onPressed: () => Get.back(result: false), child: const Text('취소')),
                    TextButton(onPressed: () => Get.back(result: true), child: const Text('지우기')),
                  ],
                ),
              );
              if (ok == true) {
                final keys = KoTr.parseGlossary(widget.work.glossary).map((e) => e.key).toList();
                KoTr.works.remove(widget.work);
                KoTr.saveWorks();
                KoTr.retranslateContaining(keys);
                Get.back();
              }
            },
          ),
          TextButton(onPressed: _save, child: const Text('저장')),
        ],
      ),
      body: SafeArea(
        child: ListView(
          padding: const EdgeInsets.fromLTRB(16, 8, 16, 32),
          children: [
            Text('작품 이름', style: label),
            TextField(controller: _name),
            const SizedBox(height: 12),
            Text('언제 적용할까요?', style: label),
            RadioGroup<int>(
              groupValue: _mode,
              onChanged: (v) => setState(() => _mode = v ?? 0),
              child: Column(
                children: [
                  for (var i = 0; i < 3; i++)
                    RadioListTile<int>(value: i, dense: true, title: Text(_modeNames[i])),
                ],
              ),
            ),
            if (_mode == 0) ...[
              Text('알아볼 말 (쉼표로 구분, 비우면 작품 이름)', style: label),
              TextField(
                controller: _detect,
                decoration: const InputDecoration(hintText: '예) 明日方舟, 方舟, 명일방주'),
              ),
              const Text(
                '영상 제목·태그에 이 중 하나라도 있으면 적용돼요. 빌리빌리는 중국어라 중국어 이름을 꼭 넣어주세요.',
                style: TextStyle(fontSize: 12),
              ),
              const SizedBox(height: 12),
            ],
            Text("용어집 (한 줄에 하나씩 '중국어=한국어')", style: label),
            TextField(
              controller: _gloss,
              minLines: 8,
              maxLines: 20,
              decoration: const InputDecoration(hintText: '阿米娅=아미야\n罗德岛=로도스 아일랜드'),
            ),
            const SizedBox(height: 12),
            Wrap(
              spacing: 8,
              children: [
                OutlinedButton(
                  onPressed: () async {
                    _save(back: false);
                    await autoBuild(context, widget.work);
                    if (mounted) {
                      setState(() {
                        _gloss.text = widget.work.glossary;
                        _detect.text = widget.work.detect;
                      });
                    }
                  },
                  child: const Text('자동 만들기 (웹 검색 + AI)'),
                ),
                OutlinedButton(
                  onPressed: () async {
                    _save(back: false);
                    await checkGlossary(context, widget.work);
                    if (mounted) setState(() => _gloss.text = widget.work.glossary);
                  },
                  child: const Text('용어집 점검'),
                ),
              ],
            ),
          ],
        ),
      ),
    );
  }
}

Future<String?> _askText(BuildContext context, String title, {String hint = ''}) {
  final c = TextEditingController();
  return showDialog<String>(
    context: context,
    builder: (context) => AlertDialog(
      title: Text(title),
      content: TextField(
        controller: c,
        autofocus: true,
        decoration: InputDecoration(hintText: hint),
      ),
      actions: [
        TextButton(onPressed: Get.back, child: const Text('취소')),
        TextButton(onPressed: () => Get.back(result: c.text), child: const Text('확인')),
      ],
    ),
  );
}

// 작품 용어집 자동 만들기: 후보를 보여주고 체크한 것만 넣음
Future<void> autoBuild(BuildContext context, KoWork w) async {
  if (!KoTr.hasAi) {
    SmartDialog.showToast('AI 번역기가 필요해요 (설정 → AI 번역기)');
    return;
  }
  SmartDialog.showLoading(msg: "'${w.name}' 용어를 찾는 중…\n(웹 검색 후 AI가 정리해요)");
  List<KoTerm> terms;
  try {
    terms = await KoLookup.buildWorkGlossary(w.name);
  } catch (e) {
    SmartDialog.dismiss();
    SmartDialog.showToast('실패: $e');
    return;
  }
  SmartDialog.dismiss();
  if (terms.isEmpty) {
    SmartDialog.showToast('찾은 용어가 없어요');
    return;
  }
  if (!context.mounted) return;
  final have = {for (final e in KoTr.parseGlossary(w.glossary)) e.key: e.value};
  final checked = List<bool>.generate(terms.length, (i) => !have.containsKey(terms[i].zh));
  final ok = await showDialog<bool>(
    context: context,
    builder: (context) => StatefulBuilder(
      builder: (context, setState) => AlertDialog(
        title: Text('${w.name} 용어집에 넣을 항목 (틀린 건 체크 해제)'),
        contentPadding: const EdgeInsets.only(top: 8),
        content: SizedBox(
          width: double.maxFinite,
          child: ListView.builder(
            shrinkWrap: true,
            itemCount: terms.length,
            itemBuilder: (context, i) {
              final t = terms[i];
              return CheckboxListTile(
                dense: true,
                value: checked[i],
                onChanged: (v) => setState(() => checked[i] = v ?? false),
                title: Text('${t.ko} ← ${t.zh}${have.containsKey(t.zh) ? '  [이미 있음]' : ''}'),
                subtitle: t.note.isEmpty ? null : Text(t.note),
              );
            },
          ),
        ),
        actions: [
          TextButton(onPressed: () => Get.back(result: false), child: const Text('취소')),
          TextButton(onPressed: () => Get.back(result: true), child: const Text('넣기')),
        ],
      ),
    ),
  );
  if (ok != true) return;
  final lines = w.glossary.trim().isEmpty ? <String>[] : w.glossary.trim().split('\n');
  var added = 0;
  for (var i = 0; i < terms.length; i++) {
    if (!checked[i]) continue;
    final t = terms[i];
    lines.removeWhere((l) {
      final j = l.indexOf('=');
      return j > 0 && l.substring(0, j).trim() == t.zh;
    });
    lines.add('${t.zh}=${t.ko}');
    added++;
  }
  // 첫 항목(작품 이름)의 중국어를 '알아볼 말'에 넣어둠
  final first = terms.first;
  if (checked.first && !w.detectWords.contains(first.zh)) {
    w.detect = [
      if (w.detect.trim().isNotEmpty) w.detect.trim(),
      first.zh,
    ].join(', ');
  }
  KoTr.setWorkGlossary(w, lines.join('\n'));
  SmartDialog.showToast('$added개를 넣었어요');
}

// 용어집 점검 (w 가 null 이면 공통 용어집)
Future<void> checkGlossary(BuildContext context, KoWork? w) async {
  if (!KoTr.hasAi) {
    SmartDialog.showToast('AI 번역기가 필요해요 (설정 → AI 번역기)');
    return;
  }
  final entries = KoTr.parseGlossary(w?.glossary ?? KoTr.commonGlossaryText);
  if (entries.isEmpty) {
    SmartDialog.showToast('점검할 용어집이 비어 있어요');
    return;
  }
  SmartDialog.showLoading(msg: '용어집 점검 중…');
  List<KoCheck> res;
  try {
    res = await KoLookup.check(w?.name, entries);
  } catch (e) {
    SmartDialog.dismiss();
    SmartDialog.showToast('실패: $e');
    return;
  }
  SmartDialog.dismiss();
  if (res.isEmpty) {
    SmartDialog.showToast('바꿀 만한 항목을 못 찾았어요');
    return;
  }
  if (!context.mounted) return;
  final checked = List<bool>.filled(res.length, true);
  final ok = await showDialog<bool>(
    context: context,
    builder: (context) => StatefulBuilder(
      builder: (context, setState) => AlertDialog(
        title: Text('${w?.name ?? '공통'} 용어집 점검'),
        contentPadding: const EdgeInsets.only(top: 8),
        content: SizedBox(
          width: double.maxFinite,
          child: ListView.builder(
            shrinkWrap: true,
            itemCount: res.length,
            itemBuilder: (context, i) {
              final r = res[i];
              final e = entries[r.index];
              return CheckboxListTile(
                dense: true,
                value: checked[i],
                onChanged: (v) => setState(() => checked[i] = v ?? false),
                title: Text('${e.value}: ${e.key} → ${r.zh}'),
                subtitle: r.why.isEmpty ? null : Text(r.why),
              );
            },
          ),
        ),
        actions: [
          TextButton(onPressed: () => Get.back(result: false), child: const Text('취소')),
          TextButton(onPressed: () => Get.back(result: true), child: const Text('바꾸기')),
        ],
      ),
    ),
  );
  if (ok != true) return;
  final fixes = <String, String>{};
  for (var i = 0; i < res.length; i++) {
    if (checked[i]) fixes[entries[res[i].index].key] = res[i].zh;
  }
  final text = (w?.glossary ?? KoTr.commonGlossaryText).split('\n').map((l) {
    final j = l.indexOf('=');
    if (j <= 0) return l;
    final z = l.substring(0, j).trim();
    final nz = fixes[z];
    return nz == null ? l : '$nz=${l.substring(j + 1).trim()}';
  }).join('\n');
  if (w == null) {
    KoTr.setCommonGlossary(text);
  } else {
    KoTr.setWorkGlossary(w, text);
  }
  SmartDialog.showToast('${fixes.length}개를 바꿨어요');
}

// 직접 고친 번역 목록
class KoOverridesPage extends StatefulWidget {
  const KoOverridesPage({super.key});

  @override
  State<KoOverridesPage> createState() => _KoOverridesPageState();
}

class _KoOverridesPageState extends State<KoOverridesPage> {
  @override
  Widget build(BuildContext context) {
    final items = KoTr.overrides.entries.toList().reversed.toList();
    return Scaffold(
      appBar: AppBar(title: const Text('직접 고친 번역')),
      body: SafeArea(
        child: items.isEmpty
            ? const Center(child: Text('아직 고친 번역이 없어요.\n댓글·탄막 메뉴나 제목 길게 누르기의 [번역 고치기]로 고칠 수 있어요.', textAlign: TextAlign.center))
            : ListView.builder(
                itemCount: items.length,
                itemBuilder: (context, i) {
                  final e = items[i];
                  return ListTile(
                    title: Text(e.value),
                    subtitle: Text(e.key, maxLines: 2, overflow: TextOverflow.ellipsis),
                    trailing: IconButton(
                      tooltip: '지우기',
                      icon: const Icon(Icons.delete_outline),
                      onPressed: () {
                        KoTr.setOverride(e.key, null);
                        setState(() {});
                      },
                    ),
                  );
                },
              ),
      ),
    );
  }
}
