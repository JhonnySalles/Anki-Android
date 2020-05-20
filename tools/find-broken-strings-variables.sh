#!/bin/bash
# Spot malformed string replacement patterns in Android localization files.
# Hopefully it will prevent this kind of bugs: https://code.google.com/p/ankidroid/issues/detail?id=359

 # shellcheck disable=SC2016
 EXIT_STATUS=0

pushd AnkiDroid/src/main || exit 1
if grep -R "%1$ s" res/values*; then ((EXIT_STATUS + 1)); fi
if grep -R "%1$ d" res/values*; then ((EXIT_STATUS + 1)); fi
if grep -R "%1" res/values* | grep -v "%1\\$"; then ((EXIT_STATUS + 1)); fi

# This is currently not working and I'm not sure why? It worked a couple days ago as of 20200516
echo "The next test will likely only run correctly on macOS. On Ubuntu it does not work well."
if grep -RHn '%' res/values* |
 sed -e 's/%/\n%/g' | # Split lines that contain several expressions
 grep '%'           | # Filter out lines that do not contain expressions
 grep -v ' n% '     | # Lone % character, not a variable
 grep -v '(n%)'     | # Lone % character, not a variable
 grep -v 'n%<'      | # Same, at the end of the string
 grep -v '>n% '     | # Same, at the beginning of the string
 grep -v '%で'      | # Same, no spaces in Japanese
 grep -v '%s'       | # Single string variable
 grep -v '%d'       | # Single decimal variable
 grep -v '%[0-9][0-9]\?$s' | # Multiple string variable
 grep -v '%[0-9][0-9]\?$d' |  # Multiple decimal variable
 grep -v '%1$.1f'   | # ?
 grep -v '%.1f'     |
 grep -v '%\\n'
then
 echo "Found errors but if you are not on macOS they might be false positive." #EXIT_STATUS=$((EXIT_STATUS + 1))
fi

if grep -R '％' res/values*; then ((EXIT_STATUS + 1)); fi

if grep -R "CDATA " res/values*; then ((EXIT_STATUS + 1)); fi

lint --check StringFormatInvalid ./res
popd || exit 1
exit $EXIT_STATUS
