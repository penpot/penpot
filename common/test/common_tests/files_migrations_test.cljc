;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS SUBSIDIARY SL

(ns common-tests.files-migrations-test
  (:require
   [app.common.data :as d]
   [app.common.files.migrations :as cfm]
   [app.common.types.file :as ctf]
   [app.common.types.shape :as cts]
   [app.common.uuid :as uuid]
   [clojure.test :as t]))

(defmethod cfm/migrate-data "test/1" [data _] (update data :sum inc))
(defmethod cfm/migrate-data "test/2" [data _] (update data :sum inc))
(defmethod cfm/migrate-data "test/3" [data _] (update data :sum inc))

(t/deftest generic-migration-subsystem-1
  (let [migrations (into (d/ordered-set) ["test/1" "test/2" "test/3"])]
    (with-redefs [cfm/available-migrations migrations
                  ctf/check-file-data identity]
      (let [file  {:data {:sum 1}
                   :id 1
                   :migrations (d/ordered-set "test/1")}
            file' (cfm/migrate file nil)]
        (t/is (= cfm/available-migrations (:migrations file')))
        (t/is (= 3 (:sum (:data file'))))))))

(t/deftest migration-0024b-fix-stroke-cap-placement
  (let [shape-id (uuid/next)
        page-id  (uuid/next)
        data     {:pages-index
                  {page-id
                   {:objects
                    {shape-id {:id         shape-id
                               :type       :path
                               :stroke-cap-start "round"
                               :stroke-cap-end   "round"
                               :strokes    [{:stroke-color "#000000"
                                             :stroke-opacity 1
                                             :stroke-style :svg
                                             :stroke-width 2}
                                            {:stroke-color "#000000"
                                             :stroke-cap-start "round"
                                             :stroke-cap-end   "round"
                                             :stroke-opacity 1
                                             :stroke-style :svg
                                             :stroke-width 2}]}}}}}
        data'    (cfm/migrate-data data "0024b-fix-stroke-cap-placement")]

    (let [shape (get-in data' [:pages-index page-id :objects shape-id])]
      (t/is (nil? (:stroke-cap-start shape)) "top-level cap removed")
      (t/is (nil? (:stroke-cap-end shape)) "top-level cap removed")
      (t/is (= :round (get-in shape [:strokes 0 :stroke-cap-start])) "cap moved into stroke")
      (t/is (= :round (get-in shape [:strokes 0 :stroke-cap-end])) "cap moved into stroke")
      (t/is (= :round (get-in shape [:strokes 1 :stroke-cap-start])) "correct cap type")
      (t/is (= :round (get-in shape [:strokes 1 :stroke-cap-end])) "correct cap type"))))

(t/deftest migration-0024-fix-stroke-cap-no-strokes
  (let [shape-id (uuid/next)
        page-id  (uuid/next)
        data     {:pages-index
                  {page-id
                   {:objects
                    {shape-id {:id               shape-id
                               :type             :path
                               :stroke-cap-start :round
                               :stroke-cap-end   :round
                               :strokes          []}}}}}
        data'    (cfm/migrate-data data "0024b-fix-stroke-cap-placement")]

    (let [shape (get-in data' [:pages-index page-id :objects shape-id])]
      (t/is (nil? (:stroke-cap-start shape)) "top-level cap removed even with no strokes")
      (t/is (nil? (:stroke-cap-end shape)) "top-level cap removed even with no strokes"))))

(t/deftest migration-0027-normalizes-constrained-shape-values
  (let [file-id  (uuid/next)
        page-id  (uuid/next)
        shape-id (uuid/next)
        shape     (-> (cts/setup-shape {:id shape-id :type :frame})
                      (assoc :r1 -1
                             :r2 -2
                             :r3 -3
                             :r4 -4
                             :layout-gap {:row-gap -5 :column-gap -6}
                             :layout-padding {:p1 -7 :p2 -8 :p3 -9 :p4 -10}
                             :layout-grid-rows [{:type :fixed :value -11}]
                             :layout-grid-columns [{:type :percent :value -12}]
                             :layout-item-min-w -13
                             :layout-item-max-w -14
                             :layout-item-min-h -15
                             :layout-item-max-h -16
                             :strokes [{:stroke-color "#000000"
                                        :stroke-width -17
                                        :stroke-width-top -18
                                        :stroke-width-right -19
                                        :stroke-width-bottom -20
                                        :stroke-width-left -21}]
                             :shadow [{:id nil
                                       :style :drop-shadow
                                       :offset-x 0
                                       :offset-y 0
                                       :blur -22
                                       :spread 0
                                       :hidden false
                                       :color {:color "#000000" :opacity 1}}]
                             :blur {:id (uuid/next)
                                    :type :layer-blur
                                    :value -23
                                    :hidden false}
                             :background-blur {:id (uuid/next)
                                               :type :background-blur
                                               :value -24
                                               :hidden false}
                             :exports [{:type :png :scale 0 :suffix ""}]
                             :grids [{:type :square
                                      :display true
                                      :params {:size 0
                                               :color {:color "#000000" :opacity 1}}}
                                     {:type :column
                                      :display true
                                      :params {:size -25
                                               :color {:color "#000000" :opacity 1}}}]))
        data      (-> (ctf/make-file-data file-id page-id)
                      (assoc-in [:pages-index page-id :objects shape-id] shape))]

    (t/is (thrown? #?(:clj Exception :cljs js/Error)
                   (ctf/check-file-data data))
          "new schemas reject legacy negative values")

    (let [data'  (cfm/migrate-data data "0027-normalize-constrained-values")
          shape' (get-in data' [:pages-index page-id :objects shape-id])]
      (t/is (= data' (ctf/check-file-data data')) "migrated file data passes the schema")
      (t/is (every? zero? (map #(get shape' %) [:r1 :r2 :r3 :r4])) "corner radii clamped")
      (t/is (= {:row-gap 0 :column-gap 0} (:layout-gap shape')) "layout gaps clamped")
      (t/is (= {:p1 0 :p2 0 :p3 0 :p4 0} (:layout-padding shape')) "layout padding clamped")
      (t/is (= [0] (mapv :value (:layout-grid-rows shape'))) "row tracks clamped")
      (t/is (= [0] (mapv :value (:layout-grid-columns shape'))) "column tracks clamped")
      (t/is (every? zero?
                    (map #(get shape' %)
                         [:layout-item-min-w :layout-item-max-w
                          :layout-item-min-h :layout-item-max-h]))
            "layout item bounds clamped")
      (t/is (every? zero?
                    (map (first (:strokes shape'))
                         [:stroke-width :stroke-width-top :stroke-width-right
                          :stroke-width-bottom :stroke-width-left]))
            "stroke widths clamped")
      (t/is (zero? (get-in shape' [:shadow 0 :blur])) "shadow blur clamped")
      (t/is (zero? (get-in shape' [:blur :value])) "layer blur clamped")
      (t/is (zero? (get-in shape' [:background-blur :value])) "background blur clamped")
      (t/is (= 1 (get-in shape' [:exports 0 :scale])) "export scale reset to default")
      (t/is (= 0.01 (get-in shape' [:grids 0 :params :size])) "square grid size clamped")
      (t/is (= 1 (get-in shape' [:grids 1 :params :size])) "column grid count clamped"))))

(t/deftest migration-0027-normalizes-page-grids-and-variant-properties
  (let [file-id      (uuid/next)
        page-id      (uuid/next)
        component-id (uuid/next)
        long-name    (apply str (repeat 61 "n"))
        long-value   (apply str (repeat 61 "v"))
        data          (-> (ctf/make-file-data file-id page-id)
                          (assoc-in [:pages-index page-id :default-grids]
                                    {:square {:size -1
                                              :color {:color "#000000" :opacity 1}}
                                     :row {:size 0.5
                                           :color {:color "#000000" :opacity 1}}
                                     :column {:size -2
                                              :color {:color "#000000" :opacity 1}}})
                          (assoc-in [:components component-id]
                                    {:id component-id
                                     :name "Variant"
                                     :variant-properties [{:name long-name
                                                           :value long-value}]}))
        data'         (cfm/migrate-data data "0027-normalize-constrained-values")]

    (t/is (= 0.01 (get-in data' [:pages-index page-id :default-grids :square :size]))
          "default square grid size clamped")
    (t/is (= 1 (get-in data' [:pages-index page-id :default-grids :row :size]))
          "default row grid count reset")
    (t/is (= 1 (get-in data' [:pages-index page-id :default-grids :column :size]))
          "default column grid count reset")
    (t/is (= 60 (count (get-in data' [:components component-id :variant-properties 0 :name])))
          "variant property name truncated")
    (t/is (= 60 (count (get-in data' [:components component-id :variant-properties 0 :value])))
          "variant property value truncated")
    (t/is (= data' (cfm/migrate-data data' "0027-normalize-constrained-values"))
          "migration is idempotent")))

(t/deftest migration-0027-runs-through-file-migration
  (let [migration-id "0027-normalize-constrained-values"
        shape-id     (uuid/next)
        file         (ctf/make-file {:name "Legacy constrained values"})
        page-id      (first (get-in file [:data :pages]))
        shape        (-> (cts/setup-shape {:id shape-id :type :rect})
                         (assoc :r1 -1))
        file         (-> file
                         (assoc :migrations (disj cfm/available-migrations migration-id))
                         (assoc-in [:data :pages-index page-id :objects shape-id] shape))
        file'        (cfm/migrate-file file {})]

    (t/is (cfm/need-migration? file) "new migration detected")
    (t/is (not (cfm/need-migration? file')) "new migration recorded")
    (t/is (contains? (:migrations file') migration-id) "migration id persisted")
    (t/is (zero? (get-in file' [:data :pages-index page-id :objects shape-id :r1]))
          "migration repaired file data before schema validation")))
