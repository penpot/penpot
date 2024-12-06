;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC

(ns app.plugins.grid
  (:require
   [app.common.data :as d]
   [app.common.spec :as us]
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
           (u/display-not-valid :dir value)

           (not (r/check-permission plugin-id "content:write"))
           (u/display-not-valid :dir "Plugin doesn't have 'content:write' permission")

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
           (u/display-not-valid :alignItems value)

           (not (r/check-permission plugin-id "content:write"))
           (u/display-not-valid :alignItems "Plugin doesn't have 'content:write' permission")

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
           (u/display-not-valid :alignContent value)

           (not (r/check-permission plugin-id "content:write"))
           (u/display-not-valid :alignContent "Plugin doesn't have 'content:write' permission")

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
           (u/display-not-valid :justifyItems value)

           (not (r/check-permission plugin-id "content:write"))
           (u/display-not-valid :justifyItems "Plugin doesn't have 'content:write' permission")

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
           (u/display-not-valid :justifyContent value)

           (not (r/check-permission plugin-id "content:write"))
           (u/display-not-valid :justifyContent "Plugin doesn't have 'content:write' permission")

           :else
           (st/emit! (dwsl/update-layout #{id} {:layout-justify-content value})))))}

    :rowGap
    {:this true
     :get #(-> % u/proxy->shape :layout-gap :row-gap (d/nilv 0))
     :set
     (fn [_ value]
       (cond
         (not (us/safe-int? value))
         (u/display-not-valid :rowGap value)

         (not (r/check-permission plugin-id "content:write"))
         (u/display-not-valid :rowGap "Plugin doesn't have 'content:write' permission")

         :else
         (st/emit! (dwsl/update-layout #{id} {:layout-gap {:row-gap value}}))))}

    :columnGap
    {:this true
     :get #(-> % u/proxy->shape :layout-gap :column-gap (d/nilv 0))
     :set
     (fn [_ value]
       (cond
         (not (us/safe-int? value))
         (u/display-not-valid :columnGap value)

         (not (r/check-permission plugin-id "content:write"))
         (u/display-not-valid :columnGap "Plugin doesn't have 'content:write' permission")

         :else
         (st/emit! (dwsl/update-layout #{id} {:layout-gap {:column-gap value}}))))}

    :verticalPadding
    {:this true
     :get #(-> % u/proxy->shape :layout-padding :p1 (d/nilv 0))
     :set
     (fn [_ value]
       (cond
         (not (us/safe-int? value))
         (u/display-not-valid :verticalPadding value)

         (not (r/check-permission plugin-id "content:write"))
         (u/display-not-valid :verticalPadding "Plugin doesn't have 'content:write' permission")

         :else
         (st/emit! (dwsl/update-layout #{id} {:layout-padding {:p1 value :p3 value}}))))}

    :horizontalPadding
    {:this true
     :get #(-> % u/proxy->shape :layout-padding :p2 (d/nilv 0))
     :set
     (fn [_ value]
       (cond
         (not (us/safe-int? value))
         (u/display-not-valid :horizontalPadding value)

         (not (r/check-permission plugin-id "content:write"))
         (u/display-not-valid :horizontalPadding "Plugin doesn't have 'content:write' permission")

         :else
         (st/emit! (dwsl/update-layout #{id} {:layout-padding {:p2 value :p4 value}}))))}

    :topPadding
    {:this true
     :get #(-> % u/proxy->shape :layout-padding :p1 (d/nilv 0))
     :set
     (fn [_ value]
       (cond
         (not (us/safe-int? value))
         (u/display-not-valid :topPadding value)

         (not (r/check-permission plugin-id "content:write"))
         (u/display-not-valid :topPadding "Plugin doesn't have 'content:write' permission")

         :else
         (st/emit! (dwsl/update-layout #{id} {:layout-padding {:p1 value}}))))}

    :rightPadding
    {:this true
     :get #(-> % u/proxy->shape :layout-padding :p2 (d/nilv 0))
     :set
     (fn [_ value]
       (cond
         (not (us/safe-int? value))
         (u/display-not-valid :rightPadding value)

         (not (r/check-permission plugin-id "content:write"))
         (u/display-not-valid :righPadding "Plugin doesn't have 'content:write' permission")

         :else
         (st/emit! (dwsl/update-layout #{id} {:layout-padding {:p2 value}}))))}

    :bottomPadding
    {:this true
     :get #(-> % u/proxy->shape :layout-padding :p3 (d/nilv 0))
     :set
     (fn [_ value]
       (cond
         (not (us/safe-int? value))
         (u/display-not-valid :bottomPadding value)

         (not (r/check-permission plugin-id "content:write"))
         (u/display-not-valid :bottomPadding "Plugin doesn't have 'content:write' permission")

         :else
         (st/emit! (dwsl/update-layout #{id} {:layout-padding {:p3 value}}))))}

    :leftPadding
    {:this true
     :get #(-> % u/proxy->shape :layout-padding :p4 (d/nilv 0))
     :set
     (fn [_ value]
       (cond
         (not (us/safe-int? value))
         (u/display-not-valid :leftPadding value)

         (not (r/check-permission plugin-id "content:write"))
         (u/display-not-valid :leftPadding "Plugin doesn't have 'content:write' permission")

         :else
         (st/emit! (dwsl/update-layout #{id} {:layout-padding {:p4 value}}))))}

    :addRow
    (fn [type value]
      (let [type (keyword type)]
        (cond
          (not (contains? ctl/grid-track-types type))
          (u/display-not-valid :addRow-type type)

          (and (or (= :percent type) (= :flex type) (= :fixed type))
               (not (us/safe-number? value)))
          (u/display-not-valid :addRow-value value)

          (not (r/check-permission plugin-id "content:write"))
          (u/display-not-valid :addRow "Plugin doesn't have 'content:write' permission")

          :else
          (st/emit! (dwsl/add-layout-track #{id} :row {:type type :value value})))))

    :addRowAtIndex
    (fn [index type value]
      (let [type (keyword type)]
        (cond
          (not (us/safe-int? index))
          (u/display-not-valid :addRowAtIndex-index index)

          (not (contains? ctl/grid-track-types type))
          (u/display-not-valid :addRowAtIndex-type type)

          (and (or (= :percent type) (= :flex type) (= :fixed type))
               (not (us/safe-number? value)))
          (u/display-not-valid :addRowAtIndex-value value)

          (not (r/check-permission plugin-id "content:write"))
          (u/display-not-valid :addRowAtIndex "Plugin doesn't have 'content:write' permission")

          :else
          (st/emit! (dwsl/add-layout-track #{id} :row {:type type :value value} index)))))

    :addColumn
    (fn [type value]
      (let [type (keyword type)]
        (cond
          (not (contains? ctl/grid-track-types type))
          (u/display-not-valid :addColumn-type type)

          (and (or (= :percent type) (= :flex type) (= :lex type))
               (not (us/safe-number? value)))
          (u/display-not-valid :addColumn-value value)

          (not (r/check-permission plugin-id "content:write"))
          (u/display-not-valid :addColumn "Plugin doesn't have 'content:write' permission")

          :else
          (st/emit! (dwsl/add-layout-track #{id} :column {:type type :value value})))))

    :addColumnAtIndex
    (fn [index type value]
      (cond
        (not (us/safe-int? index))
        (u/display-not-valid :addColumnAtIndex-index index)

        (not (contains? ctl/grid-track-types type))
        (u/display-not-valid :addColumnAtIndex-type type)

        (and (or (= :percent type) (= :flex type) (= :fixed type))
             (not (us/safe-number? value)))
        (u/display-not-valid :addColumnAtIndex-value value)

        (not (r/check-permission plugin-id "content:write"))
        (u/display-not-valid :addColumnAtIndex "Plugin doesn't have 'content:write' permission")

        :else
        (let [type (keyword type)]
          (st/emit! (dwsl/add-layout-track #{id} :column {:type type :value value} index)))))

    :removeRow
    (fn [index]
      (cond
        (not (us/safe-int? index))
        (u/display-not-valid :removeRow index)

        (not (r/check-permission plugin-id "content:write"))
        (u/display-not-valid :removeRow "Plugin doesn't have 'content:write' permission")

        :else
        (st/emit! (dwsl/remove-layout-track #{id} :row index))))

    :removeColumn
    (fn [index]
      (cond
        (not (us/safe-int? index))
        (u/display-not-valid :removeColumn index)

        (not (r/check-permission plugin-id "content:write"))
        (u/display-not-valid :removeColumn "Plugin doesn't have 'content:write' permission")

        :else
        (st/emit! (dwsl/remove-layout-track #{id} :column index))))

    :setColumn
    (fn [index type value]
      (let [type (keyword type)]
        (cond
          (not (us/safe-int? index))
          (u/display-not-valid :setColumn-index index)

          (not (contains? ctl/grid-track-types type))
          (u/display-not-valid :setColumn-type type)

          (and (or (= :percent type) (= :flex type) (= :fixed type))
               (not (us/safe-number? value)))
          (u/display-not-valid :setColumn-value value)

          (not (r/check-permission plugin-id "content:write"))
          (u/display-not-valid :setColumn "Plugin doesn't have 'content:write' permission")

          :else
          (st/emit! (dwsl/change-layout-track #{id} :column index (d/without-nils {:type type :value value}))))))

    :setRow
    (fn [index type value]
      (let [type (keyword type)]
        (cond
          (not (us/safe-int? index))
          (u/display-not-valid :setRow-index index)

          (not (contains? ctl/grid-track-types type))
          (u/display-not-valid :setRow-type type)

          (and (or (= :percent type) (= :flex type) (= :fixed type))
               (not (us/safe-number? value)))
          (u/display-not-valid :setRow-value value)

          (not (r/check-permission plugin-id "content:write"))
          (u/display-not-valid :setRow "Plugin doesn't have 'content:write' permission")

          :else
          (st/emit! (dwsl/change-layout-track #{id} :row index (d/without-nils {:type type :value value}))))))

    :remove
    (fn []
      (cond
        (not (r/check-permission plugin-id "content:write"))
        (u/display-not-valid :remove "Plugin doesn't have 'content:write' permission")

        :else
        (st/emit! (dwsl/remove-layout #{id}))))

    :appendChild
    (fn [child row column]
      (cond
        (not (shape-proxy? child))
        (u/display-not-valid :appendChild-child child)

        (or (< row 0) (not (us/safe-int? row)))
        (u/display-not-valid :appendChild-row row)

        (or (< column 0) (not (us/safe-int? column)))
        (u/display-not-valid :appendChild-column column)

        (not (r/check-permission plugin-id "content:write"))
        (u/display-not-valid :appendChild "Plugin doesn't have 'content:write' permission")

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
             (not (us/safe-int? value))
             (u/display-not-valid :row-value value)

             (nil? cell)
             (u/display-not-valid :row-cell "cell not found")

             (not (r/check-permission plugin-id "content:write"))
             (u/display-not-valid :row "Plugin doesn't have 'content:write' permission")

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
             (not (us/safe-int? value))
             (u/display-not-valid :rowSpan-value value)

             (nil? cell)
             (u/display-not-valid :rowSpan-cell "cell not found")

             (not (r/check-permission plugin-id "content:write"))
             (u/display-not-valid :rowSpan "Plugin doesn't have 'content:write' permission")

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
             (not (us/safe-int? value))
             (u/display-not-valid :column-value value)

             (nil? cell)
             (u/display-not-valid :column-cell "cell not found")

             (not (r/check-permission plugin-id "content:write"))
             (u/display-not-valid :column "Plugin doesn't have 'content:write' permission")

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
             (not (us/safe-int? value))
             (u/display-not-valid :columnSpan-value value)

             (nil? cell)
             (u/display-not-valid :columnSpan-cell "cell not found")

             (not (r/check-permission plugin-id "content:write"))
             (u/display-not-valid :columnSpan "Plugin doesn't have 'content:write' permission")

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
             (u/display-not-valid :areaName-value value)

             (nil? cell)
             (u/display-not-valid :areaName-cell "cell not found")

             (not (r/check-permission plugin-id "content:write"))
             (u/display-not-valid :areaName "Plugin doesn't have 'content:write' permission")

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
             (u/display-not-valid :position-value value)

             (nil? cell)
             (u/display-not-valid :position-cell "cell not found")

             (not (r/check-permission plugin-id "content:write"))
             (u/display-not-valid :position "Plugin doesn't have 'content:write' permission")

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
             (u/display-not-valid :alignSelf-value value)

             (nil? cell)
             (u/display-not-valid :alignSelf-cell "cell not found")

             (not (r/check-permission plugin-id "content:write"))
             (u/display-not-valid :alignSelf "Plugin doesn't have 'content:write' permission")

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
             (u/display-not-valid :justifySelf-value value)

             (nil? cell)
             (u/display-not-valid :justifySelf-cell "cell not found")

             (not (r/check-permission plugin-id "content:write"))
             (u/display-not-valid :justifySelf "Plugin doesn't have 'content:write' permission")

             :else
             (st/emit! (dwsl/update-grid-cells (:parent-id shape) #{(:id cell)} {:justify-self value})))))})))
