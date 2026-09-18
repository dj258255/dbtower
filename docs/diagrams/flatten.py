"""글자를 path 로 바꾼 SVG 를 따로 내보낸다 — PDF 로 인쇄할 때 쓴다.

왜 필요한가: 이 그림들의 글자는 `<text>`라 PDF 로 인쇄하면 Type 3 글꼴로 박히고,
그 글꼴이 문서 전체에 따라붙는다. 글자가 path 면 글꼴 의존이 사라진다.

왜 PNG 로 안 바꾸나: 손그림 선이 래스터가 되면 인쇄 해상도에서 뭉개지고, 배수를 올리면
파일이 커져 PDF 도 같이 무거워진다. path 는 벡터를 유지한다.

원본(`*-core.svg`)은 그대로 둔다 — 웹에서는 글자가 text 인 쪽이 가볍고 선택·검색이 된다.

사용법: python3 flatten.py
"""
from pathlib import Path

import cairosvg

HERE = Path(__file__).parent

for name in ("architecture", "erd", "insight-flow", "deployment-flow"):
    src = HERE / f"{name}.svg"
    dst = HERE / f"{name}-flat.svg"
    cairosvg.svg2svg(url=str(src), write_to=str(dst))
    text = dst.read_text()
    assert "<text" not in text, f"{dst.name} 에 아직 text 요소가 남았다"
    print(f"{dst.relative_to(HERE.parent.parent)} ({len(text) // 1024}KB, text 0개)")
