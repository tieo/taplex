#!/usr/bin/env bash
# Renders every state the book declares and puts the pictures where it reads them.
#
# The renders come from the real screens through Paparazzi, on the JVM, so they need no
# phone and cannot quietly stop matching the code. Paparazzi names a file after its test;
# the book names it after the view and state, which is the name given to snapshot().
set -euo pipefail
cd "$(dirname "$0")/.."

gradle recordPaparazziDebug --console=plain

mkdir -p docs/model/img
for file in app/src/test/snapshots/images/*.png; do
    name=$(basename "$file")
    # de.tieo.wordtap_BookRenders_<test name>_<snapshot name>.png -> <snapshot name>.png
    short=${name##*_}
    cp -f "$file" "docs/model/img/$short"
done
echo "renders in docs/model/img:"
ls docs/model/img
