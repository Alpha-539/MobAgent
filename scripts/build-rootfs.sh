#!/usr/bin/env bash
# Ubuntu Base (arm64) ni yuklab, keraksiz narsalardan tozalaydi va ENG IXCHAM .tar.xz qilib
# APK assets'iga joylaydi: app/src/main/assets/linux/ubuntu-base-arm64.tar.xz
# Sabab: Google Play ilovadan tashqaridan fayl yuklab olishni yoqtirmaydi — rootfs APK bilan keladi.
#
# Ishlatish: scripts/build-rootfs.sh   (ildiz papkadan; Linux/macOS/WSL/GitHub Actions)
# Kerak: curl, tar, xz, sha256sum.
set -euo pipefail

RELEASE_URL="https://cdimage.ubuntu.com/ubuntu-base/releases/24.04/release"
OUT="app/src/main/assets/linux/ubuntu-base-arm64.tar.xz"
WORK="$(mktemp -d)"
trap 'rm -rf "$WORK"' EXIT

mkdir -p "$(dirname "$OUT")"
curl -fsSL "$RELEASE_URL/SHA256SUMS" -o "$WORK/SHA256SUMS"
LINE="$(grep -E ' \*?ubuntu-base-[0-9.]+-base-arm64\.tar\.gz$' "$WORK/SHA256SUMS" | tail -n 1)"
[ -n "$LINE" ] || { echo "SHA256SUMS ichida arm64 rootfs topilmadi" >&2; exit 1; }
NAME="$(echo "$LINE" | awk '{print $2}' | tr -d '*')"
SHA="$(echo "$LINE" | awk '{print $1}')"

echo ">> yuklanmoqda: $NAME"
curl -fsSL "$RELEASE_URL/$NAME" -o "$WORK/$NAME"
echo "$SHA  $WORK/$NAME" | sha256sum -c -

echo ">> ochilmoqda va tozalanmoqda"
mkdir "$WORK/rootfs"
tar -xzf "$WORK/$NAME" -C "$WORK/rootfs" --no-same-owner --numeric-owner --exclude='./dev/*'
R="$WORK/rootfs"
chmod -R u+rwX "$R"

# Hujjat, man, til fayllari, apt keshi va jurnallar kerak emas (agent ularni o'qimaydi).
# apt ro'yxatlari o'chiriladi: birinchi `apt-get update` ularni qayta oladi (agent promptida shunday deyilgan).
rm -rf "$R"/usr/share/doc/* "$R"/usr/share/man/* "$R"/usr/share/info/* \
       "$R"/usr/share/lintian "$R"/usr/share/bug "$R"/usr/share/doc-base \
       "$R"/usr/share/locale/* "$R"/usr/share/help "$R"/usr/share/gtk-doc \
       "$R"/var/cache/apt/* "$R"/var/lib/apt/lists/* "$R"/var/log/* "$R"/tmp/* 2>/dev/null || true
find "$R/usr" -name '__pycache__' -type d -prune -exec rm -rf {} + 2>/dev/null || true

# Keyin `apt-get install` qilingan paketlar ham hujjat/til fayllarini olib kelmasin.
mkdir -p "$R/etc/dpkg/dpkg.cfg.d"
cat > "$R/etc/dpkg/dpkg.cfg.d/99-mobagent-minimal" <<'CFG'
path-exclude=/usr/share/doc/*
path-exclude=/usr/share/man/*
path-exclude=/usr/share/info/*
path-exclude=/usr/share/locale/*
path-exclude=/usr/share/lintian/*
path-include=/usr/share/doc/*/copyright
CFG

echo ">> siqilmoqda (xz -9e)"
# dict=16MiB: telefonda ochishda ko'p RAM talab qilmaydi (64 MB lug'at o'rniga).
tar -C "$R" --owner=0 --group=0 --numeric-owner --sort=name -cf - . \
  | xz -9e --lzma2=preset=9e,dict=16MiB -T1 -c > "$OUT"

echo ">> tayyor: $OUT ($(du -h "$OUT" | cut -f1))"
