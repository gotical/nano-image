#!/usr/bin/env bash
# Сборка «Nano Image» — клиента генерации картинок по промпту.
#
# Конвейер без Gradle: aapt2 -> javac R.java -> kotlinc -> d8 -> zip ->
# apksigner. Рассчитан на Termux (пакеты aapt2, apksigner, kotlin), но пути
# тулчейна переопределяются переменными ANDROID_HOME и KOTLIN_HOME.
# Два места, где легко споткнуться и которые здесь учтены:
#   1. R.java компилируется ДО kotlinc, иначе котлин не видит класс ресурсов;
#   2. kotlin-stdlib.jar обязательно кладётся во вход d8 — без него dexer
#      падает на отсутствии kotlin.jvm.internal.Intrinsics.
#
# На Android перед запуском поднимите лимит phantom-процессов, иначе система
# убьёт kotlinc по пути:
#   device_config put activity_manager max_phantom_processes 256
set -e

PROJ="$(cd "$(dirname "$(readlink -f "$0")")" && pwd)"
OUT="${1:-$PROJ/nanoimage.apk}"

API=35
SDK="${ANDROID_HOME:-$HOME/android-sdk}"
PLATFORM="$SDK/platforms/android-$API/android.jar"
KOTLIN_LIB="${KOTLIN_HOME:-$PREFIX/opt/kotlin}/lib"
STDLIB="$KOTLIN_LIB/kotlin-stdlib.jar"

BUILD="$PROJ/build"
KEYSTORE="$PROJ/keystore.jks"
KS_PASS="android"
MIN_SDK=26
TARGET_SDK=34

[ -f "$PLATFORM" ] || { echo "НЕТ android.jar: $PLATFORM" >&2; exit 1; }
[ -f "$STDLIB" ] || { echo "НЕТ kotlin-stdlib: $STDLIB" >&2; exit 1; }

echo "==> [1/7] чистка"
rm -rf "$BUILD"
mkdir -p "$BUILD/classes" "$BUILD/res-compiled" "$BUILD/gen"

echo "==> [2/7] ресурсы (aapt2)"
find "$PROJ/res" -type f | while read -r f; do
  rel="${f#$PROJ/res/}"
  mkdir -p "$BUILD/res-compiled/$(dirname "$rel")"
  aapt2 compile -o "$BUILD/res-compiled" "$f"
done
FLATS=$(find "$BUILD/res-compiled" -name '*.flat' | tr '\n' ' ')
aapt2 link --auto-add-overlay -o "$BUILD/base.apk" -I "$PLATFORM" \
  --manifest "$PROJ/AndroidManifest.xml" \
  --min-sdk-version "$MIN_SDK" --target-sdk-version "$TARGET_SDK" \
  --java "$BUILD/gen" $FLATS

echo "==> [3/7] R.java -> классы"
find "$BUILD/gen" -name '*.java' > "$BUILD/java-src.txt"
echo "    R.java: $(wc -l < "$BUILD/java-src.txt")"
javac -source 17 -target 17 -nowarn \
  -cp "$PLATFORM:$STDLIB" -d "$BUILD/classes" \
  @"$BUILD/java-src.txt" 2>&1 | grep -v 'bootstrap class path' || true

echo "==> [4/7] Kotlin -> классы"
find "$PROJ/kotlin" -name '*.kt' > "$BUILD/kotlin-src.txt"
echo "    исходников: $(wc -l < "$BUILD/kotlin-src.txt")"
kotlinc -nowarn -jvm-target 17 \
  -d "$BUILD/classes" \
  -classpath "$PLATFORM:$STDLIB:$BUILD/classes" \
  @$(echo "$BUILD/kotlin-src.txt" | tr '\n' ' ')

echo "==> [5/7] dex (d8) со stdlib"
find "$BUILD/classes" -name '*.class' > "$BUILD/classes-list.txt"
echo "    наших классов: $(wc -l < "$BUILD/classes-list.txt")"
d8 --release --min-api "$MIN_SDK" --lib "$PLATFORM" \
  --output "$BUILD" "$STDLIB" @"$BUILD/classes-list.txt"

echo "==> [6/7] упаковка"
(cd "$BUILD" && zip -q base.apk classes.dex)
# zipalign пропускаем осознанно: в Termux его нет ни в пакетах, ни в
# build-tools (там x86_64-бинарь, на ARM64 не запускается), а выравнивание
# влияет только на mmap нативных библиотек. У нас в APK их нет — ни одного
# .so, только classes.dex и ресурсы, поэтому выигрыш был бы нулевой.
cp "$BUILD/base.apk" "$BUILD/aligned.apk"

echo "==> [7/7] подпись"
if [ ! -f "$KEYSTORE" ]; then
  echo "    создаю keystore..."
  keytool -genkeypair -v -keystore "$KEYSTORE" -storepass "$KS_PASS" \
    -keypass "$KS_PASS" -alias androiddebugkey -keyalg RSA -keysize 2048 \
    -validity 10000 -dname "CN=Nano Image,O=RybinskLab,C=RU"
fi
apksigner sign --ks "$KEYSTORE" --ks-pass "pass:$KS_PASS" \
  --key-pass "pass:$KS_PASS" --out "$OUT" "$BUILD/aligned.apk"

apksigner verify "$OUT" && echo "    подпись в порядке"
echo "==> ГОТОВО: $OUT"
ls -lh "$OUT"
