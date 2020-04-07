;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; This Source Code Form is "Incompatible With Secondary Licenses", as
;; defined by the Mozilla Public License, v. 2.0.
;;
;; Copyright (c) 2020 UXBOX Labs SL

(ns uxbox.main.ui.workspace.sidebar.options.rect
  (:require
   [rumext.alpha :as mf]
   [uxbox.common.data :as d]
   [uxbox.builtins.icons :as i]
   [uxbox.main.data.workspace :as udw]
   [uxbox.main.geom :as geom]
   [uxbox.main.store :as st]
   [uxbox.main.ui.workspace.sidebar.options.fill :refer [fill-menu]]
   [uxbox.main.ui.workspace.sidebar.options.stroke :refer [stroke-menu]]
   [uxbox.util.dom :as dom]
   [uxbox.util.geom.point :as gpt]
   [uxbox.util.i18n :as i18n :refer [tr t]]
   [uxbox.util.math :as math]))

(mf/defc measures-menu
  [{:keys [shape] :as props}]
  (let [locale (i18n/use-locale)

        on-size-change
        (fn [event attr]
          (let [value (-> (dom/get-target event)
                          (dom/get-value)
                          (d/parse-integer 0))]
            (st/emit! (udw/update-rect-dimensions (:id shape) attr value))))

        on-proportion-lock-change
        (fn [event]
          (st/emit! (udw/toggle-shape-proportion-lock (:id shape))))

        on-position-change
        (fn [event attr]
          (let [value (-> (dom/get-target event)
                          (dom/get-value)
                          (d/parse-integer 0))]
            (st/emit! (udw/update-position (:id shape) {attr value}))))

        on-rotation-change
        (fn [event]
          (let [value (-> (dom/get-target event)
                          (dom/get-value)
                          (d/parse-integer 0))]
            (st/emit! (udw/update-shape (:id shape) {:rotation value}))))

        on-radius-change
        (fn [event]
          (let [value (-> (dom/get-target event)
                          (dom/get-value)
                          (d/parse-integer 0))]
            (st/emit! (udw/update-shape (:id shape) {:rx value :ry value}))))

        on-width-change #(on-size-change % :width)
        on-height-change #(on-size-change % :height)
        on-pos-x-change #(on-position-change % :x)
        on-pos-y-change #(on-position-change % :y)]

    [:div.element-set
     [:div.element-set-content

      ;; WIDTH & HEIGHT
      [:div.row-flex
       [:span.element-set-subtitle (t locale "workspace.options.size")]
       [:div.lock-size {:class (when (:proportion-lock shape) "selected")
                        :on-click on-proportion-lock-change}
        (if (:proportion-lock shape)
          i/lock
          i/unlock)]
       [:div.input-element.pixels
        [:input.input-text {:type "number"
                            :min "0"
                            :no-validate true
                            :on-change on-width-change
                            :value (str (-> (:width shape)
                                            (d/coalesce 0)
                                            (math/round)))}]]


       [:div.input-element.pixels
        [:input.input-text {:type "number"
                            :min "0"
                            :no-validate true
                            :on-change on-height-change
                            :value (str (-> (:height shape)
                                            (d/coalesce 0)
                                            (math/round)))}]]]

      ;; POSITION
      [:div.row-flex
       [:span.element-set-subtitle (t locale "workspace.options.position")]
       [:div.input-element.pixels
        [:input.input-text {:placeholder "x"
                            :type "number"
                            :no-validate true
                            :on-change on-pos-x-change
                            :value (str (-> (:x shape)
                                            (d/coalesce 0)
                                            (math/round)))}]]
       [:div.input-element.pixels
        [:input.input-text {:placeholder "y"
                            :type "number"
                            :no-validate true
                            :on-change on-pos-y-change
                            :value (str (-> (:y shape)
                                            (d/coalesce 0)
                                            (math/round)))}]]]

      [:div.row-flex
       [:span.element-set-subtitle (t locale "workspace.options.rotation")]
       [:div.input-element.degrees
        [:input.input-text
         {:placeholder ""
          :type "number"
          :no-validate true
          :min "0"
          :max "360"
          :on-change on-rotation-change
          :value (str (-> (:rotation shape)
                          (d/coalesce 0)
                          (math/round)))}]]
       [:input.slidebar
        {:type "range"
         :min "0"
         :max "360"
         :step "1"
         :no-validate true
         :on-change on-rotation-change
         :value (str (-> (:rotation shape)
                         (d/coalesce 0)))}]]

      [:div.row-flex
       [:span.element-set-subtitle (t locale "workspace.options.radius")]
       [:div.input-element.pixels
        [:input.input-text
         {:placeholder "rx"
          :type "number"
          :on-change on-radius-change
          :value (str (-> (:rx shape)
                          (d/coalesce 0)
                          (math/round)))}]]
       [:div.input-element]]]]))


(mf/defc options
  [{:keys [shape] :as props}]
  [:div
   [:& measures-menu {:shape shape}]
   [:& fill-menu {:shape shape}]
   [:& stroke-menu {:shape shape}]])
