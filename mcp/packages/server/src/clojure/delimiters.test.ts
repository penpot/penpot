import assert from "node:assert/strict";
import { describe, test } from "node:test";
import { delimiterProblem } from "./delimiters";

describe("each error kind with its line:col", () => {
    test("unmatched and mismatched closers", () => {
        assert.equal(delimiterProblem("(a)\n\n  }"), "unmatched '}' at 3:3 with nothing open");
        assert.equal(delimiterProblem("(let [x 1) x)"), "mismatched ')' at 1:10 where the '[' opened at 1:6 needs ']'");
    });

    test("an unclosed opener points at the first top-level form it swallowed", () => {
        assert.equal(delimiterProblem("(a (b [c"), "unclosed '(' opened at 1:1 and 2 more inside it");
        assert.equal(
            delimiterProblem("(ns a)\n(defn f []\n  (let [x 1]\n    x)\n(defn g [] 2)\n"),
            "unclosed '(' opened at 2:1; the '(' at 5:1 starts a top-level form inside it, so the missing closer is probably just before that line"
        );
    });

    test("unterminated string and regex", () => {
        assert.equal(delimiterProblem('(def a "abc)'), "unterminated string starting at 1:8");
        assert.equal(delimiterProblem('(ok)\n(re-find #"abc'), "unterminated regex starting at 2:10");
    });
});

describe("delimiters that are data do not count", () => {
    test("character literals", () => {
        assert.equal(delimiterProblem('[\\( \\) \\[ \\] \\{ \\} \\" \\; \\\\ \\newline \\space \\a]'), null);
        assert.equal(delimiterProblem("(case c \\) :close \\( :open)"), null);
    });

    test("strings, regexes, and comments", () => {
        assert.equal(delimiterProblem('(str "a \\"(\\" b" "c\\\\" ")")'), null);
        assert.equal(delimiterProblem('(re-find #"[(\\"]+" s)'), null);
        assert.equal(delimiterProblem('; (((\n(a) ; ]]] "\n'), null);
    });

    test("reader conditionals, sets, and anonymous functions", () => {
        assert.equal(delimiterProblem("(ns x (:require #?(:clj [a] :cljs [b])))"), null);
        assert.equal(delimiterProblem("#{1 #(+ % 1)}"), null);
    });
});

test("columns count code points, and multi-line strings keep lines right", () => {
    assert.equal(delimiterProblem('(a "😀") )'), "unmatched ')' at 1:9 with nothing open");
    assert.equal(delimiterProblem('(str "line1\nline2"))'), "unmatched ')' at 2:8 with nothing open");
});
