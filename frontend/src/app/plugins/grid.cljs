;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC

(ns app.plugins.grid
  (:require
   [app.common.data :as d]
   [app.common.record :as crc]
   [app.common.spec :as us]
   [app.common.types.shape.layout :as ctl]
   [app.main.data.workspace.shape-layout :as dwsl]
   [app.main.data.workspace.transforms :as dwt]
   [app.main.store :as st]
   [app.plugins.format :as format]
   [app.plugins.utils :as u]
   [app.util.object :as obj]
   [potok.v2.core :as ptk]))

;; Define in `app.plugins.shape` we do this way to prevent circular dependency
(def shape-proxy? nil)

(deftype GridLayout [$plugin $file $page $id]
  Object

  (addRow
    [_ type value]
    (let [type (keyword type)]
      (cond
        (not (contains? ctl/grid-track-types type))
        (u/display-not-valid :addRow-type type)

        (and (or (= :percent type) (= :flex type) (= :fixed type))
             (not (us/safe-number? value)))
        (u/display-not-valid :addRow-value value)

        :else
        (st/emit! (dwsl/add-layout-track #{$id} :row {:type type :value value})))))

  (addRowAtIndex
    [_ index type value]
    (let [type (keyword type)]
      (cond
        (not (us/safe-int? index))
        (u/display-not-valid :addRowAtIndex-index index)

        (not (contains? ctl/grid-track-types type))
        (u/display-not-valid :addRowAtIndex-type type)

        (and (or (= :percent type) (= :flex type) (= :fixed type))
             (not (us/safe-number? value)))
        (u/display-not-valid :addRowAtIndex-value value)

        :else
        (st/emit! (dwsl/add-layout-track #{$id} :row {:type type :value value} index)))))

  (addColumn
    [_ type value]
    (let [type (keyword type)]
      (cond
        (not (contains? ctl/grid-track-types type))
        (u/display-not-valid :addColumn-type type)

        (and (or (= :percent type) (= :flex type) (= :lex type))
             (not (us/safe-number? value)))
        (u/display-not-valid :addColumn-value value)

        :else
        (st/emit! (dwsl/add-layout-track #{$id} :column {:type type :value value})))))

  (addColumnAtIndex
    [_ index type value]
    (cond
      (not (us/safe-int? index))
      (u/display-not-valid :addColumnAtIndex-index index)

      (not (contains? ctl/grid-track-types type))
      (u/display-not-valid :addColumnAtIndex-type type)

      (and (or (= :percent type) (= :flex type) (= :fixed type))
           (not (us/safe-number? value)))
      (u/display-not-valid :addColumnAtIndex-value value)

      :else
      (let [type (keyword type)]
        (st/emit! (dwsl/add-layout-track #{$id} :column {:type type :value value} index)))))

  (removeRow
    [_ index]
    (cond
      (not (us/safe-int? index))
      (u/display-not-valid :removeRow index)

      :else
      (st/emit! (dwsl/remove-layout-track #{$id} :row index))))

  (removeColumn
    [_ index]
    (cond
      (not (us/safe-int? index))
      (u/display-not-valid :removeColumn index)

      :else
      (st/emit! (dwsl/remove-layout-track #{$id} :column index))))

  (setColumn
    [_ index type value]
    (let [type (keyword type)]
      (cond
        (not (us/safe-int? index))
        (u/display-not-valid :setColumn-index index)

        (not (contains? ctl/grid-track-types type))
        (u/display-not-valid :setColumn-type type)

        (and (or (= :percent type) (= :flex type) (= :fixed type))
             (not (us/safe-number? value)))
        (u/display-not-valid :setColumn-value value)

        :else
        (st/emit! (dwsl/change-layout-track #{$id} :column index (d/without-nils {:type type :value value}))))))

  (setRow
    [_ index type value]
    (let [type (keyword type)]
      (cond
        (not (us/safe-int? index))
        (u/display-not-valid :setRow-index index)

        (not (contains? ctl/grid-track-types type))
        (u/display-not-valid :setRow-type type)

        (and (or (= :percent type) (= :flex type) (= :fixed type))
             (not (us/safe-number? value)))
        (u/display-not-valid :setRow-value value)

        :else
        (st/emit! (dwsl/change-layout-track #{$id} :row index (d/without-nils {:type type :value value}))))))

  (remove
    [_]
    (st/emit! (dwsl/remove-layout #{$id})))

  (appendChild
    [_ child row column]
    (cond
      (not (shape-proxy? child))
      (u/display-not-valid :appendChild-child child)

      (or (< row 0) (not (us/safe-int? row)))
      (u/display-not-valid :appendChild-row row)

      (or (< column 0) (not (us/safe-int? column)))
      (u/display-not-valid :appendChild-column column)

      :else
      (let [child-id  (obj/get child "$id")]
        (st/emit! (dwt/move-shapes-to-frame #{child-id} $id nil [row column])
                  (ptk/data-event :layout/update {:ids [$id]}))))))

(defn grid-layout-proxy? [p]
  (instance? GridLayout p))

(defn grid-layout-proxy
  [plugin-id file-id page-id id]
  (-> (GridLayout. plugin-id file-id page-id id)
      (crc/add-properties!
       {:name "$plugin" :enumerable false :get (constantly plugin-id)}
       {:name "$id" :enumerable false :get (constantly id)}
       {:name "$file" :enumerable false :get (constantly file-id)}
       {:name "$page" :enumerable false :get (constantly page-id)}

       {:name "dir"
        :get #(-> % u/proxy->shape :layout-grid-dir d/name)
        :set
        (fn [self value]
          (let [value (keyword value)]
            (cond
              (not (contains? ctl/grid-direction-types value))
              (u/display-not-valid :dir value)

              :else
              (let [id (obj/get self "$id")]
                (st/emit! (dwsl/update-layout #{id} {:layout-grid-dir value}))))))}

       {:name "rows"
        :get #(-> % u/proxy->shape :layout-grid-rows format/format-tracks)}

       {:name "columns"
        :get #(-> % u/proxy->shape :layout-grid-columns format/format-tracks)}

       {:name "alignItems"
        :get #(-> % u/proxy->shape :layout-align-items d/name)
        :set
        (fn [self value]
          (let [value (keyword value)]
            (cond
              (not (contains? ctl/align-items-types value))
              (u/display-not-valid :alignItems value)

              :else
              (let [id (obj/get self "$id")]
                (st/emit! (dwsl/update-layout #{id} {:layout-align-items value}))))))}

       {:name "alignContent"
        :get #(-> % u/proxy->shape :layout-align-content d/name)
        :set
        (fn [self value]
          (let [value (keyword value)]
            (cond
              (not (contains? ctl/align-content-types value))
              (u/display-not-valid :alignContent value)

              :else
              (let [id (obj/get self "$id")]
                (st/emit! (dwsl/update-layout #{id} {:layout-align-content value}))))))}

       {:name "justifyItems"
        :get #(-> % u/proxy->shape :layout-justify-items d/name)
        :set
        (fn [self value]
          (let [value (keyword value)]
            (cond
              (not (contains? ctl/justify-items-types value))
              (u/display-not-valid :justifyItems value)

              :else
              (let [id (obj/get self "$id")]
                (st/emit! (dwsl/update-layout #{id} {:layout-justify-items value}))))))}

       {:name "justifyContent"
        :get #(-> % u/proxy->shape :layout-justify-content d/name)
        :set
        (fn [self value]
          (let [value (keyword value)]
            (cond
              (not (contains? ctl/justify-content-types value))
              (u/display-not-valid :justifyContent value)

              :else
              (let [id (obj/get self "$id")]
                (st/emit! (dwsl/update-layout #{id} {:layout-justify-content value}))))))}

       {:name "rowGap"
        :get #(-> % u/proxy->shape :layout-gap :row-gap (d/nilv 0))
        :set
        (fn [self value]
          (cond
            (not (us/safe-int? value))
            (u/display-not-valid :rowGap value)

            :else
            (let [id (obj/get self "$id")]
              (st/emit! (dwsl/update-layout #{id} {:layout-gap {:row-gap value}})))))}

       {:name "columnGap"
        :get #(-> % u/proxy->shape :layout-gap :column-gap (d/nilv 0))
        :set
        (fn [self value]
          (cond
            (not (us/safe-int? value))
            (u/display-not-valid :columnGap value)

            :else
            (let [id (obj/get self "$id")]
              (st/emit! (dwsl/update-layout #{id} {:layout-gap {:column-gap value}})))))}

       {:name "verticalPadding"
        :get #(-> % u/proxy->shape :layout-padding :p1 (d/nilv 0))
        :set
        (fn [self value]
          (cond
            (not (us/safe-int? value))
            (u/display-not-valid :verticalPadding value)

            :else
            (let [id (obj/get self "$id")]
              (st/emit! (dwsl/update-layout #{id} {:layout-padding {:p1 value :p3 value}})))))}

       {:name "horizontalPadding"
        :get #(-> % u/proxy->shape :layout-padding :p2 (d/nilv 0))
        :set
        (fn [self value]
          (cond
            (not (us/safe-int? value))
            (u/display-not-valid :horizontalPadding value)

            :else
            (let [id (obj/get self "$id")]
              (st/emit! (dwsl/update-layout #{id} {:layout-padding {:p2 value :p4 value}})))))}

       {:name "topPadding"
        :get #(-> % u/proxy->shape :layout-padding :p1 (d/nilv 0))
        :set
        (fn [self value]
          (cond
            (not (us/safe-int? value))
            (u/display-not-valid :topPadding value)

            :else
            (let [id (obj/get self "$id")]
              (st/emit! (dwsl/update-layout #{id} {:layout-padding {:p1 value}})))))}

       {:name "rightPadding"
        :get #(-> % u/proxy->shape :layout-padding :p2 (d/nilv 0))
        :set
        (fn [self value]
          (cond
            (not (us/safe-int? value))
            (u/display-not-valid :rightPadding value)

            :else
            (let [id (obj/get self "$id")]
              (st/emit! (dwsl/update-layout #{id} {:layout-padding {:p2 value}})))))}

       {:name "bottomPadding"
        :get #(-> % u/proxy->shape :layout-padding :p3 (d/nilv 0))
        :set
        (fn [self value]
          (cond
            (not (us/safe-int? value))
            (u/display-not-valid :bottomPadding value)

            :else
            (let [id (obj/get self "$id")]
              (st/emit! (dwsl/update-layout #{id} {:layout-padding {:p3 value}})))))}

       {:name "leftPadding"
        :get #(-> % u/proxy->shape :layout-padding :p4 (d/nilv 0))
        :set
        (fn [self value]
          (cond
            (not (us/safe-int? value))
            (u/display-not-valid :leftPadding value)

            :else
            (let [id (obj/get self "$id")]
              (st/emit! (dwsl/update-layout #{id} {:layout-padding {:p4 value}})))))})))

(deftype GridCellProxy [$plugin $file $page $id])

(defn layout-cell-proxy? [p]
  (instance? GridCellProxy p))

(defn layout-cell-proxy
  [plugin-id file-id page-id id]
  (letfn [(locate-cell [_]
            (let [shape (u/locate-shape file-id page-id id)
                  parent (u/locate-shape file-id page-id (:parent-id shape))]
              (ctl/get-cell-by-shape-id parent id)))]

    (-> (GridCellProxy. plugin-id file-id page-id id)
        (crc/add-properties!
         {:name "$plugin" :enumerable false :get (constantly plugin-id)}
         {:name "$id" :enumerable false :get (constantly id)}
         {:name "$file" :enumerable false :get (constantly file-id)}
         {:name "$page" :enumerable false :get (constantly page-id)}

         {:name "row"
          :get #(-> % locate-cell :row)
          :set
          (fn [self value]
            (let [cell (locate-cell self)
                  shape (u/proxy->shape self)]
              (cond
                (not (us/safe-int? value))
                (u/display-not-valid :row value)

                (nil? cell)
                (u/display-not-valid :cell "cell not found")

                :else
                (st/emit! (dwsl/update-grid-cell-position (:parent-id shape) (:id cell) {:row value})))))}

         {:name "rowSpan"
          :get #(-> % locate-cell :row-span)
          :set
          (fn [self value]
            (let [shape (u/proxy->shape self)
                  cell (locate-cell self)]
              (cond
                (not (us/safe-int? value))
                (u/display-not-valid :rowSpan-value value)

                (nil? cell)
                (u/display-not-valid :rowSpan-cell "cell not found")

                :else
                (st/emit! (dwsl/update-grid-cell-position (:parent-id shape) (:id cell) {:row-span value})))))}

         {:name "column"
          :get #(-> % locate-cell :column)
          :set
          (fn [self value]
            (let [shape (u/proxy->shape self)
                  cell (locate-cell self)]
              (cond
                (not (us/safe-int? value))
                (u/display-not-valid :column-value value)

                (nil? cell)
                (u/display-not-valid :column-cell "cell not found")

                :else
                (st/emit! (dwsl/update-grid-cell-position (:parent-id shape) (:id cell) {:column value})))))}

         {:name "columnSpan"
          :get #(-> % locate-cell :column-span)
          :set
          (fn [self value]
            (let [shape (u/proxy->shape self)
                  cell (locate-cell self)]
              (cond
                (not (us/safe-int? value))
                (u/display-not-valid :columnSpan-value value)

                (nil? cell)
                (u/display-not-valid :columnSpan-cell "cell not found")

                :else
                (st/emit! (dwsl/update-grid-cell-position (:parent-id shape) (:id cell) {:column-span value})))))}

         {:name "areaName"
          :get #(-> % locate-cell :area-name)
          :set
          (fn [self value]
            (let [shape (u/proxy->shape self)
                  cell (locate-cell self)]
              (cond
                (not (string? value))
                (u/display-not-valid :areaName-value value)

                (nil? cell)
                (u/display-not-valid :areaName-cell "cell not found")

                :else
                (st/emit! (dwsl/update-grid-cells (:parent-id shape) #{(:id cell)} {:area-name value})))))}

         {:name "position"
          :get #(-> % locate-cell :position d/name)
          :set
          (fn [self value]
            (let [shape (u/proxy->shape self)
                  cell (locate-cell self)
                  value (keyword value)]
              (cond
                (not (contains? ctl/grid-position-types value))
                (u/display-not-valid :position-value value)

                (nil? cell)
                (u/display-not-valid :position-cell "cell not found")

                :else
                (st/emit! (dwsl/change-cells-mode (:parent-id shape) #{(:id cell)} value)))))}

         {:name "alignSelf"
          :get #(-> % locate-cell :align-self d/name)
          :set
          (fn [self value]
            (let [shape (u/proxy->shape self)
                  value (keyword value)
                  cell (locate-cell self)]
              (cond
                (not (contains? ctl/grid-cell-align-self-types value))
                (u/display-not-valid :alignSelf-value value)

                (nil? cell)
                (u/display-not-valid :alignSelf-cell "cell not found")

                :else
                (st/emit! (dwsl/update-grid-cells (:parent-id shape) #{(:id cell)} {:align-self value})))))}

         {:name "justifySelf"
          :get #(-> % locate-cell :justify-self d/name)
          :set
          (fn [self value]
            (let [shape (u/proxy->shape self)
                  value (keyword value)
                  cell (locate-cell self)]
              (cond
                (not (contains? ctl/grid-cell-justify-self-types value))
                (u/display-not-valid :justifySelf-value value)

                (nil? cell)
                (u/display-not-valid :justifySelf-cell "cell not found")

                :else
                (st/emit! (dwsl/update-grid-cells (:parent-id shape) #{(:id cell)} {:justify-self value})))))}))))
