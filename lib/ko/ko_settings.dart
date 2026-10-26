// 한국어 번역 설정 항목 (설정 → 弹幕设置 맨 위)
import 'package:PiliPlus/ko/ko_engines.dart';
import 'package:PiliPlus/ko/ko_engines_page.dart';
import 'package:PiliPlus/ko/ko_translate.dart';
import 'package:PiliPlus/ko/ko_works.dart';
import 'package:PiliPlus/pages/setting/models/model.dart';
import 'package:PiliPlus/utils/storage.dart';
import 'package:flutter_smart_dialog/flutter_smart_dialog.dart';
import 'package:get/get.dart';
import 'package:material_ui/material_ui.dart';

List<SettingsModel> get koSettings => [
  const SwitchModel(
    title: '한국어 번역',
    subtitle: '영상 제목·댓글·탄막·자막을 한국어로 바꿔서 보여줘요',
    leading: Icon(Icons.translate),
    setKey: KoTr.kEnable,
    defaultVal: true,
  ),
  NormalModel(
    title: 'AI 번역기',
    getSubtitle: () {
      final engines = KoEngines.list.where((e) => e.usable).toList();
      if (engines.isEmpty) {
        return '없음 — 지금은 전부 구글 번역. 제미나이·Groq·NVIDIA 등 무료 키를 넣으면 밈·은어를 뜻이 통하게 의역해요';
      }
      final err = KoTr.lastAiError;
      return '${engines.map((e) => e.name).join(' → ')} (막히면 다음 것, 다 막히면 구글)'
          '${err != null ? '\n최근 오류: $err' : ''}';
    },
    leading: const Icon(Icons.auto_awesome_outlined),
    onTap: (context, setState) async {
      await Get.to(() => const KoEnginesPage());
      setState();
    },
  ),
  NormalModel(
    title: 'AI 번역기를 쓸 항목',
    getSubtitle: () {
      const names = {
        'dm': '탄막',
        'live': '라이브 탄막',
        'sub': '자막',
        'text': '제목·소개·검색',
        'reply': '댓글',
      };
      final on = names.entries
          .where((e) => GStorage.setting.get(_aiKey(e.key), defaultValue: _aiDef(e.key)) == true)
          .map((e) => e.value)
          .toList();
      return on.isEmpty ? '없음 (전부 구글 번역)' : '${on.join(', ')} · 나머지는 구글 번역';
    },
    leading: const Icon(Icons.tune),
    onTap: _pickAiKinds,
  ),
  NormalModel(
    title: '구글 번역 상태',
    getSubtitle: () =>
        '${KoTr.googleStatus}\n요청이 너무 많으면 구글이 잠깐 막아요. 막혀 있는 동안은 AI 번역기로 대신해요 (누르면 바로 다시 시도)',
    leading: const Icon(Icons.g_translate),
    onTap: (context, setState) {
      KoTr.resetGoogle();
      SmartDialog.showToast('구글 번역을 다시 시도해요');
      setState();
    },
  ),
  const SwitchModel(
    title: '먼저 구글, 나중에 AI로 다듬기',
    subtitle: '댓글·제목·자막을 구글 번역으로 바로 보여주고, AI 번역이 오면 바꿔요 (탄막 제외)',
    leading: Icon(Icons.bolt_outlined),
    setKey: KoTr.kRefine,
    defaultVal: true,
  ),
  const SwitchModel(
    title: '제목·소개·게시글 번역',
    subtitle: '영상 제목, 소개, 게시글 등을 한국어로',
    leading: Icon(Icons.title),
    setKey: KoTr.kText,
    defaultVal: true,
  ),
  const SwitchModel(
    title: '댓글 번역',
    subtitle: "댓글마다 '원문 보기'로 원래 글을 볼 수 있어요",
    leading: Icon(Icons.comment_outlined),
    setKey: KoTr.kReply,
    defaultVal: true,
  ),
  const SwitchModel(
    title: '검색 화면 번역',
    subtitle: '인기 검색어·추천 검색어·자동완성을 한국어로 (누르면 원래 중국어로 검색)',
    leading: Icon(Icons.manage_search),
    setKey: KoTr.kSearch,
    defaultVal: true,
  ),
  const SwitchModel(
    title: '한국어로 검색',
    subtitle: '검색어에 한국어가 있으면 중국어로 바꿔서 검색해요',
    leading: Icon(Icons.search),
    setKey: KoTr.kSearchKo,
    defaultVal: true,
  ),
  const SwitchModel(
    title: '탄막 번역',
    leading: Icon(Icons.chat_bubble_outline),
    setKey: KoTr.kDm,
    defaultVal: true,
  ),
  const SwitchModel(
    title: '번역 안 된 탄막은 숨기기',
    subtitle: '켜면 중국어 탄막이 섞여 나오지 않아요 (영상 시작 직후엔 탄막이 늦게 나올 수 있음)',
    leading: Icon(Icons.visibility_off_outlined),
    setKey: KoTr.kDmHide,
    defaultVal: false,
  ),
  const SwitchModel(
    title: '자막 번역',
    subtitle: '자막(CC·AI 자막)을 한국어로. 자막을 다시 켜면 적용',
    leading: Icon(Icons.subtitles_outlined),
    setKey: KoTr.kSub,
    defaultVal: true,
  ),
  const SwitchModel(
    title: '자막에 중국어 원문도 같이',
    leading: Icon(Icons.closed_caption_outlined),
    setKey: KoTr.kSubOrig,
    defaultVal: true,
  ),
  const SwitchModel(
    title: '라이브 탄막 번역',
    subtitle: '라이브 방송 탄막·채팅을 한국어로 (약간 늦게 나와요)',
    leading: Icon(Icons.live_tv_outlined),
    setKey: KoTr.kLive,
    defaultVal: true,
  ),
  NormalModel(
    title: '공통 용어집',
    getSubtitle: () {
      final n = KoTr.parseGlossary(KoTr.commonGlossaryText).length;
      return n == 0
          ? "작품과 상관없이 늘 쓰는 단어. 한 줄에 하나씩 '중국어=한국어' (예: 主播=스트리머)"
          : '$n개';
    },
    leading: const Icon(Icons.book_outlined),
    onTap: (context, setState) => _editText(
      context,
      setState,
      title: "공통 용어집 (한 줄에 하나씩 '중국어=한국어')",
      key: KoTr.kGlossary,
      hint: '主播=스트리머\n弹幕=탄막',
      multiline: true,
      onSave: KoTr.setCommonGlossary,
    ),
  ),
  NormalModel(
    title: '작품별 용어집',
    getSubtitle: () {
      final n = KoTr.works.length;
      final act = KoTr.activeWorks.map((e) => e.name).join(', ');
      return n == 0
          ? '작품마다 용어집을 따로 (영상 제목·태그로 자동 적용, 자동 만들기 가능)'
          : '$n개${act.isEmpty ? '' : ' · 지금 적용 중: $act'}';
    },
    leading: const Icon(Icons.collections_bookmark_outlined),
    onTap: (context, setState) async {
      await Get.to(() => const KoWorksPage());
      setState();
    },
  ),
  NormalModel(
    title: '공통 용어집 점검',
    subtitle: '웹 검색·AI로 표기가 틀린 항목 찾기 (제미나이 키 필요)',
    leading: const Icon(Icons.fact_check_outlined),
    onTap: (context, setState) async {
      await checkGlossary(context, null);
      setState();
    },
  ),
  NormalModel(
    title: '직접 고친 번역',
    getSubtitle: () => '${KoTr.overrides.length}개 · 댓글·탄막 메뉴나 제목 길게 누르기의 [번역 고치기]로 고친 것',
    leading: const Icon(Icons.edit_note),
    onTap: (context, setState) async {
      await Get.to(() => const KoOverridesPage());
      setState();
    },
  ),
  NormalModel(
    title: '번역 테스트',
    subtitle: '예시 문장을 지금 설정으로 번역해서 보여줘요 (제목·댓글 설정 기준)',
    leading: const Icon(Icons.play_circle_outline),
    onTap: (context, setState) async {
      SmartDialog.showLoading(msg: '번역 중…');
      final r = await KoTr.test();
      SmartDialog.dismiss();
      if (!context.mounted) return;
      showDialog(
        context: context,
        builder: (context) => AlertDialog(
          title: const Text('번역 테스트'),
          content: Text(r),
          actions: [TextButton(onPressed: Get.back, child: const Text('확인'))],
        ),
      );
    },
  ),
];

