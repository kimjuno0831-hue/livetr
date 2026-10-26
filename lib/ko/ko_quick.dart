// 번역 빠른 설정 (플레이어 위쪽 文A 버튼, 댓글 목록 위 文A 버튼, 검색 화면 文A 길게 누르기)
import 'package:PiliPlus/ko/ko_engines_page.dart';
import 'package:PiliPlus/ko/ko_translate.dart';
import 'package:PiliPlus/utils/storage.dart';
import 'package:get/get.dart';
import 'package:material_ui/material_ui.dart';

abstract final class KoQuick {
  static bool _get(String k, bool def) {
    try {
      final v = GStorage.setting.get(k, defaultValue: def);
      return v is bool ? v : def;
    } catch (_) {
      return def;
    }
  }

  static void _put(String k, bool v) {
    try {
      GStorage.setting.put(k, v);
    } catch (_) {}
  }

  static Future<void> show(BuildContext context) {
    return showModalBottomSheet(
      context: context,
      useSafeArea: true,
      isScrollControlled: true,
      constraints: const BoxConstraints(maxWidth: 640),
      builder: (context) => StatefulBuilder(
        builder: (context, setState) {
          final cs = ColorScheme.of(context);
          final style = KoTr.style;
          final on = KoTr.enabled;

          Widget sw(String title, String key, bool def, {String? sub, VoidCallback? after}) =>
              SwitchListTile(
                dense: true,
                title: Text(title),
                subtitle: sub == null ? null : Text(sub, style: const TextStyle(fontSize: 12)),
                value: _get(key, def),
                onChanged: on
                    ? (v) {
                        _put(key, v);
                        after?.call();
                        KoTr.updates.value++;
                        setState(() {});
                      }
                    : null,
              );

          return SingleChildScrollView(
            child: Padding(
              padding: const EdgeInsets.fromLTRB(0, 8, 0, 16),
              child: Column(
                mainAxisSize: MainAxisSize.min,
                crossAxisAlignment: CrossAxisAlignment.stretch,
                children: [
                  Center(
                    child: Container(
                      width: 32,
                      height: 4,
                      decoration: BoxDecoration(
                        color: cs.outline.withValues(alpha: 0.4),
                        borderRadius: BorderRadius.circular(2),
                      ),
                    ),
                  ),
                  SwitchListTile(
                    title: const Text('한국어 번역', style: TextStyle(fontWeight: FontWeight.bold)),
                    value: on,
                    onChanged: (v) {
                      _put(KoTr.kEnable, v);
                      KoTr.updates.value++;
                      KoTr.subRefresh.value++;
                      setState(() {});
                    },
                  ),
                  Padding(
                    padding: const EdgeInsets.fromLTRB(16, 4, 16, 4),
                    child: Text('번역 말투', style: TextStyle(fontSize: 13, color: cs.primary)),
                  ),
                  Padding(
                    padding: const EdgeInsets.symmetric(horizontal: 12),
                    child: Row(
                      children: [
                        for (var i = 0; i < 3; i++)
                          Expanded(
                            child: Padding(
                              padding: const EdgeInsets.symmetric(horizontal: 4),
                              child: i == style
                                  ? FilledButton(
                                      onPressed: on ? () {} : null,
                                      child: Text(KoTr.styleNames[i]),
                                    )
                                  : OutlinedButton(
                                      onPressed: on
                                          ? () {
                                              KoTr.setStyle(i);
                                              setState(() {});
                                            }
                                          : null,
                                      child: Text(KoTr.styleNames[i]),
                                    ),
                            ),
                          ),
                      ],
                    ),
                  ),
                  Padding(
                    padding: const EdgeInsets.fromLTRB(16, 4, 16, 8),
                    child: Text(
                      switch (style) {
                        0 => '뜻을 바꾸지 않고 원문대로. 밈도 풀어 쓰지 않아요 (꼭 필요하면 괄호로 뜻만).',
                        1 => '문장은 원문대로, 한국 사람이 모를 밈·은어만 풀어줘요.',
                        _ => '한국 커뮤니티 말투로 자연스럽게 의역해요.',
                      },
                      style: TextStyle(fontSize: 12, color: cs.outline),
                    ),
                  ),
                  const Divider(height: 1),
                  sw('탄막 번역', KoTr.kDm, true),
                  sw('자막 번역', KoTr.kSub, true, after: () => KoTr.subRefresh.value++),
                  sw(
                    '자막에 중국어 원문도 같이',
                    KoTr.kSubOrig,
                    true,
                    after: () => KoTr.subRefresh.value++,
                  ),
                  sw('제목·소개·게시글 번역', KoTr.kText, true),
                  sw('댓글 번역', KoTr.kReply, true),
                  const Divider(height: 1),
                  Padding(
                    padding: const EdgeInsets.fromLTRB(16, 10, 16, 0),
                    child: Text(
                      KoTr.hasAi ? 'AI 번역기로 번역할 것 (끄면 구글 번역)' : 'AI 번역기가 없어서 지금은 전부 구글 번역이에요',
                      style: TextStyle(fontSize: 13, color: cs.primary),
                    ),
                  ),
                  if (KoTr.hasAi) ...[
                    sw('탄막', KoTr.kAiDm, false, sub: '양이 많아서 AI 한도가 빨리 닳아요'),
                    sw('라이브 탄막', KoTr.kAiLive, false),
                    sw('자막', KoTr.kAiSub, true),
                    sw('제목·소개·검색', KoTr.kAiText, true),
                    sw('댓글', KoTr.kAiReply, true),
                    sw('먼저 구글, 나중에 AI로 다듬기', KoTr.kRefine, true),
                  ],
                  Padding(
                    padding: const EdgeInsets.fromLTRB(12, 8, 12, 0),
                    child: Wrap(
                      spacing: 8,
                      children: [
                        TextButton.icon(
                          onPressed: () {
                            Get.back();
                            Get.to(() => const KoEnginesPage());
                          },
                          icon: const Icon(Icons.auto_awesome_outlined, size: 18),
                          label: const Text('AI 번역기 관리'),
                        ),
                      ],
                    ),
                  ),
                  Padding(
                    padding: const EdgeInsets.fromLTRB(16, 4, 16, 0),
                    child: Text(
                      '구글 번역: ${KoTr.googleStatus}',
                      style: TextStyle(fontSize: 12, color: cs.outline),
                    ),
                  ),
                ],
              ),
            ),
          );
        },
      ),
    );
  }
}
