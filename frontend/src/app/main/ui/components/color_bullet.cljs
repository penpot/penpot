;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC

(ns app.main.ui.components.color-bullet
  (:require
   [app.config :as cfg]
   [app.util.color :as uc]
   [app.util.dom :as dom]
   [app.util.i18n :as i18n :refer [tr]]
   [cuerdas.core :as str]
   [rumext.v2 :as mf]))

(mf/defc color-bullet
  {::mf/wrap [mf/memo]
   ::mf/wrap-props false}
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
        [:div.color-bullet
         {:class (dom/classnames :is-library-color (some? (:id color))
                                 :is-not-library-color (nil? (:id color))
                                 :is-gradient (some? (:gradient color)))
          :on-click on-click
          :title (uc/get-color-name color)}
         (cond
           (:gradient color)
           [:div.color-bullet-wrapper {:style {:background (uc/color->background color)}}]

           (:image color)
           (let [uri (cfg/resolve-file-media (:image color))]
             [:div.color-bullet-wrapper {:style {:background-size "contain" :background-image (str/ffmt "url(%)" uri)}}])

           :else
           [:div.color-bullet-wrapper
            [:div.color-bullet-left {:style {:background (uc/color->background (assoc color :opacity 1))}}]
            [:div.color-bullet-right {:style {:background (uc/color->background color)}}]])]))))

(mf/defc color-name
  {::mf/wrap-props false}
  [{:keys [color size on-click on-double-click]}]
  (let [{:keys [name color gradient image]} (if (string? color) {:color color :opacity 1} color)]
    (when (or (not size) (= size :big))
      [:span.color-text
       {:on-click on-click
        :on-double-click on-double-click
        :title name}
       (if (some? image)
         (tr "media.image")
         (or name color (uc/gradient-type->string (:type gradient))))])))
