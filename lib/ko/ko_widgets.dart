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
  });

  final String data;
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

  void _sync() {
    final want = KoTr.textOn && KoTr.needs(widget.data) && !KoTr.isTranslated(widget.data);
    if (want) {
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
    if (KoTr.isTranslated(widget.data)) {
      KoTr.updates.removeListener(_onUpdate);
      _listening = false;
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
    if (oldWidget.data != widget.data) _sync();
  }

  @override
  void dispose() {
    if (_listening) KoTr.updates.removeListener(_onUpdate);
    super.dispose();
  }

  @override
  Widget build(BuildContext context) {
    final t = KoTr.textOn ? KoTr.show(widget.data) : widget.data;
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

  void _sync() {
    final want = widget.enabled && KoTr.needs(widget.text) && !KoTr.isTranslated(widget.text);
    if (want) {
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
    if (KoTr.isTranslated(widget.text)) {
      KoTr.updates.removeListener(_onUpdate);
      _listening = false;
      setState(() {});
    }
  }

  @override
  void initState() {
    super.initState();
    _sync();
  }

  @override
  void didUpdateWidget(KoBuilder oldWidget) {
    super.didUpdateWidget(oldWidget);
    if (oldWidget.text != widget.text || oldWidget.enabled != widget.enabled) _sync();
  }

  @override
  void dispose() {
    if (_listening) KoTr.updates.removeListener(_onUpdate);
    super.dispose();
  }

  @override
  Widget build(BuildContext context) {
    final ko = widget.enabled && KoTr.isTranslated(widget.text) ? KoTr.show(widget.text) : null;
    return widget.builder(context, ko);
  }
}
