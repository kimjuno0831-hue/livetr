// 화면에 보이는 제목·소개·댓글을 한국어로 바꿔 보여주는 위젯
import 'package:PiliPlus/ko/ko_translate.dart';
import 'package:material_ui/material_ui.dart';

// Text 대신 쓰는 위젯. 번역이 오면 저절로 한국어로 바뀜.
class KoText extends StatefulWidget {
  const KoText(
    this.data, {
    super.key,
    this.style,
    this.strutStyle,
    this.textAlign,
    this.softWrap,
    this.overflow,
    this.maxLines,
    this.enabled,
  });

  final String data;
  // null 이면 '제목·소개·게시글 번역' 설정을 따름
  final bool? enabled;
  final TextStyle? style;
  final StrutStyle? strutStyle;
  final TextAlign? textAlign;
  final bool? softWrap;
  final TextOverflow? overflow;
  final int? maxLines;

  @override
  State<KoText> createState() => _KoTextState();
}

class _KoTextState extends State<KoText> {
  bool _listening = false;
  String? _shown;
  int _ver = 0;

  bool get _on => widget.enabled ?? KoTr.textOn;

  // 번역 대상이면 계속 지켜봄 (나중에 번역을 고쳐도 바로 바뀌게)
  void _sync() {
    final watch = _on && KoTr.needs(widget.data);
    if (watch) {
      KoTr.want(widget.data);
      if (!_listening) {
        KoTr.updates.addListener(_onUpdate);
        _listening = true;
      }
    } else if (_listening) {
      KoTr.updates.removeListener(_onUpdate);
      _listening = false;
    }
  }

  void _onUpdate() {
    if (!mounted) return;
    if (KoTr.show(widget.data) != _shown || KoTr.origVersion != _ver) {
      setState(() {});
    }
  }

  @override
  void initState() {
    super.initState();
    _sync();
  }

  @override
  void didUpdateWidget(KoText oldWidget) {
    super.didUpdateWidget(oldWidget);
    if (oldWidget.data != widget.data || oldWidget.enabled != widget.enabled) {
      _sync();
    }
  }

  @override
  void dispose() {
    if (_listening) KoTr.updates.removeListener(_onUpdate);
    super.dispose();
  }

  @override
  Widget build(BuildContext context) {
    final t = _on ? KoTr.show(widget.data) : widget.data;
    _shown = KoTr.show(widget.data);
    _ver = KoTr.origVersion;
    return Text(
      t,
      style: widget.style,
      strutStyle: widget.strutStyle,
      textAlign: widget.textAlign,
      softWrap: widget.softWrap,
      overflow: widget.overflow,
      maxLines: widget.maxLines,
    );
  }
}

// 번역문을 받아서 직접 그릴 때 (댓글처럼 이모티콘·링크가 섞인 글)
// builder 에는 번역문(아직 없으면 null)이 들어옴
class KoBuilder extends StatefulWidget {
  const KoBuilder({
    super.key,
    required this.text,
    required this.enabled,
    required this.builder,
  });

  final String text;
  final bool enabled;
  final Widget Function(BuildContext context, String? ko) builder;

  @override
  State<KoBuilder> createState() => _KoBuilderState();
}

class _KoBuilderState extends State<KoBuilder> {
  bool _listening = false;
  String? _shown;
  int _ver = 0;

  void _sync() {
    final watch = widget.enabled && KoTr.needs(widget.text);
    if (watch) {
      KoTr.want(widget.text);
      if (!_listening) {
        KoTr.updates.addListener(_onUpdate);
        _listening = true;
      }
    } else if (_listening) {
      KoTr.updates.removeListener(_onUpdate);
      _listening = false;
    }
  }

  void _onUpdate() {
    if (!mounted) return;
    final now = KoTr.isTranslated(widget.text) ? KoTr.show(widget.text) : null;
    if (now != _shown || KoTr.origVersion != _ver) setState(() {});
  }

  @override
  void initState() {
    super.initState();
    _sync();
  }

  @override
  void didUpdateWidget(KoBuilder oldWidget) {
    super.didUpdateWidget(oldWidget);
    if (oldWidget.text != widget.text || oldWidget.enabled != widget.enabled) {
      _sync();
    }
  }

  @override
  void dispose() {
    if (_listening) KoTr.updates.removeListener(_onUpdate);
    super.dispose();
  }

  @override
  Widget build(BuildContext context) {
    final ko = widget.enabled && KoTr.isTranslated(widget.text)
        ? KoTr.show(widget.text)
        : null;
    _shown = KoTr.isTranslated(widget.text) ? KoTr.show(widget.text) : null;
    _ver = KoTr.origVersion;
    return widget.builder(context, ko);
  }
}

// 여러 글을 한꺼번에 지켜보다가 번역이 오면 다시 그림 (답글 미리보기 등)
class KoWatch extends StatefulWidget {
  const KoWatch({
    super.key,
    required this.texts,
    required this.enabled,
    required this.builder,
  });

  final List<String> texts;
  final bool enabled;
  final WidgetBuilder builder;

  @override
  State<KoWatch> createState() => _KoWatchState();
}

class _KoWatchState extends State<KoWatch> {
  bool _listening = false;

  bool get _waiting =>
      widget.enabled &&
      widget.texts.any((t) => KoTr.needs(t) && !KoTr.isTranslated(t));

  void _sync() {
    if (_waiting) {
      if (widget.enabled) widget.texts.forEach(KoTr.want);
      if (!_listening) {
        KoTr.updates.addListener(_onUpdate);
        _listening = true;
      }
    } else if (_listening) {
      KoTr.updates.removeListener(_onUpdate);
      _listening = false;
    }
  }

  void _onUpdate() {
    if (!mounted) return;
    setState(() {});
    if (!_waiting && _listening) {
      KoTr.updates.removeListener(_onUpdate);
      _listening = false;
    }
  }

  @override
  void initState() {
    super.initState();
    _sync();
  }

  @override
  void didUpdateWidget(KoWatch oldWidget) {
    super.didUpdateWidget(oldWidget);
    _sync();
  }

  @override
  void dispose() {
    if (_listening) KoTr.updates.removeListener(_onUpdate);
    super.dispose();
  }

  @override
  Widget build(BuildContext context) => widget.builder(context);
}

// 검색 결과 제목(검색어 강조 포함) 번역: 번역이 오면 강조 없이 번역문 한 덩어리로
typedef KoEm = ({bool isEm, String text});

String koJoin(List<KoEm> parts) => parts.map((e) => e.text).join();

List<KoEm> koEm(List<KoEm> parts, String? ko) =>
    ko == null ? parts : [(isEm: false, text: ko)];
