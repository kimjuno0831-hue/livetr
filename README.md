# PiliPlus 한국어

[PiliPlus](https://github.com/bggRGjQaUbCoE/PiliPlus) (GPL-3.0) 에 한국어 번역 기능(제목·소개·댓글·탄막·자막·라이브 탄막)과 한국어 메뉴를 얹은 개인용 빌드.

- `ko.patch`: 원본 코드 수정분
- `lib/ko/`: 번역 모듈과 설정 화면
- `.github/workflows/pili-ko.yml`: 원본 고정 커밋 + 패치로 빌드해서 Releases `pili-latest` 에 올림
- `tool/ui_strings.py`, `ui_ko.json`: 메뉴·설정 고정 문구 한국어 번역 (빌드 때 적용)
