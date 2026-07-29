;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC Sucursal en España SL

(ns frontend-tests.plugins.format-test
  (:require
   [app.plugins.exports :as exports]
   [app.plugins.format :as format]
   [app.plugins.shadows :as shadows]
   [app.plugins.tracks :as tracks]
   [cljs.test :as t :include-macros true]))

(t/deftest test-format-array-always-returns-array
  (t/testing "nil collection returns an empty array"
    (let [result (format/format-array identity nil)]
      (t/is (array? result))
      (t/is (= 0 (.-length result)))))

  (t/testing "empty collection returns an empty array"
    (let [result (format/format-array identity [])]
      (t/is (array? result))
      (t/is (= 0 (.-length result)))))

  (t/testing "non-empty collection maps each item"
    (let [result (format/format-array inc [1 2 3])]
      (t/is (array? result))
      (t/is (= [2 3 4] (vec result)))))

  (t/testing "items dropped by format-fn (nil) are removed"
    (let [result (format/format-array #(when (odd? %) %) [1 2 3 4])]
      (t/is (= [1 3] (vec result))))))

(t/deftest test-array-wrappers-return-empty-array-on-nil
  ;; Each wrapper backs a non-nullable array-typed Plugin API property and
  ;; must return an empty array (never nil) when the source collection is nil.
  (t/are [result] (and (array? result) (= 0 (.-length result)))
    (shadows/format-shadows nil)
    (exports/format-exports nil)
    (format/format-frame-guides nil)
    (tracks/format-tracks nil nil nil nil nil nil)
    (format/format-path-content nil)))

(t/deftest test-format-color-result-uses-shapes-info-key
  (let [shape-id (random-uuid)
        result   (format/format-color-result
                  [{:color "#fabada"}
                   [{:prop :fill :shape-id shape-id :index 0}]])
        info     (aget result "shapesInfo")]
    (t/is (array? info))
    (t/is (nil? (aget result "shapesColors")))
    (t/is (= "fill" (aget (aget info 0) "property")))
    (t/is (= (str shape-id) (aget (aget info 0) "shapeId")))))

(t/deftest test-shape-type-reports-boolean
  (t/is (= "boolean" (format/shape-type :bool))))
