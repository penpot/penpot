;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS SUBSIDIARY SL

(ns frontend-tests.tokens.token-path-test
  (:require
   [app.main.ui.workspace.tokens.management :as wtm]
   [cljs.test :as t :include-macros true]))

;; https://github.com/penpot/penpot/issues/11588
;; A token whose name equals a tree node path must stay in the operations of
;; that node, alongside the tokens nested below it.

(t/deftest token-in-path-matches-the-path-itself
  (t/testing "an ancestor token is in its own path"
    (t/is (true? (wtm/token-in-path? "a.a" "a.a")))
    (t/is (true? (wtm/token-in-path? "a" "a")))))

(t/deftest token-in-path-matches-nested-tokens
  (t/testing "a token nested below the path is in the path"
    (t/is (true? (wtm/token-in-path? "a.a" "a.a.a")))
    (t/is (true? (wtm/token-in-path? "a" "a.b.c")))
    (t/is (true? (wtm/token-in-path? "a.a" "a.a.b.c")))))

(t/deftest token-in-path-rejects-non-descendants
  (t/testing "a sibling is not in the path"
    (t/is (false? (wtm/token-in-path? "a.a" "a.b"))))

  (t/testing "a deeper path does not match a shorter token"
    (t/is (false? (wtm/token-in-path? "a.a.a" "a.a"))))

  (t/testing "a name sharing the prefix without a separator boundary is not in the path"
    (t/is (false? (wtm/token-in-path? "a.a" "a.ab")))
    (t/is (false? (wtm/token-in-path? "a" "ab"))))

  (t/testing "an unrelated path is not in the path"
    (t/is (false? (wtm/token-in-path? "x.y" "z")))))
