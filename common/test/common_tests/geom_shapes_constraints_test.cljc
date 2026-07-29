;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC

(ns common-tests.geom-shapes-constraints-test
  (:require
   [app.common.geom.shapes.constraints :as gsc]
   [clojure.test :as t]))

;; ---- constraint-modifier :default ----

(t/deftest constraint-modifier-default-returns-empty-vector
  (t/testing ":default method accepts 6 args and returns an empty vector"
    ;; Before the fix the :default method only accepted 5 positional args
    ;; (plus the dispatch value), so calling it with 6 args would throw an
    ;; arity error.  After the fix it takes [_ _ _ _ _ _] and returns [].
    (let [result (gsc/constraint-modifier :unknown-constraint-type
                                          :x nil nil nil nil)]
      (t/is (vector? result))
      (t/is (empty? result))))

  (t/testing ":default method returns [] for :scale-like unknown type on :y axis"
    (let [result (gsc/constraint-modifier :some-other-unknown
                                          :y nil nil nil nil)]
      (t/is (= [] result)))))
