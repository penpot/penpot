;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC

(ns app.plugins.flex
  (:require
   [app.common.data :as d]
   [app.common.spec :as us]
   [app.common.types.shape.layout :as ctl]
   [app.main.data.workspace.shape-layout :as dwsl]
   [app.main.data.workspace.transforms :as dwt]
   [app.main.store :as st]
   [app.plugins.register :as r]
   [app.plugins.utils :as u]
   [app.util.object :as obj]
   [potok.v2.core :as ptk]))

;; Define in `app.plugins.shape` we do this way to prevent circular dependency
(def shape-proxy? nil)

(defn flex-layout-proxy? [p]
  (obj/type-of? p "FlexLayoutProxy"))

(defn flex-layout-proxy
  [plugin-id file-id page-id id]
  (obj/reify {:name "FlexLayoutProxy"}
    :$plugin {:enumerable false :get (fn [] plugin-id)}
    :$id {:enumerable false :get (fn [] id)}
    :$file {:enumerable false :get (fn [] file-id)}
    :$page {:enumerable false :get (fn [] page-id)}

    :dir
    {:this true
     :get #(-> % u/proxy->shape :layout-flex-dir d/name)
     :set
     (fn [_ value]
       (let [value (keyword value)]
         (cond
           (not (contains? ctl/flex-direction-types value))
           (u/display-not-valid :dir value)

           (not (r/check-permission plugin-id "content:write"))
           (u/display-not-valid :dir "Plugin doesn't have 'content:write' permission")

           :else
           (st/emit! (dwsl/update-layout #{id} {:layout-flex-dir value})))))}

    :wrap
    {:this true
     :get #(-> % u/proxy->shape :layout-wrap-type d/name)
     :set
     (fn [_ value]
       (let [value (keyword value)]
         (cond
           (not (contains? ctl/wrap-types value))
           (u/display-not-valid :wrap value)

           (not (r/check-permission plugin-id "content:write"))
           (u/display-not-valid :wrap "Plugin doesn't have 'content:write' permission")

           :else
           (st/emit! (dwsl/update-layout #{id} {:layout-wrap-type value})))))}

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
         (u/display-not-valid :rightPadding "Plugin doesn't have 'content:write' permission")

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

    :remove
    (fn []
      (st/emit! (dwsl/remove-layout #{id})))

    :appendChild
    (fn [child]
      (cond
        (not (shape-proxy? child))
        (u/display-not-valid :appendChild child)

        :else
        (let [child-id  (obj/get child "$id")]
          (st/emit! (dwt/move-shapes-to-frame #{child-id} id nil nil)
                    (ptk/data-event :layout/update {:ids [id]})))))))


(defn layout-child-proxy? [p]
  (obj/type-of? p "LayoutChildProxy"))

(defn layout-child-proxy
  [plugin-id file-id page-id id]
  (obj/reify {:name "LayoutChildProxy"}
    :$plugin {:enumerable false :get (fn [] plugin-id)}
    :$id {:enumerable false :get (fn [] id)}
    :$file {:enumerable false :get (fn [] file-id)}
    :$page {:enumerable false :get (fn [] page-id)}

    :absolute
    {:this true
     :get #(-> % u/proxy->shape :layout-item-absolute boolean)
     :set
     (fn [_ value]
       (cond
         (not (boolean? value))
         (u/display-not-valid :absolute value)

         (not (r/check-permission plugin-id "content:write"))
         (u/display-not-valid :absolute "Plugin doesn't have 'content:write' permission")

         :else
         (st/emit! (dwsl/update-layout #{id} {:layout-item-absolute value}))))}

    :zIndex
    {:this true
     :get #(-> % u/proxy->shape :layout-item-z-index (d/nilv 0))
     :set
     (fn [_ value]
       (cond
         (us/safe-int? value)
         (u/display-not-valid :zIndex value)

         (not (r/check-permission plugin-id "content:write"))
         (u/display-not-valid :zIndex "Plugin doesn't have 'content:write' permission")

         :else
         (st/emit! (dwsl/update-layout-child #{id} {:layout-item-z-index value}))))}

    :horizontalSizing
    {:this true
     :get #(-> % u/proxy->shape :layout-item-h-sizing (d/nilv :fix) d/name)
     :set
     (fn [_ value]
       (let [value (keyword value)]
         (cond
           (not (contains? ctl/item-h-sizing-types value))
           (u/display-not-valid :horizontalPadding value)

           (not (r/check-permission plugin-id "content:write"))
           (u/display-not-valid :horizontalPadding "Plugin doesn't have 'content:write' permission")

           :else
           (st/emit! (dwsl/update-layout-child #{id} {:layout-item-h-sizing value})))))}

    :verticalSizing
    {:this true
     :get #(-> % u/proxy->shape :layout-item-v-sizing (d/nilv :fix) d/name)
     :set
     (fn [_ value]
       (let [value (keyword value)]
         (cond
           (not (contains? ctl/item-v-sizing-types value))
           (u/display-not-valid :verticalSizing value)

           (not (r/check-permission plugin-id "content:write"))
           (u/display-not-valid :verticalSizing "Plugin doesn't have 'content:write' permission")

           :else
           (st/emit! (dwsl/update-layout-child #{id} {:layout-item-v-sizing value})))))}

    :alignSelf
    {:this true
     :get #(-> % u/proxy->shape :layout-item-align-self (d/nilv :auto) d/name)
     :set
     (fn [_ value]
       (let [value (keyword value)]
         (cond
           (not (contains? ctl/item-align-self-types value))
           (u/display-not-valid :alignSelf value)

           (not (r/check-permission plugin-id "content:write"))
           (u/display-not-valid :alignSelf "Plugin doesn't have 'content:write' permission")

           :else
           (st/emit! (dwsl/update-layout-child #{id} {:layout-item-align-self value})))))}

    :verticalMargin
    {:this true
     :get #(-> % u/proxy->shape :layout-item-margin :m1 (d/nilv 0))
     :set
     (fn [_ value]
       (cond
         (not (us/safe-number? value))
         (u/display-not-valid :verticalMargin value)

         (not (r/check-permission plugin-id "content:write"))
         (u/display-not-valid :verticalMargin "Plugin doesn't have 'content:write' permission")

         :else
         (st/emit! (dwsl/update-layout-child #{id} {:layout-item-margin {:m1 value :m3 value}}))))}

    :horizontalMargin
    {:this true
     :get #(-> % u/proxy->shape :layout-item-margin :m2 (d/nilv 0))
     :set
     (fn [_ value]
       (cond
         (not (us/safe-number? value))
         (u/display-not-valid :horizontalMargin value)

         (not (r/check-permission plugin-id "content:write"))
         (u/display-not-valid :horizontalMargin "Plugin doesn't have 'content:write' permission")

         :else
         (st/emit! (dwsl/update-layout-child #{id} {:layout-item-margin {:m2 value :m4 value}}))))}

    :topMargin
    {:this true
     :get #(-> % u/proxy->shape :layout-item-margin :m1 (d/nilv 0))
     :set
     (fn [_ value]
       (cond
         (not (us/safe-number? value))
         (u/display-not-valid :topMargin value)

         (not (r/check-permission plugin-id "content:write"))
         (u/display-not-valid :topMargin "Plugin doesn't have 'content:write' permission")

         :else
         (st/emit! (dwsl/update-layout-child #{id} {:layout-item-margin {:m1 value}}))))}

    :rightMargin
    {:this true
     :get #(-> % u/proxy->shape :layout-item-margin :m2 (d/nilv 0))
     :set
     (fn [_ value]
       (cond
         (not (us/safe-number? value))
         (u/display-not-valid :rightMargin value)

         (not (r/check-permission plugin-id "content:write"))
         (u/display-not-valid :rightMargin "Plugin doesn't have 'content:write' permission")

         :else
         (st/emit! (dwsl/update-layout-child #{id} {:layout-item-margin {:m2 value}}))))}

    :bottomMargin
    {:this true
     :get #(-> % u/proxy->shape :layout-item-margin :m3 (d/nilv 0))
     :set
     (fn [_ value]
       (cond
         (not (us/safe-number? value))
         (u/display-not-valid :bottomMargin value)

         (not (r/check-permission plugin-id "content:write"))
         (u/display-not-valid :bottomMargin "Plugin doesn't have 'content:write' permission")

         :else
         (st/emit! (dwsl/update-layout-child #{id} {:layout-item-margin {:m3 value}}))))}

    :leftMargin
    {:this true
     :get #(-> % u/proxy->shape :layout-item-margin :m4 (d/nilv 0))
     :set
     (fn [_ value]
       (cond
         (not (us/safe-number? value))
         (u/display-not-valid :leftMargin value)

         (not (r/check-permission plugin-id "content:write"))
         (u/display-not-valid :leftMargin "Plugin doesn't have 'content:write' permission")

         :else
         (st/emit! (dwsl/update-layout-child #{id} {:layout-item-margin {:m4 value}}))))}

    :maxWidth
    {:this true
     :get #(-> % u/proxy->shape :layout-item-max-w)
     :set
     (fn [_ value]
       (cond
         (not (us/safe-number? value))
         (u/display-not-valid :maxWidth value)

         (not (r/check-permission plugin-id "content:write"))
         (u/display-not-valid :maxWidth "Plugin doesn't have 'content:write' permission")

         :else
         (st/emit! (dwsl/update-layout-child #{id} {:layout-item-max-w value}))))}

    :minWidth
    {:this true
     :get #(-> % u/proxy->shape :layout-item-min-w)
     :set
     (fn [_ value]
       (cond
         (not (us/safe-number? value))
         (u/display-not-valid :minWidth value)

         (not (r/check-permission plugin-id "content:write"))
         (u/display-not-valid :minWidth "Plugin doesn't have 'content:write' permission")

         :else
         (st/emit! (dwsl/update-layout-child #{id} {:layout-item-min-w value}))))}

    :maxHeight
    {:this true
     :get #(-> % u/proxy->shape :layout-item-max-h)
     :set
     (fn [_ value]
       (cond
         (not (us/safe-number? value))
         (u/display-not-valid :maxHeight value)

         (not (r/check-permission plugin-id "content:write"))
         (u/display-not-valid :maxHeight "Plugin doesn't have 'content:write' permission")

         :else
         (st/emit! (dwsl/update-layout-child #{id} {:layout-item-max-h value}))))}

    :minHeight
    {:this true
     :get #(-> % u/proxy->shape :layout-item-min-h)
     :set
     (fn [_ value]
       (cond
         (not (us/safe-number? value))
         (u/display-not-valid :minHeight value)

         (not (r/check-permission plugin-id "content:write"))
         (u/display-not-valid :minHeight "Plugin doesn't have 'content:write' permission")

         :else
         (st/emit! (dwsl/update-layout-child #{id} {:layout-item-min-h value}))))}))
