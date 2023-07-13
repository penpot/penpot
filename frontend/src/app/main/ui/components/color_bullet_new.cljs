;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC

(ns app.main.ui.components.color-bullet-new
  (:require-macros [app.main.style :as stl])
  (:require
   [app.util.color :as uc]
   [rumext.v2 :as mf]))

(mf/defc color-bullet
  {::mf/wrap [mf/memo]
   ::mf/wrap-props false}
  [{:keys [color on-click mini?]}]
  (let [on-click (mf/use-fn
                  (mf/deps color on-click)
                  (fn [event]
                    (when (fn? on-click)
                      (^function on-click color event))))]

    (if (uc/multiple? color)
      [:div {:on-click on-click :class (stl/css :color-bullet :multiple)}]
      ;; No multiple selection
      (let [color    (if (string? color) {:color color :opacity 1} color)
            id       (:id color)
            gradient (:gradient color)
            opacity  (:opacity color)]
        [:div
         {:class (stl/css-case
                  :color-bullet true
                  :mini mini?
                  :is-library-color (some? id)
                  :is-not-library-color (nil? id)
                  :is-gradient (some? gradient)
                  :is-transparent (and opacity (> 1 opacity)))
          :on-click on-click}

         (if (some? gradient)
           [:div {:class (stl/css :color-bullet-wrapper)
                  :style {:background (uc/color->background color)}}]

           [:div {:class (stl/css :color-bullet-wrapper)}
            [:div {:class (stl/css :color-bullet-left)
                   :style {:background (uc/color->background (assoc color :opacity 1))}}]
            [:div {:class (stl/css :color-bullet-right)
                   :style {:background (uc/color->background color)}}]])]))))


(mf/defc color-name
  {::mf/wrap-props false}
  [{:keys [color size on-click on-double-click]}]
  (let [{:keys [name color gradient]} (if (string? color) {:color color :opacity 1} color)]
    (when (or (not size) (> size 64))
      [:span {:class (stl/css-case
                      :color-text true
                      :small-text (and (>= size 64) (< size 72)))
              :on-click on-click
              :on-double-click on-double-click}
       (or name color (uc/gradient-type->string (:type gradient)))])))
