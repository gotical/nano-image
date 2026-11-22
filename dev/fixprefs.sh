#!/usr/bin/env bash
# Тестовая правка SharedPreferences приложения Nano Image (запускать под su):
#   su -c "sh dev/fixprefs.sh add qwen-image-3-uncensored"
#   su -c "sh dev/fixprefs.sh del"
# Меняет gen_model, чтобы снять probe-рендер с другой выбранной моделью.
# Приложение должно быть остановлено (am force-stop), иначе prefs затрёт.
# Инструмент разработчика на устройстве; в сборку и в релиз не входит.
F=/data/data/ru.rybinsklab.nanoimage/shared_prefs/nanoimage.xml
MODE="$1"
VAL="$2"
[ -f "$F" ] || { echo "нет $F"; exit 1; }
# убираем прежнюю строку gen_model
sed -i '/name="gen_model"/d' "$F"
if [ "$MODE" = "add" ]; then
  sed -i "s#</map>#<string name=\"gen_model\">$VAL</string>\n</map>#" "$F"
fi
grep -c gen_model "$F" || true
cat "$F"
