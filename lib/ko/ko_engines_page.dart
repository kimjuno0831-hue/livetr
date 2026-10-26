// AI 번역기 설정 화면: 등록·순서·켜고 끄기·상태(오늘 사용 횟수, 쉬는 중)
import 'package:PiliPlus/ko/ko_engines.dart';
import 'package:PiliPlus/utils/page_utils.dart';
import 'package:flutter_smart_dialog/flutter_smart_dialog.dart';
import 'package:get/get.dart';
import 'package:material_ui/material_ui.dart';

class KoEnginesPage extends StatefulWidget {
  const KoEnginesPage({super.key});

  @override
  State<KoEnginesPage> createState() => _KoEnginesPageState();
}

class _KoEnginesPageState extends State<KoEnginesPage> {
  void _add() {
    showDialog(
      context: context,
      builder: (context) => AlertDialog(
        title: const Text('어떤 번역기를 추가할까요?'),
        contentPadding: const EdgeInsets.only(top: 8, bottom: 8),
        content: SingleChildScrollView(
          child: Column(
            mainAxisSize: MainAxisSize.min,
            children: [
              for (final p in koPresets)
                ListTile(
                  dense: true,
                  title: Text(p.name),
                  subtitle: Text(p.note, maxLines: 2, overflow: TextOverflow.ellipsis),
                  onTap: () async {
                    Get.back();
                    final e = KoEngine(
                      id: DateTime.now().microsecondsSinceEpoch.toString(),
                      type: p.type,
                      name: p.name.startsWith('직접') ? 'AI 번역기' : p.name,
                      baseUrl: p.baseUrl,
                      models: p.models,
                    );
                    final saved = await Get.to<bool>(
                      () => KoEngineEditPage(engine: e, preset: p, isNew: true),
                    );
                    if (saved == true && mounted) setState(() {});
                  },
                ),
            ],
          ),
        ),
      ),
    );
  }

  String _status(KoEngine e) {
    final parts = <String>[];
    for (final m in e.modelList) {
      final s = KoSlot(e, m);
      final st = KoEngines.stateOf(s.key);
      final n = KoEngines.todayCount(s);
      var line = '$m · 오늘 $n번${st.avgMs != null ? ' · 평균 ${(st.avgMs! / 1000).toStringAsFixed(1)}초' : ''}';
      if (KoEngines.resting(s)) {
        final left = st.restUntil!.difference(DateTime.now());
        final mins = left.inMinutes;
        line += ' · 쉬는 중 (${mins >= 60 ? '${left.inHours}시간' : '${mins < 1 ? 1 : mins}분'} 남음)';
      }
      if (st.lastError != null) line += '\n   최근 오류: ${st.lastError}';
      parts.add(line);
    }
    if (e.key.trim().isEmpty) parts.add('키가 없어요');
    return parts.join('\n');
  }

  @override
  Widget build(BuildContext context) {
    final cs = ColorScheme.of(context);
    final list = KoEngines.list;
    return Scaffold(
      appBar: AppBar(
        title: const Text('AI 번역기'),
        actions: [
          IconButton(
            tooltip: '새로고침',
            icon: const Icon(Icons.refresh),
            onPressed: () => setState(() {}),
          ),
          IconButton(
            tooltip: '추가',
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
                '위에서부터 순서대로 써요. 한도에 걸리면 잠깐 쉬게 하고 다음 것으로 넘어가고, 다 막히면 구글 번역으로 해요. 평균 응답 시간이 짧은 걸 앞에 두면 더 빨라요.\n'
                '번역기 하나에 모델을 쉼표로 여러 개 적으면 모델마다 따로 돌려써요 (제미나이 키 하나로 여러 모델).',
                style: TextStyle(fontSize: 13, color: cs.outline),
              ),
            ),
            if (list.isEmpty)
              const Padding(
                padding: EdgeInsets.all(24),
                child: Center(
                  child: Text('아직 등록한 AI 번역기가 없어요.\n오른쪽 위 + 로 추가하세요.', textAlign: TextAlign.center),
                ),
              ),
            for (var i = 0; i < list.length; i++)
              Card(
                margin: const EdgeInsets.fromLTRB(12, 4, 12, 4),
                child: Column(
                  children: [
                    SwitchListTile(
                      value: list[i].on,
                      onChanged: (v) {
                        list[i].on = v;
                        KoEngines.save();
                        setState(() {});
                      },
                      title: Text('${i + 1}. ${list[i].name}'),
                      subtitle: Text(_status(list[i]), style: const TextStyle(fontSize: 12)),
                      isThreeLine: true,
                    ),
                    Row(
                      mainAxisAlignment: MainAxisAlignment.end,
                      children: [
                        IconButton(
                          tooltip: '위로',
                          icon: const Icon(Icons.arrow_upward, size: 20),
                          onPressed: i == 0
                              ? null
                              : () {
                                  final e = list.removeAt(i);
                                  list.insert(i - 1, e);
                                  KoEngines.save();
                                  setState(() {});
                                },
                        ),
                        IconButton(
                          tooltip: '아래로',
                          icon: const Icon(Icons.arrow_downward, size: 20),
                          onPressed: i == list.length - 1
                              ? null
                              : () {
                                  final e = list.removeAt(i);
                                  list.insert(i + 1, e);
                                  KoEngines.save();
                                  setState(() {});
                                },
                        ),
                        TextButton(
                          onPressed: () async {
                            await Get.to<bool>(() => KoEngineEditPage(engine: list[i]));
                            if (mounted) setState(() {});
                          },
                          child: const Text('설정'),
                        ),
                        const SizedBox(width: 8),
                      ],
                    ),
                  ],
                ),
              ),
          ],
        ),
      ),
    );
  }
}

