;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC
(ns frontend-tests.tokens.style-dictionary-test
  (:require
   [app.main.ui.workspace.tokens.style-dictionary :as wtsd]
   [cljs.test :as t :include-macros true]))

(t/deftest test-find-token-references
  ;; Return references
  (t/is (= #{"foo" "bar"} (wtsd/find-token-references "{foo} + {bar}")))
  ;; Ignore non reference text
  (t/is (= #{"foo.bar.baz"} (wtsd/find-token-references "{foo.bar.baz} + something")))
  ;; No references found
  (t/is (nil? (wtsd/find-token-references "1 + 2")))
  ;; Edge-case: Ignore unmatched closing parens
  (t/is (= #{"foo" "bar"} (wtsd/find-token-references "{foo}} + {bar}"))))
