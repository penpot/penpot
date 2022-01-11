;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) UXBOX Labs SL

(ns app.main.ui.components.color-bullet
  (:require
   [app.util.color :as uc]
   [app.util.i18n :as i18n :refer [tr]]
   [rumext.alpha :as mf]))

(mf/defc color-bullet [{:keys [color on-click]}]
  (if (uc/multiple? color)
    [:div.color-bullet.multiple {:on-click #(when on-click (on-click %))}]

    ;; No multiple selection
    (let [color (if (string? color) {:color color :opacity 1} color)]
      [:div.color-bullet.tooltip.tooltip-right {:class (when (:id color) "is-library-color")
                          :on-click #(when on-click (on-click %))
                          :alt (or (:name color) (:color color))}
       (when (not (:gradient color))
         [:div.color-bullet-left {:style {:background (uc/color->background (assoc color :opacity 1))}}])

       [:div.color-bullet-right {:style {:background (uc/color->background color)}}]])))

(defn gradient-type->string [type]
  (case type
    :linear (tr "workspace.gradients.linear")
    :radial (tr "workspace.gradients.radial")
    (str "???" type)))

(mf/defc color-name [{:keys [color size on-click on-double-click]}]
  (let [color (if (string? color) {:color color :opacity 1} color)
        {:keys [name color gradient]} color
        color-str (or name color (gradient-type->string (:type gradient)))]
    (when (or (not size) (= size :big))
      [:span.color-text {:on-click #(when on-click (on-click %))
                         :on-double-click #(when on-double-click (on-double-click %))
                         :title name} color-str])))
