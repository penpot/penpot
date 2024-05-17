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
   [app.plugins.utils :as utils :refer [proxy->shape]]
   [app.util.object :as obj]
   [potok.v2.core :as ptk]))

(defn- make-tracks
  [tracks]
  (.freeze
   js/Object
   (apply array (->> tracks (map utils/to-js)))))

(deftype GridLayout [$file $page $id]
  Object

  (addRow
    [_ type value]
    (let [type (keyword type)]
      (st/emit! (dwsl/add-layout-track #{$id} :row {:type type :value value}))))

  (addRowAtIndex
    [_ index type value]
    (let [type (keyword type)]
      (st/emit! (dwsl/add-layout-track #{$id} :row {:type type :value value} index))))

  (addColumn
    [_ type value]
    (let [type (keyword type)]
      (st/emit! (dwsl/add-layout-track #{$id} :column {:type type :value value}))))

  (addColumnAtIndex
    [_ index type value]
    (let [type (keyword type)]
      (st/emit! (dwsl/add-layout-track #{$id} :column {:type type :value value} index))))

  (removeRow
    [_ index]
    (st/emit! (dwsl/remove-layout-track #{$id} :row index)))

  (removeColumn
    [_ index]
    (st/emit! (dwsl/remove-layout-track #{$id} :column index)))

  (setColumn
    [_ index type value]
    (let [type (keyword type)]
      (st/emit! (dwsl/change-layout-track #{$id} :column index (d/without-nils {:type type :value value})))))

  (setRow
    [_ index type value]
    (let [type (keyword type)]
      (st/emit! (dwsl/change-layout-track #{$id} :row index (d/without-nils {:type type :value value})))))

  (remove
    [_]
    (st/emit! (dwsl/remove-layout #{$id})))

  (appendChild
    [_ child row column]
    (let [child-id  (obj/get child "$id")]
      (st/emit! (dwt/move-shapes-to-frame #{child-id} $id nil [row column])
                (ptk/data-event :layout/update {:ids [$id]})))))

(defn grid-layout-proxy
  [file-id page-id id]
  (-> (GridLayout. file-id page-id id)
      (crc/add-properties!
       {:name "$id" :enumerable false}
       {:name "$file" :enumerable false}
       {:name "$page" :enumerable false}
       {:name "dir"
        :get #(-> % proxy->shape :layout-grid-dir d/name)
        :set
        (fn [self value]
          (let [id (obj/get self "$id")
                value (keyword value)]
            (when (contains? ctl/grid-direction-types value)
              (st/emit! (dwsl/update-layout #{id} {:layout-grid-dir value})))))}

       {:name "rows"
        :get #(-> % proxy->shape :layout-grid-rows make-tracks)}

       {:name "columns"
        :get #(-> % proxy->shape :layout-grid-columns make-tracks)}

       {:name "alignItems"
        :get #(-> % proxy->shape :layout-align-items d/name)
        :set
        (fn [self value]
          (let [id (obj/get self "$id")
                value (keyword value)]
            (when (contains? ctl/align-items-types value)
              (st/emit! (dwsl/update-layout #{id} {:layout-align-items value})))))}

       {:name "alignContent"
        :get #(-> % proxy->shape :layout-align-content d/name)
        :set
        (fn [self value]
          (let [id (obj/get self "$id")
                value (keyword value)]
            (when (contains? ctl/align-content-types value)
              (st/emit! (dwsl/update-layout #{id} {:layout-align-content value})))))}

       {:name "justifyItems"
        :get #(-> % proxy->shape :layout-justify-items d/name)
        :set
        (fn [self value]
          (let [id (obj/get self "$id")
                value (keyword value)]
            (when (contains? ctl/justify-items-types value)
              (st/emit! (dwsl/update-layout #{id} {:layout-justify-items value})))))}

       {:name "justifyContent"
        :get #(-> % proxy->shape :layout-justify-content d/name)
        :set
        (fn [self value]
          (let [id (obj/get self "$id")
                value (keyword value)]
            (when (contains? ctl/justify-content-types value)
              (st/emit! (dwsl/update-layout #{id} {:layout-justify-content value})))))}

       {:name "rowGap"
        :get #(-> % proxy->shape :layout-gap :row-gap)
        :set
        (fn [self value]
          (let [id (obj/get self "$id")]
            (when (us/safe-int? value)
              (st/emit! (dwsl/update-layout #{id} {:layout-gap {:row-gap value}})))))}

       {:name "columnGap"
        :get #(-> % proxy->shape :layout-gap :column-gap)
        :set
        (fn [self value]
          (let [id (obj/get self "$id")]
            (when (us/safe-int? value)
              (st/emit! (dwsl/update-layout #{id} {:layout-gap {:column-gap value}})))))}

       {:name "verticalPadding"
        :get #(-> % proxy->shape :layout-padding :p1)
        :set
        (fn [self value]
          (let [id (obj/get self "$id")]
            (when (us/safe-int? value)
              (st/emit! (dwsl/update-layout #{id} {:layout-padding {:p1 value :p3 value}})))))}

       {:name "horizontalPadding"
        :get #(-> % proxy->shape :layout-padding :p2)
        :set
        (fn [self value]
          (let [id (obj/get self "$id")]
            (when (us/safe-int? value)
              (st/emit! (dwsl/update-layout #{id} {:layout-padding {:p2 value :p4 value}})))))}


       {:name "topPadding"
        :get #(-> % proxy->shape :layout-padding :p1)
        :set
        (fn [self value]
          (let [id (obj/get self "$id")]
            (when (us/safe-int? value)
              (st/emit! (dwsl/update-layout #{id} {:layout-padding {:p1 value}})))))}

       {:name "rightPadding"
        :get #(-> % proxy->shape :layout-padding :p2)
        :set
        (fn [self value]
          (let [id (obj/get self "$id")]
            (when (us/safe-int? value)
              (st/emit! (dwsl/update-layout #{id} {:layout-padding {:p2 value}})))))}

       {:name "bottomPadding"
        :get #(-> % proxy->shape :layout-padding :p3)
        :set
        (fn [self value]
          (let [id (obj/get self "$id")]
            (when (us/safe-int? value)
              (st/emit! (dwsl/update-layout #{id} {:layout-padding {:p3 value}})))))}

       {:name "leftPadding"
        :get #(-> % proxy->shape :layout-padding :p4)
        :set
        (fn [self value]
          (let [id (obj/get self "$id")]
            (when (us/safe-int? value)
              (st/emit! (dwsl/update-layout #{id} {:layout-padding {:p4 value}})))))})))
