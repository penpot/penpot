"""Simple script for rebuilding .codespell-ignore-lines

Usage:

cat < /dev/null > .codespell-ignore-lines
pre-commit run --all-files codespell >& /tmp/codespell_errors.txt
python3 tools/codespell_ignore_lines_from_errors.py /tmp/codespell_errors.txt > .codespell-ignore-lines

git diff to review changes, then commit, push.
"""

import sys
from typing import List


def run(args: List[str]) -> None:
    assert len(args) == 1, "codespell_errors.txt"
    cache = {}
    done = set()
    for line in sorted(open(args[0]).read().splitlines()):
        i = line.find(" ==> ")
        if i > 0:
            flds = line[:i].split(":")
            if len(flds) >= 2:
                filename, line_num = flds[:2]
                if filename not in cache:
                    cache[filename] = open(filename).read().splitlines()
                supp = cache[filename][int(line_num) - 1]
                if supp not in done:
                    print(supp)
                    done.add(supp)


if __name__ == "__main__":
    run(args=sys.argv[1:])
