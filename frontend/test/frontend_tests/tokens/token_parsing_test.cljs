;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS SUBSIDIARY SL

(ns frontend-tests.tokens.token-parsing-test
  (:require
   [app.main.ui.workspace.tokens.management.forms.controls.token-parsing :as tp]
   [cljs.test :as t :include-macros true]))

(t/deftest extract-partial-token
  (t/testing "an open reference at the cursor"
    (t/is (= {:start 0 :end 4 :partial "col"}
             (tp/extract-partial-token "{col" 4))))

  (t/testing "the last open reference when an earlier one is closed"
    (t/is (= {:start 14 :end 17 :partial "sp"}
             (tp/extract-partial-token "{color.red} + {sp" 17))))

  (t/testing "the reference ends at its closing brace after the cursor"
    (t/is (= {:start 0 :end 5 :partial "co"}
             (tp/extract-partial-token "{colo}" 3))))

  (t/testing "nothing after a closed reference"
    (t/is (nil? (tp/extract-partial-token "{color} " 8))))

  (t/testing "nothing without a reference"
    (t/is (nil? (tp/extract-partial-token "12" 2)))
    (t/is (nil? (tp/extract-partial-token "" 0)))))

(t/deftest token-at-cursor
  (t/testing "the token name when the cursor is inside a reference"
    (t/is (= "color.red" (tp/token-at-cursor "{color.red}" 3)))
    (t/is (= "color.red" (tp/token-at-cursor "{color.red} + {spacing.md}" 1)))
    (t/is (= "spacing.md" (tp/token-at-cursor "{color.red} + {spacing.md}" 18))))

  (t/testing "nothing for a reference that isn't closed"
    (t/is (nil? (tp/token-at-cursor "{color" 3))))

  (t/testing "nothing for an empty reference or one with spaces"
    (t/is (nil? (tp/token-at-cursor "{}" 1)))
    (t/is (nil? (tp/token-at-cursor "{color red}" 3))))

  (t/testing "nothing before the first reference"
    (t/is (nil? (tp/token-at-cursor "1 + {color}" 1)))))
