// 한국어 번역 설정 항목 (설정 → 弹幕设置 맨 위)
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
    title: '번역기',
    getSubtitle: () => KoTr.engine == 'gemini'
        ? '제미나이 — 밈·은어를 뜻이 통하게 의역 (무료 키 필요)'
              '${KoTr.lastGeminiError != null ? '\n최근 제미나이 실패: ${KoTr.lastGeminiError} → 구글로 대신 번역 중' : ''}'
        : '구글 번역 — 무료, 키 필요 없음, 빠름',
    leading: const Icon(Icons.language),
    onTap: _pickEngine,
  ),
  NormalModel(
    title: '제미나이 API 키',
    getSubtitle: () => KoTr.geminiKey.isEmpty
        ? '없음 (aistudio.google.com/apikey 에서 무료 발급)'
        : '입력됨 (${KoTr.geminiKey.length > 6 ? KoTr.geminiKey.substring(0, 6) : KoTr.geminiKey}…)',
    leading: const Icon(Icons.vpn_key_outlined),
    onTap: (context, setState) => _editText(
      context,
      setState,
      title: '제미나이 API 키',
      key: KoTr.kKey,
      hint: 'AIza로 시작하는 키',
    ),
  ),
  NormalModel(
    title: '제미나이 모델',
    getSubtitle: () => KoTr.geminiModel,
    leading: const Icon(Icons.memory),
    onTap: (context, setState) => _editText(
      context,
      setState,
      title: '제미나이 모델 이름',
      key: KoTr.kModel,
      hint: KoTr.defaultModel,
    ),
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
    subtitle: '웹 검색·제미나이로 표기가 틀린 항목 찾기 (제미나이 키 필요)',
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
    subtitle: '탄막 예시 몇 개를 번역해서 보여줘요',
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

void _pickEngine(BuildContext context, VoidCallback setState) {
  void pick(String v) {
    GStorage.setting.put(KoTr.kEngine, v);
    KoTr.clearCache();
    Get.back();
    setState();
    if (v == 'gemini' && KoTr.geminiKey.isEmpty) {
      SmartDialog.showToast('제미나이 API 키를 입력해야 써져요');
    }
  }

  showDialog(
    context: context,
    builder: (context) => AlertDialog(
      title: const Text('번역기'),
      content: Column(
        mainAxisSize: MainAxisSize.min,
        crossAxisAlignment: CrossAxisAlignment.stretch,
        children: [
          TextButton(
            onPressed: () => pick('google'),
            child: const Text('구글 번역 (무료, 키 없음, 빠름)'),
          ),
          TextButton(
            onPressed: () => pick('gemini'),
            child: const Text('제미나이 (밈·은어 의역, 무료 키 필요)'),
          ),
        ],
      ),
    ),
  );
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
