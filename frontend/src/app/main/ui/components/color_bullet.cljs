;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) UXBOX Labs SL

(ns app.main.ui.components.color-bullet
  (:require
   [app.util.color :as uc]
   [app.util.dom :as dom]
   [app.util.i18n :as i18n :refer [tr]]
   [rumext.alpha :as mf]))

(defn gradient-type->string [type]
  (case type
    :linear (tr "workspace.gradients.linear")
    :radial (tr "workspace.gradients.radial")
    nil))

(mf/defc color-bullet
  {::mf/wrap [mf/memo]}
  [{:keys [color on-click]}]
  (let [on-click (mf/use-fn
                  (mf/deps color on-click)
                  (fn [event]
                    (when (fn? on-click)
                      (^function on-click color event))))]

    (if (uc/multiple? color)
      [:div.color-bullet.multiple {:on-click on-click}]

      ;; No multiple selection
      (let [color (if (string? color) {:color color :opacity 1} color)]
        [:div.color-bullet.tooltip.tooltip-right
         {:class (dom/classnames :is-library-color (some? (:id color))
                                 :is-not-library-color (nil? (:id color))
                                 :is-gradient (some? (:gradient color)))
          :on-click on-click
          :alt (or (:name color) (:color color) (gradient-type->string (:type (:gradient color))))}
         (if  (:gradient color)
           [:div.color-bullet-wrapper {:style {:background (uc/color->background color)}}]
           [:div.color-bullet-wrapper
            [:div.color-bullet-left {:style {:background (uc/color->background (assoc color :opacity 1))}}]
            [:div.color-bullet-right {:style {:background (uc/color->background color)}}]])]))))

(mf/defc color-name [{:keys [color size on-click on-double-click]}]
  (let [color (if (string? color) {:color color :opacity 1} color)
        {:keys [name color gradient]} color
        color-str (or name color (gradient-type->string (:type gradient)))]
    (when (or (not size) (= size :big))
      [:span.color-text {:on-click #(when on-click (on-click %))
                         :on-double-click #(when on-double-click (on-double-click %))
                         :title name} color-str])))
