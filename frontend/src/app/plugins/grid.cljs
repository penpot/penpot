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
   [app.common.uuid :as uuid]
   [app.main.data.workspace.shape-layout :as dwsl]
   [app.main.data.workspace.transforms :as dwt]
   [app.main.store :as st]
   [app.plugins.utils :as utils :refer [get-data get-state]]
   [app.util.object :as obj]
   [potok.v2.core :as ptk]))

(defn- make-tracks
  [tracks]
  (.freeze
   js/Object
   (apply array (->> tracks (map utils/to-js)))))

(deftype GridLayout [_data]
  Object

  (addRow
    [self type value]
    (let [id (get-data self :id)
          type (keyword type)]
      (st/emit! (dwsl/add-layout-track #{id} :row {:type type :value value}))))

  (addRowAtIndex
    [self type value index]
    (let [id (get-data self :id)
          type (keyword type)]
      (st/emit! (dwsl/add-layout-track #{id} :row {:type type :value value} index))))

  (addColumn
    [self type value]
    (let [id (get-data self :id)
          type (keyword type)]
      (st/emit! (dwsl/add-layout-track #{id} :column {:type type :value value}))))

  (addColumnAtIndex
    [self type value index]
    (let [id (get-data self :id)
          type (keyword type)]
      (st/emit! (dwsl/add-layout-track #{id} :column {:type type :value value} index))))

  (removeRow
    [self index]
    (let [id (get-data self :id)]
      (st/emit! (dwsl/remove-layout-track #{id} :row index))))

  (removeColumn
    [self index]
    (let [id (get-data self :id)]
      (st/emit! (dwsl/remove-layout-track #{id} :column index))))

  (setColumn
    [self index type value]
    (let [id (get-data self :id)
          type (keyword type)]
      (st/emit! (dwsl/change-layout-track #{id} :column index (d/without-nils {:type type :value value})))))

  (setRow
    [self index type value]
    (let [id (get-data self :id)
          type (keyword type)]
      (st/emit! (dwsl/change-layout-track #{id} :row index (d/without-nils {:type type :value value})))))

  (remove
    [self]
    (let [id (get-data self :id)]
      (st/emit! (dwsl/remove-layout #{id}))))

  (appendChild
    [self child row column]
    (let [parent-id (get-data self :id)
          child-id (uuid/uuid (obj/get child "id"))]
      (st/emit! (dwt/move-shapes-to-frame #{child-id} parent-id nil [row column])
                (ptk/data-event :layout/update {:ids [parent-id]})))))

(defn grid-layout-proxy
  [data]
  (-> (GridLayout. data)
      (crc/add-properties!
       {:name "dir"
        :get #(get-state % :layout-grid-dir d/name)
        :set
        (fn [self value]
          (let [id (get-data self :id)
                value (keyword value)]
            (when (contains? ctl/grid-direction-types value)
              (st/emit! (dwsl/update-layout #{id} {:layout-grid-dir value})))))}

       {:name "rows"
        :get #(get-state % :layout-grid-rows make-tracks)}

       {:name "columns"
        :get #(get-state % :layout-grid-columns make-tracks)}

       {:name "alignItems"
        :get #(get-state % :layout-align-items d/name)
        :set
        (fn [self value]
          (let [id (get-data self :id)
                value (keyword value)]
            (when (contains? ctl/align-items-types value)
              (st/emit! (dwsl/update-layout #{id} {:layout-align-items value})))))}

       {:name "alignContent"
        :get #(get-state % :layout-align-content d/name)
        :set
        (fn [self value]
          (let [id (get-data self :id)
                value (keyword value)]
            (when (contains? ctl/align-content-types value)
              (st/emit! (dwsl/update-layout #{id} {:layout-align-content value})))))}

       {:name "justifyItems"
        :get #(get-state % :layout-justify-items d/name)
        :set
        (fn [self value]
          (let [id (get-data self :id)
                value (keyword value)]
            (when (contains? ctl/justify-items-types value)
              (st/emit! (dwsl/update-layout #{id} {:layout-justify-items value})))))}

       {:name "justifyContent"
        :get #(get-state % :layout-justify-content d/name)
        :set
        (fn [self value]
          (let [id (get-data self :id)
                value (keyword value)]
            (when (contains? ctl/justify-content-types value)
              (st/emit! (dwsl/update-layout #{id} {:layout-justify-content value})))))}

       {:name "rowGap"
        :get #(:row-gap (get-state % :layout-gap))
        :set
        (fn [self value]
          (let [id (get-data self :id)]
            (when (us/safe-int? value)
              (st/emit! (dwsl/update-layout #{id} {:layout-gap {:row-gap value}})))))}

       {:name "columnGap"
        :get #(:column-gap (get-state % :layout-gap))
        :set
        (fn [self value]
          (let [id (get-data self :id)]
            (when (us/safe-int? value)
              (st/emit! (dwsl/update-layout #{id} {:layout-gap {:column-gap value}})))))}

       {:name "verticalPadding"
        :get #(:p1 (get-state % :layout-padding))
        :set
        (fn [self value]
          (let [id (get-data self :id)]
            (when (us/safe-int? value)
              (st/emit! (dwsl/update-layout #{id} {:layout-padding {:p1 value :p3 value}})))))}

       {:name "horizontalPadding"
        :get #(:p2 (get-state % :layout-padding))
        :set
        (fn [self value]
          (let [id (get-data self :id)]
            (when (us/safe-int? value)
              (st/emit! (dwsl/update-layout #{id} {:layout-padding {:p2 value :p4 value}})))))})))
