"""Ilovadagi o'rnatilgan katalogdan (ModelItem.kt) Worker uchun boshlang'ich ma'lumot yaratadi.
Ishlatish: python3 tools/build_seed.py   (ilova katalogi o'zgarganda qayta ishga tushiring)"""
import json, pathlib, re
root = pathlib.Path(__file__).resolve().parent.parent
kt = root.parent / "app/src/main/java/com/mobagent/app/model/ModelItem.kt"
src = kt.read_text(encoding="utf-8")
body = src[src.index("object ModelCatalog"):]
items = re.findall(r"ModelItem\((.*?)\n        \)", body, re.S)
out = []
for i, it in enumerate(items):
    s = lambda k: re.search(k + r'\s*=\s*"((?:[^"\\]|\\.)*)"', it).group(1).replace('\\"', '"')
    n = lambda k, d=None: (lambda m: int(m.group(1)) if m else d)(re.search(k + r"\s*=\s*(\d+)", it))
    fam = re.search(r"ModelFamily\.(\w+)", it)
    role = re.search(r"ModelRole\.(\w+)", it)
    out.append({
        "id": s("id"), "name": s("name"), "downloadUrl": s("downloadUrl"), "direction": s("direction"),
        "category": re.search(r"ModelCategory\.(\w+)", it).group(1),
        "baseSizeMb": n("baseSizeMb"), "baseRamMb": n("baseRamMb"), "minPhoneClass": s("minPhoneClass"),
        "family": fam.group(1) if fam else "GENERIC",
        "paramsBillion": float(re.search(r"paramsBillion\s*=\s*([\d.]+)", it).group(1)),
        "maxContext": n("maxContext", 16384), "role": role.group(1) if role else None, "sortOrder": i * 10,
    })
(root / "src/seed-data.ts").write_text(
    "// Avtomatik yaratilgan: python3 tools/build_seed.py (qo'lda tahrirlamang)\n"
    "export interface SeedModel { id: string; name: string; downloadUrl: string; direction: string; category: string;\n"
    "  baseSizeMb: number; baseRamMb: number; minPhoneClass: string; family: string; paramsBillion: number;\n"
    "  maxContext: number; role: string | null; sortOrder: number }\n"
    "export const SEED_MODELS: SeedModel[] = " + json.dumps(out, ensure_ascii=False, indent=2) + ";\n", encoding="utf-8")
print(len(out), "models")
