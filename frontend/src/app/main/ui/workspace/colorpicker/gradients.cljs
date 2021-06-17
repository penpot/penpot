;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) UXBOX Labs SL

(ns app.main.ui.workspace.colorpicker.gradients
  (:require
   [cuerdas.core :as str]
   [rumext.alpha :as mf]))

(defn gradient->string [stops]
  (let [format-stop
        (fn [[offset {:keys [r g b alpha]}]]
          (str/fmt "rgba(%s, %s, %s, %s) %s"
                   r g b alpha (str (* offset 100) "%")))

        gradient-css (str/join "," (map format-stop stops))]
    (str/fmt "linear-gradient(90deg, %s)" gradient-css)))

(mf/defc gradients [{:keys [type stops editing-stop on-select-stop]}]
  (when (#{:linear-gradient :radial-gradient} type)
    [:div.gradient-stops
     [:div.gradient-background-wrapper
      [:div.gradient-background {:style {:background (gradient->string stops)}}]]

     [:div.gradient-stop-wrapper
      (for [[offset value] stops]
        [:div.gradient-stop
         {:class (when (= editing-stop offset) "active")
          :on-click (partial on-select-stop offset)
          :style {:left (str (* offset 100) "%")}}

         (let [{:keys [hex r g b alpha]} value]
           [:*
            [:div.gradient-stop-color {:style {:background-color hex}}]
            [:div.gradient-stop-alpha {:style {:background-color (str/format "rgba(%s, %s, %s, %s)" r g b alpha)}}]])])]]))
