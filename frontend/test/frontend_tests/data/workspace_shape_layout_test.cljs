;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS SUBSIDIARY SL

(ns frontend-tests.data.workspace-shape-layout-test
  (:require
   [app.common.schema :as sm]
   [app.common.types.shape.layout :as ctl]
   [app.main.data.workspace.shape-layout :as dwsl]
   [cljs.test :as t :include-macros true]))

(def ^:private valid-layout-padding?
  (sm/lazy-validator ctl/schema:layout-attrs))

(t/deftest update-shape-layout-patches-one-padding-side
  (let [shape  {:layout :flex
                :layout-padding-type :multiple
                :layout-padding {:p1 1 :p2 2 :p3 3 :p4 4}}
        result (dwsl/update-shape-layout shape {:layout-padding {:p1 12}})]
    (t/is (= {:p1 12 :p2 2 :p3 3 :p4 4} (:layout-padding result)))))

(t/deftest update-shape-layout-completes-a-missing-padding
  ;; Regression for https://github.com/penpot/penpot/issues/12463.
  ;; A layout without padding (made through the API) saved `{:p1 12}`
  ;; after setting one side, and the backend rejected the shape.
  (let [shape  {:layout :flex :layout-padding-type :multiple}
        result (dwsl/update-shape-layout shape {:layout-padding {:p1 12}})]
    (t/is (= {:p1 12 :p2 0 :p3 0 :p4 0} (:layout-padding result)))
    (t/is (valid-layout-padding? (select-keys result [:layout-padding])))))

(t/deftest update-shape-layout-leaves-padding-alone-for-other-changes
  (let [shape  {:layout :flex}
        result (dwsl/update-shape-layout shape {:layout-gap {:row-gap 4}})]
    (t/is (not (contains? result :layout-padding)))
    (t/is (= {:row-gap 4} (:layout-gap result)))))
