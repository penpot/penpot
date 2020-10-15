;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; This Source Code Form is "Incompatible With Secondary Licenses", as
;; defined by the Mozilla Public License, v. 2.0.
;;
;; Copyright (c) 2020 UXBOX Labs SL

(ns app.main.ui.components.color-bullet
  (:require
   [rumext.alpha :as mf]
   [app.util.color :as uc]))

(mf/defc color-bullet [{:keys [color on-click]}]
  (if (uc/multiple? color)
    [:div.color-bullet.multiple {:on-click #(when on-click (on-click %))}]

    ;; No multiple selection
    (let [color (if (string? color) {:color color :opacity 1} color)]
      [:div.color-bullet {:on-click #(when on-click (on-click %))}
       (when (not (:gradient color))
         [:div.color-bullet-left {:style {:background (uc/color->background (assoc color :opacity 1))}}])

       [:div.color-bullet-right {:style {:background (uc/color->background color)}}]])))

(defn gradient-type->string [{:keys [type]}]
  (case type
    :linear "Linear"
    :radial "Radial"))

(mf/defc color-name [{:keys [color size on-click on-double-click]}]
  (let [color (if (string? color) {:color color :opacity 1} color)
        {:keys [name color opacity gradient]} color
        color-str (or name color (gradient-type->string gradient))]
    #_(when (= size :big) [:span.color-text {:title (:name color) } (or (:name color) (:value color))])
    (when (= size :big)
      [:span.color-text {:on-click #(when on-click (on-click %))
                         :on-double-click #(when on-double-click (on-double-click %))
                         :title name } color-str])))
