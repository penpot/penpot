;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS SUBSIDIARY SL

(ns frontend-tests.data.exports-selection-test
  (:require
   #?(:clj [clojure.test :as t]
      :cljs [cljs.test :as t :include-macros true])
   [app.main.data.exports.selection :as selection]))

(def ^:private shape
  {:id "card"
   :name "Card"
   :exports [{:type :png :scale 2 :suffix "@2x"}]})

(t/deftest empty-selection-does-not-export-the-page
  (t/is (= {:status :empty-selection}
           (selection/plan-export [] "file" "page" false))))

(t/deftest missing-settings-do-not-invent-a-png-preset
  (t/is (= {:status :missing-settings}
           (selection/plan-export [(dissoc shape :exports)] "file" "page" false))))

(t/deftest busy-export-is-not-restarted
  (t/is (= {:status :busy}
           (selection/plan-export [shape] "file" "page" true))))

(t/deftest single-export-keeps-base-name-and-suffix-separate
  (let [plan (selection/plan-export [shape] "file" "page" false)
        export (first (:exports plan))]
    (t/is (= :ready (:status plan)))
    (t/is (= "Card" (:name export)))
    (t/is (= "@2x" (:suffix export)))
    (t/is (= :png (:type export)))
    (t/is (= 2 (:scale export)))
    (t/is (= (dissoc shape :exports) (:shape export)))))

(t/deftest batch-exports-keep-base-names-and-preset-order
  (let [second-shape {:id "icon" :name "Icon"
                      :exports [{:type :svg :suffix "-outline"}
                                {:type :webp :scale 1 :suffix ""}]}
        exports (:exports (selection/plan-export [shape second-shape] "file" "page" false))]
    (t/is (= ["Card" "Icon" "Icon"] (mapv :name exports)))
    (t/is (= ["@2x" "-outline" ""] (mapv :suffix exports)))
    (t/is (= [:png :svg :webp] (mapv :type exports)))
    (t/is (every? :enabled exports))))

(t/deftest multiple-presets-on-one-shape-use-the-batch-contract
  (let [exports (:exports (selection/plan-export
                           [(update shape :exports conj {:type :pdf :suffix "-print"})]
                           "file" "page" false))]
    (t/is (= ["Card" "Card"] (mapv :name exports)))
    (t/is (= ["@2x" "-print"] (mapv :suffix exports)))))

(t/deftest mixed-selection-exports-only-configured-shapes
  (let [exports (:exports (selection/plan-export
                           [{:id "unconfigured"} shape] "file" "page" false))]
    (t/is (= ["card"] (mapv :object-id exports)))
    (t/is (= "Card" (:name (first exports))))))

(t/deftest document-and-shape-identities-override-preset-fields
  (let [shape (update-in shape [:exports 0] merge
                         {:file-id "other" :page-id "other" :object-id "other"
                          :name "Other" :enabled false})
        export (-> (selection/plan-export [shape] "file" "page" false) :exports first)]
    (t/is (= ["file" "page" "card" "Card" true]
             ((juxt :file-id :page-id :object-id :name :enabled) export)))))

(t/deftest absent-suffix-does-not-change-the-name
  (let [shape (update-in shape [:exports 0] dissoc :suffix)]
    (t/is (= "Card" (-> (selection/plan-export [shape] "file" "page" false)
                        :exports first :name)))))

(t/deftest unnamed-shapes-use-the-object-id-as-the-base-name
  (doseq [name [nil "" "   "]]
    (t/is (= "card" (-> (selection/plan-export [(assoc shape :name name)] "file" "page" false)
                        :exports first :name)))))
