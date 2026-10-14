import json, pathlib
root = pathlib.Path(__file__).resolve().parent.parent
h = (root / "tools/admin.html").read_text(encoding="utf-8")
(root / "src/admin-html.ts").write_text(
    "// Avtomatik yaratilgan: tools/admin.html dan (python3 tools/build_admin.py)\nexport const ADMIN_HTML = "
    + json.dumps(h, ensure_ascii=False) + ";\n", encoding="utf-8")
