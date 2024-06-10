;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC

(ns app.plugins.flex
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

(deftype FlexLayout [$plugin $file $page $id]
  Object
  (remove
    [_]
    (st/emit! (dwsl/remove-layout #{$id})))

  (appendChild
    [_ child]
    (let [child-id  (obj/get child "$id")]
      (st/emit! (dwt/move-shapes-to-frame #{child-id} $id nil nil)
                (ptk/data-event :layout/update {:ids [$id]})))))

(defn flex-layout-proxy
  [plugin-id file-id page-id id]
  (-> (FlexLayout. plugin-id file-id page-id id)
      (crc/add-properties!
       {:name "$plugin" :enumerable false :get (constantly plugin-id)}
       {:name "$id" :enumerable false :get (constantly id)}
       {:name "$file" :enumerable false :get (constantly file-id)}
       {:name "$page" :enumerable false :get (constantly page-id)}

       {:name "dir"
        :get #(-> % proxy->shape :layout-flex-dir d/name)
        :set
        (fn [self value]
          (let [id (obj/get self "$id")
                value (keyword value)]
            (when (contains? ctl/flex-direction-types value)
              (st/emit! (dwsl/update-layout #{id} {:layout-flex-dir value})))))}

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


(deftype LayoutChildProxy [$plugin $file $page $id])

(defn layout-child-proxy
  [plugin-id file-id page-id id]
  (-> (LayoutChildProxy. plugin-id file-id page-id id)
      (crc/add-properties!
       {:name "$plugin" :enumerable false :get (constantly plugin-id)}
       {:name "$id" :enumerable false :get (constantly id)}
       {:name "$file" :enumerable false :get (constantly file-id)}
       {:name "$page" :enumerable false :get (constantly page-id)}

       {:name "absolute"
        :get #(-> % proxy->shape :layout-item-absolute boolean)
        :set
        (fn [self value]
          (let [id (obj/get self "$id")]
            (when (boolean? value)
              (st/emit! (dwsl/update-layout #{id} {:layout-item-absolute value})))))}

       {:name "zIndex"
        :get #(-> % proxy->shape :layout-item-z-index (d/nilv 0))
        :set
        (fn [self value]
          (let [id (obj/get self "$id")]
            (when (us/safe-int? value)
              (st/emit! (dwsl/update-layout-child #{id} {:layout-item-z-index value})))))}

       {:name "horizontalSizing"
        :get #(-> % proxy->shape :layout-item-h-sizing (d/nilv :fix) d/name)
        :set
        (fn [self value]
          (let [id (obj/get self "$id")
                value (keyword value)]
            (when (contains? ctl/item-h-sizing-types value)
              (st/emit! (dwsl/update-layout-child #{id} {:layout-item-h-sizing value})))))}

       {:name "verticalSizing"
        :get #(-> % proxy->shape :layout-item-v-sizing (d/nilv :fix) d/name)
        :set
        (fn [self value]
          (let [id (obj/get self "$id")
                value (keyword value)]
            (when (contains? ctl/item-v-sizing-types value)
              (st/emit! (dwsl/update-layout-child #{id} {:layout-item-v-sizing value})))))}

       {:name "alignSelf"
        :get #(-> % proxy->shape :layout-item-align-self (d/nilv :auto) d/name)
        :set
        (fn [self value]
          (let [id (obj/get self "$id")
                value (keyword value)]
            (when (contains? ctl/item-align-self-types value)
              (st/emit! (dwsl/update-layout-child #{id} {:layout-item-align-self value})))))}

       {:name "verticalMargin"
        :get #(-> % proxy->shape :layout-item-margin :m1 (d/nilv 0))
        :set
        (fn [self value]
          (let [id (obj/get self "$id")]
            (when (us/safe-number? value)
              (st/emit! (dwsl/update-layout-child #{id} {:layout-item-margin {:m1 value :m3 value}})))))}

       {:name "horizontalMargin"
        :get #(-> % proxy->shape :layout-item-margin :m2 (d/nilv 0))
        :set
        (fn [self value]
          (let [id (obj/get self "$id")]
            (when (us/safe-number? value)
              (st/emit! (dwsl/update-layout-child #{id} {:layout-item-margin {:m2 value :m4 value}})))))}

       {:name "topMargin"
        :get #(-> % proxy->shape :layout-item-margin :m1 (d/nilv 0))
        :set
        (fn [self value]
          (let [id (obj/get self "$id")]
            (when (us/safe-number? value)
              (st/emit! (dwsl/update-layout-child #{id} {:layout-item-margin {:m1 value}})))))}

       {:name "rightMargin"
        :get #(-> % proxy->shape :layout-item-margin :m2 (d/nilv 0))
        :set
        (fn [self value]
          (let [id (obj/get self "$id")]
            (when (us/safe-number? value)
              (st/emit! (dwsl/update-layout-child #{id} {:layout-item-margin {:m2 value}})))))}

       {:name "bottomMargin"
        :get #(-> % proxy->shape :layout-item-margin :m3 (d/nilv 0))
        :set
        (fn [self value]
          (let [id (obj/get self "$id")]
            (when (us/safe-number? value)
              (st/emit! (dwsl/update-layout-child #{id} {:layout-item-margin {:m3 value}})))))}

       {:name "leftMargin"
        :get #(-> % proxy->shape :layout-item-margin :m4 (d/nilv 0))
        :set
        (fn [self value]
          (let [id (obj/get self "$id")]
            (when (us/safe-number? value)
              (st/emit! (dwsl/update-layout-child #{id} {:layout-item-margin {:m4 value}})))))}

       {:name "maxWidth"
        :get #(-> % proxy->shape :layout-item-max-w)
        :set
        (fn [self value]
          (let [id (obj/get self "$id")]
            (when (us/safe-number? value)
              (st/emit! (dwsl/update-layout-child #{id} {:layout-item-max-w value})))))}

       {:name "minWidth"
        :get #(-> % proxy->shape :layout-item-min-w)
        :set
        (fn [self value]
          (let [id (obj/get self "$id")]
            (when (us/safe-number? value)
              (st/emit! (dwsl/update-layout-child #{id} {:layout-item-min-w value})))))}

       {:name "maxHeight"
        :get #(-> % proxy->shape :layout-item-max-h)
        :set
        (fn [self value]
          (let [id (obj/get self "$id")]
            (when (us/safe-number? value)
              (st/emit! (dwsl/update-layout-child #{id} {:layout-item-max-h value})))))}

       {:name "minHeight"
        :get #(-> % proxy->shape :layout-item-min-h)
        :set
        (fn [self value]
          (let [id (obj/get self "$id")]
            (when (us/safe-number? value)
              (st/emit! (dwsl/update-layout-child #{id} {:layout-item-min-h value})))))})))