class KoEngineEditPage extends StatefulWidget {
  const KoEngineEditPage({
    super.key,
    required this.engine,
    this.preset,
    this.isNew = false,
  });

  final KoEngine engine;
  final KoPreset? preset;
  final bool isNew;

  @override
  State<KoEngineEditPage> createState() => _KoEngineEditPageState();
}

class _KoEngineEditPageState extends State<KoEngineEditPage> {
  late final _name = TextEditingController(text: widget.engine.name);
  late final _key = TextEditingController(text: widget.engine.key);
  late final _base = TextEditingController(text: widget.engine.baseUrl);
  late final _models = TextEditingController(text: widget.engine.models);
  late final _account = TextEditingController(text: widget.engine.accountId);
  bool _busy = false;
  String _info = '';

  KoPreset? get _preset =>
      widget.preset ??
      koPresets.where((p) => p.name == widget.engine.name).firstOrNull;

  @override
  void dispose() {
    _name.dispose();
    _key.dispose();
    _base.dispose();
    _models.dispose();
    _account.dispose();
    super.dispose();
  }

  // 입력 칸 → 번역기 (저장 전 시험용으로도 씀)
  KoEngine _draft() => KoEngine(
    id: widget.engine.id,
    type: widget.engine.type,
    name: _name.text.trim().isEmpty ? widget.engine.name : _name.text.trim(),
    baseUrl: _base.text.trim(),
    key: _key.text.trim(),
    models: _models.text.trim(),
    accountId: _account.text.trim(),
    on: widget.engine.on,
  );

  void _save() {
    final d = _draft();
    if (d.key.isEmpty) {
      SmartDialog.showToast('키를 넣어주세요');
      return;
    }
    if (d.modelList.isEmpty) {
      SmartDialog.showToast('모델을 하나 이상 적어주세요 ([모델 목록 불러오기]로 고를 수 있어요)');
      return;
    }
    final e = widget.engine
      ..name = d.name
      ..baseUrl = d.baseUrl
      ..key = d.key
      ..models = d.models
      ..accountId = d.accountId;
    if (widget.isNew && !KoEngines.list.contains(e)) KoEngines.list.add(e);
    KoEngines.save();
    SmartDialog.showToast('저장했어요');
    Get.back(result: true);
  }

  Future<void> _run(Future<void> Function() f) async {
    if (_busy) return;
    setState(() => _busy = true);
    try {
      await f();
    } catch (e) {
      if (mounted) setState(() => _info = '실패: $e');
    } finally {
      if (mounted) setState(() => _busy = false);
    }
  }

  void _pickModels() => _run(() async {
    final d = _draft();
    if (d.key.isEmpty) {
      SmartDialog.showToast('먼저 키를 넣어주세요');
      return;
    }
    setState(() => _info = '모델 목록 불러오는 중…');
    final all = await KoEngines.fetchModels(d);
    if (!mounted) return;
    setState(() => _info = '');
    if (all.isEmpty) {
      SmartDialog.showToast('모델 목록이 비어 있어요');
      return;
    }
    final chosen = d.modelList.toSet();
    final filter = TextEditingController(
      text: d.name == 'Groq'
          ? 'qwen'
          : d.name == 'OpenRouter'
          ? ':free'
          : '',
    );
    final ok = await showDialog<bool>(
      context: context,
      builder: (context) => StatefulBuilder(
        builder: (context, setDlg) {
          final f = filter.text.trim().toLowerCase();
          final shown = all.where((m) => f.isEmpty || m.toLowerCase().contains(f)).toList();
          return AlertDialog(
            title: const Text('모델 고르기 (위에서부터 순서대로 돌려써요)'),
            contentPadding: const EdgeInsets.only(top: 8),
            content: SizedBox(
              width: double.maxFinite,
              child: Column(
                mainAxisSize: MainAxisSize.min,
                children: [
                  Padding(
                    padding: const EdgeInsets.symmetric(horizontal: 16),
                    child: TextField(
                      controller: filter,
                      decoration: const InputDecoration(hintText: '이름으로 걸러보기', isDense: true),
                      onChanged: (_) => setDlg(() {}),
                    ),
                  ),
                  Flexible(
                    child: ListView.builder(
                      shrinkWrap: true,
                      itemCount: shown.length,
                      itemBuilder: (context, i) {
                        final m = shown[i];
                        final note = KoEngines.modelNote(d, m);
                        return CheckboxListTile(
                          dense: true,
                          subtitle: note == null ? null : Text(note),
                          value: chosen.contains(m),
                          onChanged: (v) => setDlg(() {
                            if (v == true) {
                              chosen.add(m);
                            } else {
                              chosen.remove(m);
                            }
                          }),
                          title: Text(m),
                        );
                      },
                    ),
                  ),
                ],
              ),
            ),
            actions: [
              TextButton(onPressed: () => Get.back(result: false), child: const Text('취소')),
              TextButton(onPressed: () => Get.back(result: true), child: const Text('확인')),
            ],
          );
        },
      ),
    );
    filter.dispose();
    if (ok == true) {
      // 원래 적혀 있던 순서를 지키고 새로 고른 건 뒤에
      final keep = d.modelList.where(chosen.contains).toList();
      final add = chosen.where((m) => !keep.contains(m)).toList();
      setState(() => _models.text = [...keep, ...add].join(', '));
    }
  });

