#!/usr/bin/env bash
# Скриншот экрана телефона в shots/<имя>.png + уменьшенная копия для просмотра
# из Termux. Нужны root (screencap пишет только системный пользователь) и
# ImageMagick. На устройстве под secure-keyguard снимет локскрин — для вёрстки
# вместо этого используйте probe-рендер (см. README, раздел «Отладка вёрстки»).
#
# Использование: ./dev/shot.sh 02-create
set -e
NAME="${1:-shot}"
PROJ="$(cd "$(dirname "$(readlink -f "$0")")/.." && pwd)"
DIR="$PROJ/shots"
mkdir -p "$DIR"
TMP=/data/local/tmp/nano-$NAME.png
su -c "screencap -p $TMP; chmod 666 $TMP"
cp "$TMP" "$DIR/$NAME.png"
su -c "rm -f $TMP"
magick "$DIR/$NAME.png" -resize 700x "$DIR/$NAME-small.png"
ls -lh "$DIR/$NAME-small.png"
