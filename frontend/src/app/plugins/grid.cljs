;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC

(ns app.plugins.grid
  (:require
   [app.common.data :as d]
   [app.common.schema :as sm]
   [app.common.types.shape.layout :as ctl]
   [app.main.data.workspace.shape-layout :as dwsl]
   [app.main.data.workspace.transforms :as dwt]
   [app.main.store :as st]
   [app.plugins.format :as format]
   [app.plugins.register :as r]
   [app.plugins.utils :as u]
   [app.util.object :as obj]
   [potok.v2.core :as ptk]))

;; Define in `app.plugins.shape` we do this way to prevent circular dependency
(def shape-proxy? nil)

(defn grid-layout-proxy? [p]
  (obj/type-of? p "GridLayoutProxy"))

(defn grid-layout-proxy
  [plugin-id file-id page-id id]
  (obj/reify {:name "GridLayoutProxy"}
    :$plugin {:enumerable false :get (constantly plugin-id)}
    :$id {:enumerable false :get (constantly id)}
    :$file {:enumerable false :get (constantly file-id)}
    :$page {:enumerable false :get (constantly page-id)}

    :dir
    {:this true
     :get #(-> % u/proxy->shape :layout-grid-dir d/name)
     :set
     (fn [_ value]
       (let [value (keyword value)]
         (cond
           (not (contains? ctl/grid-direction-types value))
           (u/not-valid plugin-id :dir value)

           (not (r/check-permission plugin-id "content:write"))
           (u/not-valid plugin-id :dir "Plugin doesn't have 'content:write' permission")

           :else
           (st/emit! (dwsl/update-layout #{id} {:layout-grid-dir value})))))}

    :rows
    {:this true
     :get #(-> % u/proxy->shape :layout-grid-rows format/format-tracks)}

    :columns
    {:this true
     :get #(-> % u/proxy->shape :layout-grid-columns format/format-tracks)}

    :alignItems
    {:this true
     :get #(-> % u/proxy->shape :layout-align-items d/name)
     :set
     (fn [_ value]
       (let [value (keyword value)]
         (cond
           (not (contains? ctl/align-items-types value))
           (u/not-valid plugin-id :alignItems value)

           (not (r/check-permission plugin-id "content:write"))
           (u/not-valid plugin-id :alignItems "Plugin doesn't have 'content:write' permission")

           :else
           (st/emit! (dwsl/update-layout #{id} {:layout-align-items value})))))}

    :alignContent
    {:this true
     :get #(-> % u/proxy->shape :layout-align-content d/name)
     :set
     (fn [_ value]
       (let [value (keyword value)]
         (cond
           (not (contains? ctl/align-content-types value))
           (u/not-valid plugin-id :alignContent value)

           (not (r/check-permission plugin-id "content:write"))
           (u/not-valid plugin-id :alignContent "Plugin doesn't have 'content:write' permission")

           :else
           (st/emit! (dwsl/update-layout #{id} {:layout-align-content value})))))}

    :justifyItems
    {:this true
     :get #(-> % u/proxy->shape :layout-justify-items d/name)
     :set
     (fn [_ value]
       (let [value (keyword value)]
         (cond
           (not (contains? ctl/justify-items-types value))
           (u/not-valid plugin-id :justifyItems value)

           (not (r/check-permission plugin-id "content:write"))
           (u/not-valid plugin-id :justifyItems "Plugin doesn't have 'content:write' permission")

           :else
           (st/emit! (dwsl/update-layout #{id} {:layout-justify-items value})))))}

    :justifyContent
    {:this true
     :get #(-> % u/proxy->shape :layout-justify-content d/name)
     :set
     (fn [_ value]
       (let [value (keyword value)]
         (cond
           (not (contains? ctl/justify-content-types value))
           (u/not-valid plugin-id :justifyContent value)

           (not (r/check-permission plugin-id "content:write"))
           (u/not-valid plugin-id :justifyContent "Plugin doesn't have 'content:write' permission")

           :else
           (st/emit! (dwsl/update-layout #{id} {:layout-justify-content value})))))}

    :rowGap
    {:this true
     :get #(-> % u/proxy->shape :layout-gap :row-gap (d/nilv 0))
     :set
     (fn [_ value]
       (cond
         (not (sm/valid-safe-int? value))
         (u/not-valid plugin-id :rowGap value)

         (not (r/check-permission plugin-id "content:write"))
         (u/not-valid plugin-id :rowGap "Plugin doesn't have 'content:write' permission")

         :else
         (st/emit! (dwsl/update-layout #{id} {:layout-gap {:row-gap value}}))))}

    :columnGap
    {:this true
     :get #(-> % u/proxy->shape :layout-gap :column-gap (d/nilv 0))
     :set
     (fn [_ value]
       (cond
         (not (sm/valid-safe-int? value))
         (u/not-valid plugin-id :columnGap value)

         (not (r/check-permission plugin-id "content:write"))
         (u/not-valid plugin-id :columnGap "Plugin doesn't have 'content:write' permission")

         :else
         (st/emit! (dwsl/update-layout #{id} {:layout-gap {:column-gap value}}))))}

    :verticalPadding
    {:this true
     :get #(-> % u/proxy->shape :layout-padding :p1 (d/nilv 0))
     :set
     (fn [_ value]
       (cond
         (not (sm/valid-safe-int? value))
         (u/not-valid plugin-id :verticalPadding value)

         (not (r/check-permission plugin-id "content:write"))
         (u/not-valid plugin-id :verticalPadding "Plugin doesn't have 'content:write' permission")

         :else
         (st/emit! (dwsl/update-layout #{id} {:layout-padding {:p1 value :p3 value}}))))}

    :horizontalPadding
    {:this true
     :get #(-> % u/proxy->shape :layout-padding :p2 (d/nilv 0))
     :set
     (fn [_ value]
       (cond
         (not (sm/valid-safe-int? value))
         (u/not-valid plugin-id :horizontalPadding value)

         (not (r/check-permission plugin-id "content:write"))
         (u/not-valid plugin-id :horizontalPadding "Plugin doesn't have 'content:write' permission")

         :else
         (st/emit! (dwsl/update-layout #{id} {:layout-padding {:p2 value :p4 value}}))))}

    :topPadding
    {:this true
     :get #(-> % u/proxy->shape :layout-padding :p1 (d/nilv 0))
     :set
     (fn [_ value]
       (cond
         (not (sm/valid-safe-int? value))
         (u/not-valid plugin-id :topPadding value)

         (not (r/check-permission plugin-id "content:write"))
         (u/not-valid plugin-id :topPadding "Plugin doesn't have 'content:write' permission")

         :else
         (st/emit! (dwsl/update-layout #{id} {:layout-padding {:p1 value}}))))}

    :rightPadding
    {:this true
     :get #(-> % u/proxy->shape :layout-padding :p2 (d/nilv 0))
     :set
     (fn [_ value]
       (cond
         (not (sm/valid-safe-int? value))
         (u/not-valid plugin-id :rightPadding value)

         (not (r/check-permission plugin-id "content:write"))
         (u/not-valid plugin-id :righPadding "Plugin doesn't have 'content:write' permission")

         :else
         (st/emit! (dwsl/update-layout #{id} {:layout-padding {:p2 value}}))))}

    :bottomPadding
    {:this true
     :get #(-> % u/proxy->shape :layout-padding :p3 (d/nilv 0))
     :set
     (fn [_ value]
       (cond
         (not (sm/valid-safe-int? value))
         (u/not-valid plugin-id :bottomPadding value)

         (not (r/check-permission plugin-id "content:write"))
         (u/not-valid plugin-id :bottomPadding "Plugin doesn't have 'content:write' permission")

         :else
         (st/emit! (dwsl/update-layout #{id} {:layout-padding {:p3 value}}))))}

    :leftPadding
    {:this true
     :get #(-> % u/proxy->shape :layout-padding :p4 (d/nilv 0))
     :set
     (fn [_ value]
       (cond
         (not (sm/valid-safe-int? value))
         (u/not-valid plugin-id :leftPadding value)

         (not (r/check-permission plugin-id "content:write"))
         (u/not-valid plugin-id :leftPadding "Plugin doesn't have 'content:write' permission")

         :else
         (st/emit! (dwsl/update-layout #{id} {:layout-padding {:p4 value}}))))}

    :addRow
    (fn [type value]
      (let [type (keyword type)]
        (cond
          (not (contains? ctl/grid-track-types type))
          (u/not-valid plugin-id :addRow-type type)

          (and (or (= :percent type) (= :flex type) (= :fixed type))
               (not (sm/valid-safe-number? value)))
          (u/not-valid plugin-id :addRow-value value)

          (not (r/check-permission plugin-id "content:write"))
          (u/not-valid plugin-id :addRow "Plugin doesn't have 'content:write' permission")

          :else
          (st/emit! (dwsl/add-layout-track #{id} :row {:type type :value value})))))

    :addRowAtIndex
    (fn [index type value]
      (let [type (keyword type)]
        (cond
          (not (sm/valid-safe-int? index))
          (u/not-valid plugin-id :addRowAtIndex-index index)

          (not (contains? ctl/grid-track-types type))
          (u/not-valid plugin-id :addRowAtIndex-type type)

          (and (or (= :percent type) (= :flex type) (= :fixed type))
               (not (sm/valid-safe-number? value)))
          (u/not-valid plugin-id :addRowAtIndex-value value)

          (not (r/check-permission plugin-id "content:write"))
          (u/not-valid plugin-id :addRowAtIndex "Plugin doesn't have 'content:write' permission")

          :else
          (st/emit! (dwsl/add-layout-track #{id} :row {:type type :value value} index)))))

    :addColumn
    (fn [type value]
      (let [type (keyword type)]
        (cond
          (not (contains? ctl/grid-track-types type))
          (u/not-valid plugin-id :addColumn-type type)

          (and (or (= :percent type) (= :flex type) (= :lex type))
               (not (sm/valid-safe-number? value)))
          (u/not-valid plugin-id :addColumn-value value)

          (not (r/check-permission plugin-id "content:write"))
          (u/not-valid plugin-id :addColumn "Plugin doesn't have 'content:write' permission")

          :else
          (st/emit! (dwsl/add-layout-track #{id} :column {:type type :value value})))))

    :addColumnAtIndex
    (fn [index type value]
      (cond
        (not (sm/valid-safe-int? index))
        (u/not-valid plugin-id :addColumnAtIndex-index index)

        (not (contains? ctl/grid-track-types type))
        (u/not-valid plugin-id :addColumnAtIndex-type type)

        (and (or (= :percent type) (= :flex type) (= :fixed type))
             (not (sm/valid-safe-number? value)))
        (u/not-valid plugin-id :addColumnAtIndex-value value)

        (not (r/check-permission plugin-id "content:write"))
        (u/not-valid plugin-id :addColumnAtIndex "Plugin doesn't have 'content:write' permission")

        :else
        (let [type (keyword type)]
          (st/emit! (dwsl/add-layout-track #{id} :column {:type type :value value} index)))))

    :removeRow
    (fn [index]
      (cond
        (not (sm/valid-safe-int? index))
        (u/not-valid plugin-id :removeRow index)

        (not (r/check-permission plugin-id "content:write"))
        (u/not-valid plugin-id :removeRow "Plugin doesn't have 'content:write' permission")

        :else
        (st/emit! (dwsl/remove-layout-track #{id} :row index))))

    :removeColumn
    (fn [index]
      (cond
        (not (sm/valid-safe-int? index))
        (u/not-valid plugin-id :removeColumn index)

        (not (r/check-permission plugin-id "content:write"))
        (u/not-valid plugin-id :removeColumn "Plugin doesn't have 'content:write' permission")

        :else
        (st/emit! (dwsl/remove-layout-track #{id} :column index))))

    :setColumn
    (fn [index type value]
      (let [type (keyword type)]
        (cond
          (not (sm/valid-safe-int? index))
          (u/not-valid plugin-id :setColumn-index index)

          (not (contains? ctl/grid-track-types type))
          (u/not-valid plugin-id :setColumn-type type)

          (and (or (= :percent type) (= :flex type) (= :fixed type))
               (not (sm/valid-safe-number? value)))
          (u/not-valid plugin-id :setColumn-value value)

          (not (r/check-permission plugin-id "content:write"))
          (u/not-valid plugin-id :setColumn "Plugin doesn't have 'content:write' permission")

          :else
          (st/emit! (dwsl/change-layout-track #{id} :column index (d/without-nils {:type type :value value}))))))

    :setRow
    (fn [index type value]
      (let [type (keyword type)]
        (cond
          (not (sm/valid-safe-int? index))
          (u/not-valid plugin-id :setRow-index index)

          (not (contains? ctl/grid-track-types type))
          (u/not-valid plugin-id :setRow-type type)

          (and (or (= :percent type) (= :flex type) (= :fixed type))
               (not (sm/valid-safe-number? value)))
          (u/not-valid plugin-id :setRow-value value)

          (not (r/check-permission plugin-id "content:write"))
          (u/not-valid plugin-id :setRow "Plugin doesn't have 'content:write' permission")

          :else
          (st/emit! (dwsl/change-layout-track #{id} :row index (d/without-nils {:type type :value value}))))))

    :remove
    (fn []
      (cond
        (not (r/check-permission plugin-id "content:write"))
        (u/not-valid plugin-id :remove "Plugin doesn't have 'content:write' permission")

        :else
        (st/emit! (dwsl/remove-layout #{id}))))

    :appendChild
    (fn [child row column]
      (cond
        (not (shape-proxy? child))
        (u/not-valid plugin-id :appendChild-child child)

        (or (< row 0) (not (sm/valid-safe-int? row)))
        (u/not-valid plugin-id :appendChild-row row)

        (or (< column 0) (not (sm/valid-safe-int? column)))
        (u/not-valid plugin-id :appendChild-column column)

        (not (r/check-permission plugin-id "content:write"))
        (u/not-valid plugin-id :appendChild "Plugin doesn't have 'content:write' permission")

        :else
        (let [child-id  (obj/get child "$id")]
          (st/emit! (dwt/move-shapes-to-frame #{child-id} id nil [row column])
                    (ptk/data-event :layout/update {:ids [id]})))))))

(defn layout-cell-proxy? [p]
  (obj/type-of? p "GridCellProxy"))

(defn layout-cell-proxy
  [plugin-id file-id page-id id]
  (letfn [(locate-cell [_]
            (let [shape (u/locate-shape file-id page-id id)
                  parent (u/locate-shape file-id page-id (:parent-id shape))]
              (ctl/get-cell-by-shape-id parent id)))]

    (obj/reify {:name "GridCellProxy"}
      :$plugin {:enumerable false :get (constantly plugin-id)}
      :$id {:enumerable false :get (constantly id)}
      :$file {:enumerable false :get (constantly file-id)}
      :$page {:enumerable false :get (constantly page-id)}

      :row
      {:this true
       :get #(-> % locate-cell :row)
       :set
       (fn [self value]
         (let [cell (locate-cell self)
               shape (u/proxy->shape self)]
           (cond
             (not (sm/valid-safe-int? value))
             (u/not-valid plugin-id :row-value value)

             (nil? cell)
             (u/not-valid plugin-id :row-cell "cell not found")

             (not (r/check-permission plugin-id "content:write"))
             (u/not-valid plugin-id :row "Plugin doesn't have 'content:write' permission")

             :else
             (st/emit! (dwsl/update-grid-cell-position (:parent-id shape) (:id cell) {:row value})))))}

      :rowSpan
      {:this true
       :get #(-> % locate-cell :row-span)
       :set
       (fn [self value]
         (let [shape (u/proxy->shape self)
               cell (locate-cell self)]
           (cond
             (not (sm/valid-safe-int? value))
             (u/not-valid plugin-id :rowSpan-value value)

             (nil? cell)
             (u/not-valid plugin-id :rowSpan-cell "cell not found")

             (not (r/check-permission plugin-id "content:write"))
             (u/not-valid plugin-id :rowSpan "Plugin doesn't have 'content:write' permission")

             :else
             (st/emit! (dwsl/update-grid-cell-position (:parent-id shape) (:id cell) {:row-span value})))))}

      :column
      {:this true
       :get #(-> % locate-cell :column)
       :set
       (fn [self value]
         (let [shape (u/proxy->shape self)
               cell (locate-cell self)]
           (cond
             (not (sm/valid-safe-int? value))
             (u/not-valid plugin-id :column-value value)

             (nil? cell)
             (u/not-valid plugin-id :column-cell "cell not found")

             (not (r/check-permission plugin-id "content:write"))
             (u/not-valid plugin-id :column "Plugin doesn't have 'content:write' permission")

             :else
             (st/emit! (dwsl/update-grid-cell-position (:parent-id shape) (:id cell) {:column value})))))}

      :columnSpan
      {:this true
       :get #(-> % locate-cell :column-span)
       :set
       (fn [self value]
         (let [shape (u/proxy->shape self)
               cell (locate-cell self)]
           (cond
             (not (sm/valid-safe-int? value))
             (u/not-valid plugin-id :columnSpan-value value)

             (nil? cell)
             (u/not-valid plugin-id :columnSpan-cell "cell not found")

             (not (r/check-permission plugin-id "content:write"))
             (u/not-valid plugin-id :columnSpan "Plugin doesn't have 'content:write' permission")

             :else
             (st/emit! (dwsl/update-grid-cell-position (:parent-id shape) (:id cell) {:column-span value})))))}

      :areaName
      {:this true
       :get #(-> % locate-cell :area-name)
       :set
       (fn [self value]
         (let [shape (u/proxy->shape self)
               cell (locate-cell self)]
           (cond
             (not (string? value))
             (u/not-valid plugin-id :areaName-value value)

             (nil? cell)
             (u/not-valid plugin-id :areaName-cell "cell not found")

             (not (r/check-permission plugin-id "content:write"))
             (u/not-valid plugin-id :areaName "Plugin doesn't have 'content:write' permission")

             :else
             (st/emit! (dwsl/update-grid-cells (:parent-id shape) #{(:id cell)} {:area-name value})))))}

      :position
      {:this true
       :get #(-> % locate-cell :position d/name)
       :set
       (fn [self value]
         (let [shape (u/proxy->shape self)
               cell (locate-cell self)
               value (keyword value)]
           (cond
             (not (contains? ctl/grid-position-types value))
             (u/not-valid plugin-id :position-value value)

             (nil? cell)
             (u/not-valid plugin-id :position-cell "cell not found")

             (not (r/check-permission plugin-id "content:write"))
             (u/not-valid plugin-id :position "Plugin doesn't have 'content:write' permission")

             :else
             (st/emit! (dwsl/change-cells-mode (:parent-id shape) #{(:id cell)} value)))))}

      :alignSelf
      {:this true
       :get #(-> % locate-cell :align-self d/name)
       :set
       (fn [self value]
         (let [shape (u/proxy->shape self)
               value (keyword value)
               cell (locate-cell self)]
           (cond
             (not (contains? ctl/grid-cell-align-self-types value))
             (u/not-valid plugin-id :alignSelf-value value)

             (nil? cell)
             (u/not-valid plugin-id :alignSelf-cell "cell not found")

             (not (r/check-permission plugin-id "content:write"))
             (u/not-valid plugin-id :alignSelf "Plugin doesn't have 'content:write' permission")

             :else
             (st/emit! (dwsl/update-grid-cells (:parent-id shape) #{(:id cell)} {:align-self value})))))}

      :justifySelf
      {:this true
       :get #(-> % locate-cell :justify-self d/name)
       :set
       (fn [self value]
         (let [shape (u/proxy->shape self)
               value (keyword value)
               cell (locate-cell self)]
           (cond
             (not (contains? ctl/grid-cell-justify-self-types value))
             (u/not-valid plugin-id :justifySelf-value value)

             (nil? cell)
             (u/not-valid plugin-id :justifySelf-cell "cell not found")

             (not (r/check-permission plugin-id "content:write"))
             (u/not-valid plugin-id :justifySelf "Plugin doesn't have 'content:write' permission")

             :else
             (st/emit! (dwsl/update-grid-cells (:parent-id shape) #{(:id cell)} {:justify-self value})))))})))