  void _test() => _run(() async {
    final d = _draft();
    if (d.key.isEmpty || d.modelList.isEmpty) {
      SmartDialog.showToast('키와 모델을 먼저 넣어주세요');
      return;
    }
    setState(() => _info = '시험 중…');
    final r = await KoEngines.test(d);
    if (mounted) setState(() => _info = r);
  });

  @override
  Widget build(BuildContext context) {
    final cs = ColorScheme.of(context);
    final label = TextStyle(fontSize: 13, color: cs.primary);
    final p = _preset;
    final type = widget.engine.type;
    final custom = type == 'openai' && (p == null || p.baseUrl.isEmpty);
    return Scaffold(
      appBar: AppBar(
        title: Text(widget.isNew ? '${widget.engine.name} 추가' : widget.engine.name),
        actions: [
          if (!widget.isNew)
            IconButton(
              tooltip: '삭제',
              icon: const Icon(Icons.delete_outline),
              onPressed: () async {
                final ok = await showDialog<bool>(
                  context: context,
                  builder: (context) => AlertDialog(
                    title: Text("'${widget.engine.name}'를 지울까요?"),
                    actions: [
                      TextButton(onPressed: () => Get.back(result: false), child: const Text('취소')),
                      TextButton(onPressed: () => Get.back(result: true), child: const Text('지우기')),
                    ],
                  ),
                );
                if (ok == true) {
                  KoEngines.list.remove(widget.engine);
                  KoEngines.save();
                  Get.back(result: true);
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
            if (p != null && p.note.isNotEmpty)
              Text(p.note, style: TextStyle(fontSize: 13, color: cs.outline)),
            if (p != null && p.keyUrl.isNotEmpty)
              Align(
                alignment: Alignment.centerLeft,
                child: TextButton.icon(
                  onPressed: () => PageUtils.launchURL(p.keyUrl),
                  icon: const Icon(Icons.open_in_new, size: 18),
                  label: const Text('키 받으러 가기'),
                ),
              ),
            const SizedBox(height: 8),
            Text('이름', style: label),
            TextField(controller: _name),
            const SizedBox(height: 12),
            Text(type == 'cloudflare' ? 'API 토큰' : 'API 키', style: label),
            TextField(
              controller: _key,
              decoration: const InputDecoration(hintText: '붙여넣기'),
            ),
            if (type == 'cloudflare') ...[
              const SizedBox(height: 12),
              Text('계정 ID (Account ID)', style: label),
              TextField(controller: _account),
            ],
            if (custom) ...[
              const SizedBox(height: 12),
              Text('주소 (…/v1 까지)', style: label),
              TextField(
                controller: _base,
                decoration: const InputDecoration(hintText: 'https://…/v1'),
              ),
            ],
            const SizedBox(height: 12),
            Text('모델 (여러 개면 쉼표로, 앞에서부터 돌려써요)', style: label),
            TextField(
              controller: _models,
              minLines: 1,
              maxLines: 4,
            ),
            const SizedBox(height: 8),
            Wrap(
              spacing: 8,
              children: [
                OutlinedButton(
                  onPressed: _busy ? null : _pickModels,
                  child: const Text('모델 목록 불러오기'),
                ),
                OutlinedButton(
                  onPressed: _busy ? null : _test,
                  child: const Text('연결 테스트'),
                ),
              ],
            ),
            if (_busy) const LinearProgressIndicator(),
            if (_info.isNotEmpty)
              Container(
                margin: const EdgeInsets.only(top: 8),
                padding: const EdgeInsets.all(10),
                decoration: BoxDecoration(
                  color: cs.secondaryContainer,
                  borderRadius: BorderRadius.circular(8),
                ),
                child: Text(_info, style: TextStyle(color: cs.onSecondaryContainer)),
              ),
          ],
        ),
      ),
    );
  }
}
