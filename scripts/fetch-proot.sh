#!/usr/bin/env bash
# proot (+ loader + libtalloc) ni Termux paket repositoriyasidan olib, jniLibs ichiga
# `lib*.so` nomlari bilan joylaydi. Sabab: Android 10+ da faqat nativeLibraryDir'dagi
# fayllarni ishga tushirish mumkin (LinuxEnvironment.kt'ga qarang).
#
# Termux paketlari /data/data/com.termux/... yo'liga qurilgan, lekin proot uchun bu
# muammo emas: loader yo'li PROOT_LOADER muhit o'zgaruvchisi bilan, talloc esa
# LD_LIBRARY_PATH bilan beriladi (ikkalasini ham LinuxEnvironment.kt o'zi qo'yadi).
#
# Ishlatish: scripts/fetch-proot.sh   (ildiz papkadan)
set -euo pipefail

REPO="https://packages.termux.dev/apt/termux-main"
ARCH="aarch64"
OUT="app/src/main/jniLibs/arm64-v8a"
WORK="$(mktemp -d)"
trap 'rm -rf "$WORK"' EXIT

mkdir -p "$OUT"
curl -fsSL "$REPO/dists/stable/main/binary-$ARCH/Packages" -o "$WORK/Packages"

# Packages indeksidan paketning .deb yo'lini topadi (versiya raqami qattiq yozilmaydi).
deb_path() {
  awk -v pkg="$1" 'BEGIN{RS="";FS="\n"} {
    name=""; file="";
    for(i=1;i<=NF;i++){
      if($i ~ /^Package: /){name=substr($i,10)}
      if($i ~ /^Filename: /){file=substr($i,11)}
    }
    if(name==pkg){print file; exit}
  }' "$WORK/Packages"
}

fetch_pkg() {
  local pkg="$1" path
  path="$(deb_path "$pkg")"
  [ -n "$path" ] || { echo "Packages ichida '$pkg' topilmadi" >&2; exit 1; }
  echo "Yuklanmoqda: $path"
  mkdir -p "$WORK/$pkg"
  curl -fsSL "$REPO/$path" -o "$WORK/$pkg/pkg.deb"
  (cd "$WORK/$pkg" && ar x pkg.deb && tar xf data.tar.* )
}

fetch_pkg proot
fetch_pkg libtalloc
# Yangi Termux proot'lari libandroid-shmem.so ga bog'liq (SysV shm emulyatsiyasi).
fetch_pkg libandroid-shmem

PREFIX_DIR="data/data/com.termux/files/usr"
cp -L "$WORK/proot/$PREFIX_DIR/bin/proot"                 "$OUT/libproot.so"
cp -L "$WORK/proot/$PREFIX_DIR/libexec/proot/loader"      "$OUT/libproot-loader.so"
cp -L "$WORK/libtalloc/$PREFIX_DIR/lib/libtalloc.so.2"    "$OUT/libtalloc.so"
cp -L "$WORK/libandroid-shmem/$PREFIX_DIR/lib/libandroid-shmem.so" "$OUT/libandroid-shmem.so"
chmod 755 "$OUT"/libproot.so "$OUT"/libproot-loader.so

echo "--- proot DT_NEEDED (kutilgan: libc, libm, libdl, libtalloc.so.2, libandroid-shmem.so) ---"
readelf -d "$OUT/libproot.so" | grep NEEDED || true
echo "--- libtalloc DT_NEEDED ---"
readelf -d "$OUT/libtalloc.so" | grep -E "NEEDED|SONAME" || true
echo "--- libandroid-shmem DT_NEEDED ---"
readelf -d "$OUT/libandroid-shmem.so" | grep -E "NEEDED|SONAME" || true

# Kutilmagan bog'liqlik bo'lsa, ogohlantiramiz (qurilishni to'xtatmaymiz: sinov tugmasi aniq xato ko'rsatadi).
UNEXPECTED="$(readelf -d "$OUT/libproot.so" | grep NEEDED | grep -vE 'libc\.so|libm\.so|libdl\.so|liblog\.so|libtalloc\.so\.2|libandroid-shmem\.so' || true)"
if [ -n "$UNEXPECTED" ]; then
  echo "::warning::proot kutilmagan kutubxonalarga bog'liq: $UNEXPECTED"
fi
# Google Play (targetSdk 35+): 16 KB sahifali qurilmalar uchun ELF LOAD segmentlari kamida 0x4000 (16 KB)
# hizalangan bo'lishi kerak. Termux paketi eski (4 KB) bo'lsa — ogohlantiramiz.
for f in "$OUT"/*.so; do
  ALIGN="$(readelf -lW "$f" | awk '$1=="LOAD"{print $NF}' | sort -u | head -n1)"
  if [ -n "$ALIGN" ] && [ "$((ALIGN))" -lt 16384 ]; then
    echo "::warning::$(basename "$f") LOAD hizalanishi $ALIGN (<0x4000): 16 KB sahifali qurilmalarda ishlamasligi mumkin"
  fi
done

ls -l "$OUT"
