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
   [app.plugins.utils :as u]
   [app.util.object :as obj]
   [potok.v2.core :as ptk]))

;; Define in `app.plugins.shape` we do this way to prevent circular dependency
(def shape-proxy? nil)

(deftype FlexLayout [$plugin $file $page $id]
  Object
  (remove
    [_]
    (st/emit! (dwsl/remove-layout #{$id})))

  (appendChild
    [_ child]
    (cond
      (not (shape-proxy? child))
      (u/display-not-valid :appendChild child)

      :else
      (let [child-id  (obj/get child "$id")]
        (st/emit! (dwt/move-shapes-to-frame #{child-id} $id nil nil)
                  (ptk/data-event :layout/update {:ids [$id]}))))))

(defn flex-layout-proxy? [p]
  (instance? FlexLayout p))

(defn flex-layout-proxy
  [plugin-id file-id page-id id]
  (-> (FlexLayout. plugin-id file-id page-id id)
      (crc/add-properties!
       {:name "$plugin" :enumerable false :get (constantly plugin-id)}
       {:name "$id" :enumerable false :get (constantly id)}
       {:name "$file" :enumerable false :get (constantly file-id)}
       {:name "$page" :enumerable false :get (constantly page-id)}

       {:name "dir"
        :get #(-> % u/proxy->shape :layout-flex-dir d/name)
        :set
        (fn [self value]
          (let [value (keyword value)]
            (cond
              (not (contains? ctl/flex-direction-types value))
              (u/display-not-valid :dir value)

              :else
              (let [id (obj/get self "$id")]
                (st/emit! (dwsl/update-layout #{id} {:layout-flex-dir value}))))))}

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


(deftype LayoutChildProxy [$plugin $file $page $id])

(defn layout-child-proxy? [p]
  (instance? LayoutChildProxy p))

(defn layout-child-proxy
  [plugin-id file-id page-id id]
  (-> (LayoutChildProxy. plugin-id file-id page-id id)
      (crc/add-properties!
       {:name "$plugin" :enumerable false :get (constantly plugin-id)}
       {:name "$id" :enumerable false :get (constantly id)}
       {:name "$file" :enumerable false :get (constantly file-id)}
       {:name "$page" :enumerable false :get (constantly page-id)}

       {:name "absolute"
        :get #(-> % u/proxy->shape :layout-item-absolute boolean)
        :set
        (fn [self value]
          (cond
            (not (boolean? value))
            (u/display-not-valid :absolute value)

            :else
            (let [id (obj/get self "$id")]
              (st/emit! (dwsl/update-layout #{id} {:layout-item-absolute value})))))}

       {:name "zIndex"
        :get #(-> % u/proxy->shape :layout-item-z-index (d/nilv 0))
        :set
        (fn [self value]
          (cond
            (us/safe-int? value)
            (u/display-not-valid :zIndex value)

            :else
            (let [id (obj/get self "$id")]
              (st/emit! (dwsl/update-layout-child #{id} {:layout-item-z-index value})))))}

       {:name "horizontalSizing"
        :get #(-> % u/proxy->shape :layout-item-h-sizing (d/nilv :fix) d/name)
        :set
        (fn [self value]
          (let [value (keyword value)]
            (cond
              (not (contains? ctl/item-h-sizing-types value))
              (u/display-not-valid :horizontalPadding value)

              :else
              (let [id (obj/get self "$id")]
                (st/emit! (dwsl/update-layout-child #{id} {:layout-item-h-sizing value}))))))}

       {:name "verticalSizing"
        :get #(-> % u/proxy->shape :layout-item-v-sizing (d/nilv :fix) d/name)
        :set
        (fn [self value]
          (let [value (keyword value)]
            (cond
              (not (contains? ctl/item-v-sizing-types value))
              (u/display-not-valid :verticalSizing value)

              :else
              (let [id (obj/get self "$id")]
                (st/emit! (dwsl/update-layout-child #{id} {:layout-item-v-sizing value}))))))}

       {:name "alignSelf"
        :get #(-> % u/proxy->shape :layout-item-align-self (d/nilv :auto) d/name)
        :set
        (fn [self value]
          (let [value (keyword value)]
            (cond
              (not (contains? ctl/item-align-self-types value))
              (u/display-not-valid :alignSelf value)

              :else
              (let [id (obj/get self "$id")]
                (st/emit! (dwsl/update-layout-child #{id} {:layout-item-align-self value}))))))}

       {:name "verticalMargin"
        :get #(-> % u/proxy->shape :layout-item-margin :m1 (d/nilv 0))
        :set
        (fn [self value]
          (cond
            (not (us/safe-number? value))
            (u/display-not-valid :verticalMargin value)

            :else
            (let [id (obj/get self "$id")]
              (st/emit! (dwsl/update-layout-child #{id} {:layout-item-margin {:m1 value :m3 value}})))))}

       {:name "horizontalMargin"
        :get #(-> % u/proxy->shape :layout-item-margin :m2 (d/nilv 0))
        :set
        (fn [self value]
          (cond
            (not (us/safe-number? value))
            (u/display-not-valid :horizontalMargin value)

            :else
            (let [id (obj/get self "$id")]
              (st/emit! (dwsl/update-layout-child #{id} {:layout-item-margin {:m2 value :m4 value}})))))}

       {:name "topMargin"
        :get #(-> % u/proxy->shape :layout-item-margin :m1 (d/nilv 0))
        :set
        (fn [self value]
          (cond
            (not (us/safe-number? value))
            (u/display-not-valid :topMargin value)

            :else
            (let [id (obj/get self "$id")]
              (st/emit! (dwsl/update-layout-child #{id} {:layout-item-margin {:m1 value}})))))}

       {:name "rightMargin"
        :get #(-> % u/proxy->shape :layout-item-margin :m2 (d/nilv 0))
        :set
        (fn [self value]
          (cond
            (not (us/safe-number? value))
            (u/display-not-valid :rightMargin value)

            :else
            (let [id (obj/get self "$id")]
              (st/emit! (dwsl/update-layout-child #{id} {:layout-item-margin {:m2 value}})))))}

       {:name "bottomMargin"
        :get #(-> % u/proxy->shape :layout-item-margin :m3 (d/nilv 0))
        :set
        (fn [self value]
          (cond
            (not (us/safe-number? value))
            (u/display-not-valid :bottomMargin value)

            :else
            (let [id (obj/get self "$id")]
              (st/emit! (dwsl/update-layout-child #{id} {:layout-item-margin {:m3 value}})))))}

       {:name "leftMargin"
        :get #(-> % u/proxy->shape :layout-item-margin :m4 (d/nilv 0))
        :set
        (fn [self value]
          (cond
            (not (us/safe-number? value))
            (u/display-not-valid :leftMargin value)

            :else
            (let [id (obj/get self "$id")]
              (st/emit! (dwsl/update-layout-child #{id} {:layout-item-margin {:m4 value}})))))}

       {:name "maxWidth"
        :get #(-> % u/proxy->shape :layout-item-max-w)
        :set
        (fn [self value]
          (cond
            (not (us/safe-number? value))
            (u/display-not-valid :maxWidth value)

            :else
            (let [id (obj/get self "$id")]
              (st/emit! (dwsl/update-layout-child #{id} {:layout-item-max-w value})))))}

       {:name "minWidth"
        :get #(-> % u/proxy->shape :layout-item-min-w)
        :set
        (fn [self value]
          (cond
            (not (us/safe-number? value))
            (u/display-not-valid :minWidth value)

            :else
            (let [id (obj/get self "$id")]
              (st/emit! (dwsl/update-layout-child #{id} {:layout-item-min-w value})))))}

       {:name "maxHeight"
        :get #(-> % u/proxy->shape :layout-item-max-h)
        :set
        (fn [self value]
          (cond
            (not (us/safe-number? value))
            (u/display-not-valid :maxHeight value)

            :else
            (let [id (obj/get self "$id")]
              (st/emit! (dwsl/update-layout-child #{id} {:layout-item-max-h value})))))}

       {:name "minHeight"
        :get #(-> % u/proxy->shape :layout-item-min-h)
        :set
        (fn [self value]
          (cond
            (not (us/safe-number? value))
            (u/display-not-valid :minHeight value)

            :else
            (let [id (obj/get self "$id")]
              (st/emit! (dwsl/update-layout-child #{id} {:layout-item-min-h value})))))})))
