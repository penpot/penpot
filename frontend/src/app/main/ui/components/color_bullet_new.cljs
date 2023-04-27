;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC

(ns app.main.ui.components.color-bullet-new
  (:require-macros [app.main.style :refer [css]])
  (:require
   [app.util.color :as uc]
   [app.util.dom :as dom]
   [rumext.v2 :as mf]))


(mf/defc color-bullet
  {::mf/wrap [mf/memo]}
  [{:keys [color on-click mini?]}]
  (let [on-click (mf/use-fn
                  (mf/deps color on-click)
                  (fn [event]
                    (when (fn? on-click)
                      (^function on-click color event))))]
    (if (uc/multiple? color)
      [:div {:on-click on-click
             :class (dom/classnames (css :color-bullet) true
                                    (css :multiple) true)}]
      ;; No multiple selection
      (let [color (if (string? color) {:color color :opacity 1} color)]
        [:div
         {:class (dom/classnames (css :color-bullet) true
                                 (css :mini) mini?
                                 (css :is-library-color) (some? (:id color))
                                 (css :is-not-library-color) (nil? (:id color))
                                 (css :is-gradient) (some? (:gradient color))
                                 (css :is-transparent) (and (:opacity color) (> 1 (:opacity color))))
          :on-click on-click}
         (if  (:gradient color)
           [:div {:class (dom/classnames (css :color-bullet-wrapper) true)
                  :style {:background (uc/color->background color)}}]
           [:div {:class (dom/classnames (css :color-bullet-wrapper) true)}
            [:div {:class (dom/classnames (css :color-bullet-left) true)
                   :style {:background (uc/color->background (assoc color :opacity 1))}}]
            [:div {:class (dom/classnames (css :color-bullet-right) true)
                   :style {:background (uc/color->background color)}}]])]))))
(mf/defc color-name [{:keys [color size on-click on-double-click]}]
  (let [color (if (string? color) {:color color :opacity 1} color)
        {:keys [name color gradient]} color
        color-str (or name color (uc/gradient-type->string (:type gradient)))
        text-small (and (>= size 64) (< size 72))]
    (when (or (not size) (> size 64))
      [:span {:class (dom/classnames (css :color-text) true
                                     (css :small-text) text-small)
              :on-click #(when on-click (on-click %))
              :on-double-click #(when on-double-click (on-double-click %))} color-str])))
