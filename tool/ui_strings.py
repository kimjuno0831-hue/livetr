#!/usr/bin/env python3
# PiliPlus 고정 문구(중국어 문자열 리터럴) 추출/치환 도구
#   extract <lib> <out.json>       : 번역할 문구 목록 만들기
#   apply <lib> <map.json>         : 번역 사전으로 소스 문구 바꾸기
# 문자열 안의 ${...} / $name 은 {0}, {1} 같은 자리표시로 바꿔서 다룸.
import json, os, re, sys

CJK = re.compile(r'[一-鿿]')

def scan(src):
    """Dart 소스에서 문자열 리터럴 위치를 찾음. [(start, end, quote, raw, parts)]
    parts: [('t', text) | ('i', interpolation_source)] (따옴표 안쪽 내용)"""
    out = []
    i, n = 0, len(src)
    def skip_comment(i):
        if src.startswith('//', i):
            j = src.find('\n', i)
            return n if j < 0 else j
        if src.startswith('/*', i):
            depth, j = 1, i + 2
            while j < n and depth:
                if src.startswith('/*', j): depth += 1; j += 2
                elif src.startswith('*/', j): depth -= 1; j += 2
                else: j += 1
            return j
        return None
    def read_string(i):
        # i 는 따옴표(또는 r) 위치
        start = i
        raw = False
        if src[i] == 'r':
            raw = True; i += 1
        q = src[i]
        triple = src.startswith(q * 3, i)
        qq = q * 3 if triple else q
        i += len(qq)
        parts, buf = [], []
        while i < n:
            if src.startswith(qq, i):
                if buf: parts.append(('t', ''.join(buf)))
                return i + len(qq), (start, i + len(qq), qq, raw, parts)
            c = src[i]
            if not triple and c == '\n':
                return i, None  # 깨진 문자열
            if not raw and c == '\\':
                buf.append(src[i:i+2]); i += 2; continue
            if not raw and c == '$':
                if src.startswith('${', i):
                    if buf: parts.append(('t', ''.join(buf))); buf = []
                    j, depth = i + 2, 1
                    while j < n and depth:
                        cm = skip_comment(j)
                        if cm is not None: j = cm; continue
                        cj = src[j]
                        if cj in '\'"' or (cj == 'r' and j + 1 < n and src[j+1] in '\'"'):
                            j2, _ = read_string(j); j = j2; continue
                        if cj == '{': depth += 1
                        elif cj == '}': depth -= 1
                        j += 1
                    parts.append(('i', src[i:j])); i = j; continue
                m = re.match(r'\$[A-Za-z_]\w*', src[i:])
                if m:
                    if buf: parts.append(('t', ''.join(buf))); buf = []
                    parts.append(('i', m.group(0))); i += len(m.group(0)); continue
            buf.append(c); i += 1
        return i, None
    while i < n:
        cm = skip_comment(i)
        if cm is not None:
            i = cm; continue
        c = src[i]
        if c in '\'"' or (c == 'r' and i + 1 < n and src[i+1] in '\'"' and (i == 0 or not (src[i-1].isalnum() or src[i-1] == '_'))):
            j, lit = read_string(i)
            if lit: out.append(lit)
            i = max(j, i + 1); continue
        i += 1
    return out

def template(parts):
    t, args, k = [], [], 0
    for kind, v in parts:
        if kind == 't':
            t.append(v.replace('{', '{{').replace('}', '}}'))
        else:
            t.append('{%d}' % k); args.append(v); k += 1
    return ''.join(t), args

