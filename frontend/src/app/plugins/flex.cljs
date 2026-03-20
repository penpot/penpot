;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC

(ns app.plugins.flex
  (:require
   [app.common.data :as d]
   [app.common.schema :as sm]
   [app.common.types.shape.layout :as ctl]
   [app.main.data.workspace.shape-layout :as dwsl]
   [app.main.data.workspace.shapes :as dwsh]
   [app.main.store :as st]
   [app.plugins.register :as r]
   [app.plugins.utils :as u]
   [app.util.object :as obj]))

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
           (u/not-valid plugin-id :dir value)

           (not (r/check-permission plugin-id "content:write"))
           (u/not-valid plugin-id :dir "Plugin doesn't have 'content:write' permission")

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
           (u/not-valid plugin-id :wrap value)

           (not (r/check-permission plugin-id "content:write"))
           (u/not-valid plugin-id :wrap "Plugin doesn't have 'content:write' permission")

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
         (u/not-valid plugin-id :rightPadding "Plugin doesn't have 'content:write' permission")

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

    :remove
    (fn []
      (st/emit! (dwsl/remove-layout #{id})))

    :appendChild
    (fn [child]
      (cond
        (not (shape-proxy? child))
        (u/not-valid plugin-id :appendChild child)

        :else
        (let [child-id (obj/get child "$id")
              shape (u/locate-shape file-id page-id id)
              index
              (if (and (u/natural-child-ordering? plugin-id) (not (ctl/reverse? shape)))
                0
                (count (:shapes shape)))]
          (st/emit! (dwsh/relocate-shapes #{child-id} id index)))))

    :horizontalSizing
    {:this true
     :get #(-> % u/proxy->shape :layout-item-h-sizing (d/nilv :fix) d/name)
     :set
     (fn [_ value]
       (let [value (keyword value)]
         (cond
           (not (contains? ctl/item-h-sizing-types value))
           (u/not-valid plugin-id :horizontalSizing value)

           (not (r/check-permission plugin-id "content:write"))
           (u/not-valid plugin-id :horizontalSizing "Plugin doesn't have 'content:write' permission")

           :else
           (st/emit! (dwsl/update-layout #{id} {:layout-item-h-sizing value})))))}

    :verticalSizing
    {:this true
     :get #(-> % u/proxy->shape :layout-item-v-sizing (d/nilv :fix) d/name)
     :set
     (fn [_ value]
       (let [value (keyword value)]
         (cond
           (not (contains? ctl/item-v-sizing-types value))
           (u/not-valid plugin-id :verticalSizing value)

           (not (r/check-permission plugin-id "content:write"))
           (u/not-valid plugin-id :verticalSizing "Plugin doesn't have 'content:write' permission")

           :else
           (st/emit! (dwsl/update-layout #{id} {:layout-item-v-sizing value})))))}))

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
         (u/not-valid plugin-id :absolute value)

         (not (r/check-permission plugin-id "content:write"))
         (u/not-valid plugin-id :absolute "Plugin doesn't have 'content:write' permission")

         :else
         (st/emit! (dwsl/update-layout #{id} {:layout-item-absolute value}))))}

    :zIndex
    {:this true
     :get #(-> % u/proxy->shape :layout-item-z-index (d/nilv 0))
     :set
     (fn [_ value]
       (cond
         (sm/valid-safe-int? value)
         (u/not-valid plugin-id :zIndex value)

         (not (r/check-permission plugin-id "content:write"))
         (u/not-valid plugin-id :zIndex "Plugin doesn't have 'content:write' permission")

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
           (u/not-valid plugin-id :horizontalPadding value)

           (not (r/check-permission plugin-id "content:write"))
           (u/not-valid plugin-id :horizontalPadding "Plugin doesn't have 'content:write' permission")

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
           (u/not-valid plugin-id :verticalSizing value)

           (not (r/check-permission plugin-id "content:write"))
           (u/not-valid plugin-id :verticalSizing "Plugin doesn't have 'content:write' permission")

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
           (u/not-valid plugin-id :alignSelf value)

           (not (r/check-permission plugin-id "content:write"))
           (u/not-valid plugin-id :alignSelf "Plugin doesn't have 'content:write' permission")

           :else
           (st/emit! (dwsl/update-layout-child #{id} {:layout-item-align-self value})))))}

    :verticalMargin
    {:this true
     :get #(-> % u/proxy->shape :layout-item-margin :m1 (d/nilv 0))
     :set
     (fn [_ value]
       (cond
         (not (sm/valid-safe-number? value))
         (u/not-valid plugin-id :verticalMargin value)

         (not (r/check-permission plugin-id "content:write"))
         (u/not-valid plugin-id :verticalMargin "Plugin doesn't have 'content:write' permission")

         :else
         (st/emit! (dwsl/update-layout-child #{id} {:layout-item-margin {:m1 value :m3 value}}))))}

    :horizontalMargin
    {:this true
     :get #(-> % u/proxy->shape :layout-item-margin :m2 (d/nilv 0))
     :set
     (fn [_ value]
       (cond
         (not (sm/valid-safe-number? value))
         (u/not-valid plugin-id :horizontalMargin value)

         (not (r/check-permission plugin-id "content:write"))
         (u/not-valid plugin-id :horizontalMargin "Plugin doesn't have 'content:write' permission")

         :else
         (st/emit! (dwsl/update-layout-child #{id} {:layout-item-margin {:m2 value :m4 value}}))))}

    :topMargin
    {:this true
     :get #(-> % u/proxy->shape :layout-item-margin :m1 (d/nilv 0))
     :set
     (fn [_ value]
       (cond
         (not (sm/valid-safe-number? value))
         (u/not-valid plugin-id :topMargin value)

         (not (r/check-permission plugin-id "content:write"))
         (u/not-valid plugin-id :topMargin "Plugin doesn't have 'content:write' permission")

         :else
         (st/emit! (dwsl/update-layout-child #{id} {:layout-item-margin {:m1 value}}))))}

    :rightMargin
    {:this true
     :get #(-> % u/proxy->shape :layout-item-margin :m2 (d/nilv 0))
     :set
     (fn [_ value]
       (cond
         (not (sm/valid-safe-number? value))
         (u/not-valid plugin-id :rightMargin value)

         (not (r/check-permission plugin-id "content:write"))
         (u/not-valid plugin-id :rightMargin "Plugin doesn't have 'content:write' permission")

         :else
         (st/emit! (dwsl/update-layout-child #{id} {:layout-item-margin {:m2 value}}))))}

    :bottomMargin
    {:this true
     :get #(-> % u/proxy->shape :layout-item-margin :m3 (d/nilv 0))
     :set
     (fn [_ value]
       (cond
         (not (sm/valid-safe-number? value))
         (u/not-valid plugin-id :bottomMargin value)

         (not (r/check-permission plugin-id "content:write"))
         (u/not-valid plugin-id :bottomMargin "Plugin doesn't have 'content:write' permission")

         :else
         (st/emit! (dwsl/update-layout-child #{id} {:layout-item-margin {:m3 value}}))))}

    :leftMargin
    {:this true
     :get #(-> % u/proxy->shape :layout-item-margin :m4 (d/nilv 0))
     :set
     (fn [_ value]
       (cond
         (not (sm/valid-safe-number? value))
         (u/not-valid plugin-id :leftMargin value)

         (not (r/check-permission plugin-id "content:write"))
         (u/not-valid plugin-id :leftMargin "Plugin doesn't have 'content:write' permission")

         :else
         (st/emit! (dwsl/update-layout-child #{id} {:layout-item-margin {:m4 value}}))))}

    :maxWidth
    {:this true
     :get #(-> % u/proxy->shape :layout-item-max-w)
     :set
     (fn [_ value]
       (cond
         (not (sm/valid-safe-number? value))
         (u/not-valid plugin-id :maxWidth value)

         (not (r/check-permission plugin-id "content:write"))
         (u/not-valid plugin-id :maxWidth "Plugin doesn't have 'content:write' permission")

         :else
         (st/emit! (dwsl/update-layout-child #{id} {:layout-item-max-w value}))))}

    :minWidth
    {:this true
     :get #(-> % u/proxy->shape :layout-item-min-w)
     :set
     (fn [_ value]
       (cond
         (not (sm/valid-safe-number? value))
         (u/not-valid plugin-id :minWidth value)

         (not (r/check-permission plugin-id "content:write"))
         (u/not-valid plugin-id :minWidth "Plugin doesn't have 'content:write' permission")

         :else
         (st/emit! (dwsl/update-layout-child #{id} {:layout-item-min-w value}))))}

    :maxHeight
    {:this true
     :get #(-> % u/proxy->shape :layout-item-max-h)
     :set
     (fn [_ value]
       (cond
         (not (sm/valid-safe-number? value))
         (u/not-valid plugin-id :maxHeight value)

         (not (r/check-permission plugin-id "content:write"))
         (u/not-valid plugin-id :maxHeight "Plugin doesn't have 'content:write' permission")

         :else
         (st/emit! (dwsl/update-layout-child #{id} {:layout-item-max-h value}))))}

    :minHeight
    {:this true
     :get #(-> % u/proxy->shape :layout-item-min-h)
     :set
     (fn [_ value]
       (cond
         (not (sm/valid-safe-number? value))
         (u/not-valid plugin-id :minHeight value)

         (not (r/check-permission plugin-id "content:write"))
         (u/not-valid plugin-id :minHeight "Plugin doesn't have 'content:write' permission")

         :else
         (st/emit! (dwsl/update-layout-child #{id} {:layout-item-min-h value}))))}))
