#!/usr/bin/env bash
# Build the "Bili Pause Ad Block" LSPosed module into a signed APK.
#
# All paths handed to javac stay relative and ASCII: this project may live under a
# Chinese directory, and javac reads @argfiles with the platform charset (GBK).
set -eu
WANT_BT=36.0.0
WANT_PLATFORM=android-36
. "$(cd "$(dirname "$0")/.." && pwd)/sdk.sh"   # exports BT_WIN / PLATFORM_WIN
pick_android_sdk
HERE="$(cd "$(dirname "$0")" && pwd)"
cd "$HERE"

# Release keystore lives in signing.env (gitignored). Fall back to the SDK debug key.
SIGN_ENV="$HERE/signing.env"
if [ -f "$SIGN_ENV" ]; then
    . "$SIGN_ENV"
    echo "using release keystore (alias $KS_ALIAS)"
else
    KS_WIN='C:\Users\hou33\.android\debug.keystore'
    KS_ALIAS='androiddebugkey'
    KS_PASS='android'
    KEY_PASS='android'
    echo "signing.env missing -> using SDK debug keystore"
fi

OUT="$HERE/../dist/BiliToolbox-v1.8.apk"
rm -rf build
mkdir -p build/stubs-classes build/classes build/dex

echo "[1/6] javac xposed stubs (compile-only, never packaged)"
find stubs -name '*.java' > build/stubs.txt
javac -encoding UTF-8 -source 8 -target 8 -nowarn -bootclasspath "$PLATFORM_WIN" \
  -d build/stubs-classes @build/stubs.txt

echo "[2/6] javac module sources"
find src -name '*.java' > build/src.txt
javac -encoding UTF-8 -source 8 -target 8 -nowarn -bootclasspath "$PLATFORM_WIN" \
  -cp build/stubs-classes -d build/classes @build/src.txt

echo "[3/6] d8"
( cd build/classes && find . -name '*.class' | sed 's|^\./||' > ../../build/cls.txt )
CLASSES=()
while IFS= read -r c; do CLASSES+=("build/classes/$c"); done < build/cls.txt
"$BT_WIN\\d8.bat" --release --min-api 24 --lib "$PLATFORM_WIN" --output build/dex "${CLASSES[@]}"

echo "[4/6] aapt2 link + pack dex/assets"
"$BT_WIN\\aapt2.exe" compile --dir res -o build/res.zip
"$BT_WIN\\aapt2.exe" link -o build/unaligned.apk \
  --manifest AndroidManifest.xml \
  -I "$PLATFORM_WIN" \
  --min-sdk-version 24 --target-sdk-version 35 \
  --version-code 9 --version-name 1.8 \
  build/res.zip

python - <<'PY'
import zipfile
zin = zipfile.ZipFile("build/unaligned.apk")
zout = zipfile.ZipFile("build/withdex.apk", "w", zipfile.ZIP_DEFLATED)
for item in zin.infolist():
    zout.writestr(item, zin.read(item.filename))
zout.write("build/dex/classes.dex", "classes.dex")
zout.write("assets/xposed_init", "assets/xposed_init")
zout.close(); zin.close()
print("apk entries:", zipfile.ZipFile("build/withdex.apk").namelist())
PY

echo "[5/6] zipalign"
"$BT_WIN\\zipalign.exe" -f 4 build/withdex.apk build/aligned.apk

echo "[6/6] sign"
# 两个 Windows 批处理的坑，一起绕开：
#  1. apksigner.bat 读不了含中文的 keystore 路径 -> 先拷到 build/ 下的 ASCII 路径；
#  2. 工程本身在「模块」这种中文目录里，输出也先落到 build/，成功后再由 bash 拷走。
KS_SRC="$KS_WIN" python - <<'PY'
import os, shutil
shutil.copy2(os.environ['KS_SRC'], 'build/release.p12')
PY
"$BT_WIN\\apksigner.bat" sign --ks build/release.p12 --ks-key-alias "$KS_ALIAS" \
  --ks-pass "pass:$KS_PASS" --key-pass "pass:$KEY_PASS" \
  --out build/signed.apk build/aligned.apk
rm -f build/release.p12
"$BT_WIN\\apksigner.bat" verify --print-certs build/signed.apk | head -4
mkdir -p "$HERE/../dist"
cp build/signed.apk "$OUT"
ls -la "$OUT"
echo "done -> $OUT"