# 서버에서 오는 중국어와 비교하는 줄은 건드리지 않음
LOGIC = re.compile(r"""(==|!=|\bcase\b|\.contains\(|\.startsWith\(|\.endsWith\(|\.indexOf\(|\.replaceAll\(|\.split\(|RegExp\(|\.hasMatch\(|\.firstWhere\()""")
SWITCH_PAT = re.compile(r"""^\s*(r?['"]).*\1\s*(=>|\|\|)""")
# 앱 안에서 만든 글자끼리 비교하는 곳 (같이 번역해야 맞음)
INTERNAL_OK = {
    'lib/pages/video/view.dart',
    'lib/pages/member_profile/view.dart',
}
SKIP_FILES = ('lib/grpc/', 'lib/ko/', 'lib/scripts/')

def line_of(src, pos):
    a = src.rfind('\n', 0, pos) + 1
    b = src.find('\n', pos)
    return src[a: n if (n := len(src)) and b < 0 else b]

def candidates(path, src):
    for (s, e, qq, raw, parts) in scan(src):
        text = ''.join(v for k, v in parts if k == 't')
        if not CJK.search(text):
            continue
        line = line_of(src, s)
        if path not in INTERNAL_OK and (LOGIC.search(line) or SWITCH_PAT.match(line)):
            continue
        # 줄 맨 앞이 import/annotation 이면 제외
        if line.lstrip().startswith(('import ', 'export ', 'part ', '@pragma')):
            continue
        yield s, e, qq, raw, parts

def walk(lib):
    for root, _, fs in os.walk(lib):
        rel = root.replace('\\', '/')
        if any(rel.startswith(x.rstrip('/')) or ('/' + x) in rel for x in SKIP_FILES):
            continue
        for f in fs:
            if f.endswith('.dart') and not f.endswith(('.g.dart', '.pb.dart', '.pbenum.dart', '.pbjson.dart')):
                yield os.path.join(root, f).replace('\\', '/')

def extract(lib, out):
    seen = {}
    for p in walk(lib):
        src = open(p, encoding='utf-8').read()
        for s, e, qq, raw, parts in candidates(p, src):
            t, args = template(parts)
            d = seen.setdefault(t, {'n': 0, 'args': args, 'files': []})
            d['n'] += 1
            if len(d['files']) < 2: d['files'].append(p)
    json.dump(seen, open(out, 'w', encoding='utf-8'), ensure_ascii=False, indent=0)
    print(len(seen), 'strings')

def apply(lib, mp):
    tr = json.load(open(mp, encoding='utf-8'))
    changed = replaced = 0
    for p in walk(lib):
        src = open(p, encoding='utf-8').read()
        edits = []
        for s, e, qq, raw, parts in candidates(p, src):
            t, args = template(parts)
            ko = tr.get(t)
            if not ko or ko == t:
                continue
            # 자리표시 개수 확인
            if sorted(set(re.findall(r'(?<!\{)\{(\d+)\}(?!\})', ko))) != sorted(set(str(i) for i in range(len(args)))):
                continue
            body = ko.replace('{{', '\x00').replace('}}', '\x01')
            body = re.sub(r'\{(\d+)\}', lambda m: '\x02%s\x03' % m.group(1), body)
            # 따옴표/역슬래시/달러 처리
            q = qq[0]
            if not raw:
                body = re.sub(r'(?<!\\)\$', r'\\$', body)
            if len(qq) == 1:
                body = body.replace('\n', '\\n')
                if not raw:
                    body = re.sub(r'(?<!\\)' + re.escape(q), '\\' + q, body)
                elif q in body:
                    continue
            body = re.sub('\x02(\\d+)\x03', lambda m: args[int(m.group(1))], body)
            body = body.replace('\x00', '{').replace('\x01', '}')
            prefix = 'r' if raw else ''
            edits.append((s, e, prefix + qq + body + qq))
        if edits:
            for s, e, rep in sorted(edits, reverse=True):
                src = src[:s] + rep + src[e:]
                replaced += 1
            open(p, 'w', encoding='utf-8').write(src)
            changed += 1
    print('replaced', replaced, 'in', changed, 'files')

if __name__ == '__main__':
    cmd, lib, f = sys.argv[1:4]
    (extract if cmd == 'extract' else apply)(lib, f)
