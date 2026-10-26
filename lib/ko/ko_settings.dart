// 한국어 번역 설정 항목 (설정 → 弹幕设置 맨 위)
import 'package:PiliPlus/ko/ko_translate.dart';
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
    title: '용어집',
    getSubtitle: () {
      final n = KoTr.glossary.length;
      return n == 0 ? "한 줄에 하나씩 '중국어=한국어' (예: 阿米娅=아미야)" : '$n개';
    },
    leading: const Icon(Icons.book_outlined),
    onTap: (context, setState) => _editText(
      context,
      setState,
      title: "용어집 (한 줄에 하나씩 '중국어=한국어')",
      key: KoTr.kGlossary,
      hint: '阿米娅=아미야\n罗德岛=로도스 아일랜드',
      multiline: true,
      onSaved: KoTr.clearCache,
    ),
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
  VoidCallback? onSaved,
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
            GStorage.setting.put(key, ctrl.text.trim());
            onSaved?.call();
            Get.back();
            setState();
          },
          child: const Text('저장'),
        ),
      ],
    ),
  );
}
