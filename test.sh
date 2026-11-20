#!/bin/sh
# Runs the plain-Java tests on the desktop JVM: every src file that doesn't touch android.*, plus test/.
set -e
cd "$(dirname "$0")"
export JAVA_HOME=$HOME/.local/opt/jdk17
export PATH=$JAVA_HOME/bin:$PATH

rm -rf build/test && mkdir -p build/test
SRC=$(grep -L 'import android\.' $(find src -name '*.java') || true)
TESTS=$(find test -name '*Test.java')
javac -d build/test $SRC $TESTS
for t in $TESTS; do
    cls=$(echo "$t" | sed 's|^test/||; s|\.java$||; s|/|.|g')
    java -ea -cp build/test "$cls"
    echo "ok $cls"
done
