;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS SUBSIDIARY SL

(ns common-tests.geom-shapes-constraints-test
  (:require
   [app.common.geom.shapes.constraints :as gsc]
   [app.common.types.modifiers :as ctm]
   [clojure.test :as t]))

;; ---- constraint-modifier :default ----

(t/deftest constraint-modifier-default-returns-empty-modifiers
  (t/testing ":default method accepts 6 args and returns empty modifiers"
    ;; :scale and any other type with no method of its own add nothing; the
    ;; caller adds the result to the child's modifiers (`ctm/add-modifiers`)
    (let [result (gsc/constraint-modifier :unknown-constraint-type
                                          :x nil nil nil nil)]
      (t/is (ctm/empty? result))))

  (t/testing ":default method can be added to modifiers"
    (let [result (gsc/constraint-modifier :scale :y nil nil nil nil)]
      (t/is (ctm/empty? (ctm/add-modifiers (ctm/empty) result))))))
