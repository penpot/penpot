;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) 2015-2016 Juan de la Cruz <delacruzgarciajuan@gmail.com>
;; Copyright (c) 2015-2019 Andrey Antukh <niwi@niwi.nz>

(ns uxbox.main.ui.workspace.sidebar.options.measures
  (:require
   [rumext.alpha :as mf]
   [uxbox.common.data :as d]
   [uxbox.builtins.icons :as i]
   [uxbox.main.data.workspace :as udw]
   [uxbox.main.geom :as geom]
   [uxbox.main.store :as st]
   [uxbox.util.dom :as dom]
   [uxbox.util.geom.point :as gpt]
   [uxbox.util.i18n :refer [tr]]
   [uxbox.util.math :as math]))

(mf/defc size-options
  [{:keys [shape] :as props}]
  (let [on-size-change
        (fn [event attr]
          (let [value (-> (dom/get-target event)
                          (dom/get-value)
                          (d/parse-integer 0))]
            (st/emit! (udw/update-dimensions (:id shape) {attr value}))))

        on-proportion-lock-change
        (fn [event]
          (st/emit! (udw/toggle-shape-proportion-lock (:id shape))))

        on-size-rx-change #(on-size-change % :rx)
        on-size-ry-change #(on-size-change % :ry)
        ]
    [:*
     [:span (tr "workspace.options.size")]
     [:div.row-flex
      [:div.input-element.pixels
       [:input.input-text {:type "number"
                           :min "0"
                           :on-change on-size-rx-change
                           :value (-> (:rx shape)
                                      (math/precision 2)
                                      (d/coalesce-str "0"))}]]
      [:div.lock-size {:class (when (:proportion-lock shape) "selected")
                       :on-click on-proportion-lock-change}
       (if (:proportion-lock shape)
         i/lock
         i/unlock)]

      [:div.input-element.pixels
       [:input.input-text {:type "number"
                           :min "0"
                           :on-change on-size-ry-change
                           :value (-> (:ry shape)
                                      (math/precision 2)
                                      (d/coalesce-str "0"))}]]]]))

(mf/defc position-options
  [{:keys [shape] :as props}]
  (let [on-position-change
        (fn [event attr]
          (let [value (-> (dom/get-target event)
                          (dom/get-value)
                          (d/parse-integer))
                point (gpt/point {attr value})]
            (st/emit! (udw/update-position (:id shape) point))))

        on-pos-cx-change #(on-position-change % :x)
        on-pos-cy-change #(on-position-change % :y)]
    [:*
     [:span (tr "workspace.options.position")]
     [:div.row-flex
      [:div.input-element.pixels
       [:input.input-text {:type "number"
                           :on-change on-pos-cx-change
                           :value (-> (:cx shape)
                                      (math/precision 2)
                                      (d/coalesce-str "0"))}]]
      [:div.input-element.pixels
     [:input.input-text {:type "number"
                         :on-change on-pos-cy-change
                         :value (-> (:cy shape)
                                    (math/precision 2)
                                    (d/coalesce-str "0"))}]]]]))

(mf/defc rotation-and-radius-options
  [{:keys [shape] :as props}]
  [:*
   [:span (tr "workspace.options.rotation-radius")]
   [:div.row-flex
    [:div.input-element.degrees
     [:input.input-text {:placeholder ""
                         :type "number"
                         :min 0
                         :max 360
                         ;; :on-change on-rotation-change
                         :value (-> (:rotation shape 0)
                                    (math/precision 2)
                                    (d/coalesce-str "0"))}]]

    [:div.input-element.pixels
     [:input.input-text
      {:type "number"
       ;; :on-change on-radius-change
       :value (-> (:rx shape)
                  (math/precision 2)
                  (d/coalesce-str "0"))}]]]])

(mf/defc measures-menu
  [{:keys [shape] :as props}]
  [:div.element-set
   [:div.element-set-title (tr "workspace.options.measures")]
   [:div.element-set-content
    [:& size-options {:shape shape}]
    [:& position-options {:shape shape}]
    [:& rotation-and-radius-options {:shape shape}]]])
