;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC

(ns app.main.ui.workspace.colorpicker.gradients
  (:require-macros [app.main.style :as stl])
  (:require
   [app.common.data.macros :as dm]
   [cuerdas.core :as str]
   [rumext.v2 :as mf]))

(defn- format-rgba
  [{:keys [r g b alpha offset]}]
  (str/ffmt "rgba(%1, %2, %3, %4) %5%%" r g b alpha (* offset 100)))

(defn- gradient->string [stops]
  (let [gradient-css (str/join ", " (map format-rgba stops))]
    (str/ffmt "linear-gradient(90deg, %1)" gradient-css)))

(mf/defc gradients
  [{:keys [stops editing-stop on-select-stop]}]
  [:div {:class (stl/css :gradient-stops)}
   [:div {:class (stl/css :gradient-background-wrapper)}
    [:div {:class (stl/css :gradient-background)
           :style {:background (gradient->string stops)}}]]

   [:div {:class (stl/css :gradient-stop-wrapper)}
    (for [{:keys [offset hex r g b alpha] :as value} stops]
      [:button {:class (stl/css-case :gradient-stop true
                                     :selected (= editing-stop offset))
                :data-value (str offset)
                :on-click on-select-stop
                :style {:left (dm/str (* offset 100) "%")}
                :key (dm/str offset)}

       [:div {:class (stl/css :gradient-stop-color)
              :style {:background-color hex}}]
       [:div {:class (stl/css :gradient-stop-alpha)
              :style {:background-color (str/ffmt "rgba(%1, %2, %3, %4)" r g b alpha)}}]])]])
