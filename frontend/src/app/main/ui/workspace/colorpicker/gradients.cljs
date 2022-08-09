;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) UXBOX Labs SL

(ns app.main.ui.workspace.colorpicker.gradients
  (:require
   [app.common.data.macros :as dm]
   [cuerdas.core :as str]
   [rumext.alpha :as mf]))

(defn- format-rgba
  [{:keys [r g b alpha offset]}]
  (str/ffmt "rgba(%1, %2, %3, %4) %5%%" r g b alpha (* offset 100)))

(defn- gradient->string [stops]
  (let [gradient-css (str/join ", " (map format-rgba stops))]
    (str/ffmt "linear-gradient(90deg, %1)" gradient-css)))

(mf/defc gradients
  [{:keys [stops editing-stop on-select-stop]}]
  [:div.gradient-stops
   [:div.gradient-background-wrapper
    [:div.gradient-background {:style {:background (gradient->string stops)}}]]

   [:div.gradient-stop-wrapper
    (for [{:keys [offset hex r g b alpha] :as value} stops]
      [:div.gradient-stop
       {:class (when (= editing-stop offset) "active")
        :on-click (partial on-select-stop offset)
        :style {:left (dm/str (* offset 100) "%")}
        :key (dm/str offset)}

       [:div.gradient-stop-color {:style {:background-color hex}}]
       [:div.gradient-stop-alpha {:style {:background-color (str/ffmt "rgba(%1, %2, %3, %4)" r g b alpha)}}]])]])