String _aiKey(String kind) => switch (kind) {
  'dm' => KoTr.kAiDm,
  'live' => KoTr.kAiLive,
  'sub' => KoTr.kAiSub,
  'reply' => KoTr.kAiReply,
  _ => KoTr.kAiText,
};

bool _aiDef(String kind) => kind == 'sub' || kind == 'text' || kind == 'reply';

void _pickAiKinds(BuildContext context, VoidCallback setState) {
  const items = [
    ('dm', '탄막', '양이 아주 많아서 한도가 빨리 닳아요. 기본은 구글'),
    ('live', '라이브 탄막', '빨리 떠야 해서 기본은 구글'),
    ('sub', '자막', ''),
    ('text', '제목·소개·검색 화면', ''),
    ('reply', '댓글', ''),
  ];
  showDialog(
    context: context,
    builder: (context) => StatefulBuilder(
      builder: (context, setDlg) => AlertDialog(
        title: const Text('AI 번역기를 쓸 항목'),
        contentPadding: const EdgeInsets.only(top: 8),
        content: SingleChildScrollView(
          child: Column(
            mainAxisSize: MainAxisSize.min,
            children: [
              for (final (k, name, note) in items)
                CheckboxListTile(
                  dense: true,
                  title: Text(name),
                  subtitle: note.isEmpty ? null : Text(note),
                  value: GStorage.setting.get(_aiKey(k), defaultValue: _aiDef(k)) == true,
                  onChanged: (v) {
                    GStorage.setting.put(_aiKey(k), v ?? false);
                    setDlg(() {});
                  },
                ),
              const Padding(
                padding: EdgeInsets.fromLTRB(24, 4, 24, 0),
                child: Text(
                  '체크 안 한 항목은 구글 번역으로 해요. AI 번역기가 다 막혀도 구글로 넘어가요.',
                  style: TextStyle(fontSize: 12),
                ),
              ),
            ],
          ),
        ),
        actions: [TextButton(onPressed: Get.back, child: const Text('확인'))],
      ),
    ),
  ).whenComplete(setState);
}

void _editText(
  BuildContext context,
  VoidCallback setState, {
  required String title,
  required String key,
  String hint = '',
  bool multiline = false,
  ValueChanged<String>? onSave,
}) {
  final ctrl = TextEditingController(
    text: (GStorage.setting.get(key, defaultValue: '') as String?) ?? '',
  );
  showDialog(
    context: context,
    builder: (context) => AlertDialog(
      title: Text(title),
      content: TextField(
        controller: ctrl,
        autofocus: true,
        minLines: multiline ? 6 : 1,
        maxLines: multiline ? 12 : 1,
        decoration: InputDecoration(hintText: hint),
      ),
      actions: [
        TextButton(onPressed: Get.back, child: const Text('취소')),
        TextButton(
          onPressed: () {
            if (onSave != null) {
              onSave(ctrl.text.trim());
            } else {
              GStorage.setting.put(key, ctrl.text.trim());
            }
            Get.back();
            setState();
          },
          child: const Text('저장'),
        ),
      ],
    ),
  );
}
