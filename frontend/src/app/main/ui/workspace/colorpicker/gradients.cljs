;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; This Source Code Form is "Incompatible With Secondary Licenses", as
;; defined by the Mozilla Public License, v. 2.0.
;;
;; Copyright (c) 2020 UXBOX Labs SL

(ns app.main.ui.workspace.colorpicker.gradients
  (:require
   [rumext.alpha :as mf]
   [okulary.core :as l]
   [cuerdas.core :as str]
   [app.common.geom.point :as gpt]
   [app.common.math :as math]
   [app.common.uuid :refer [uuid]]
   [app.util.dom :as dom]
   [app.util.color :as uc]
   [app.util.object :as obj]
   [app.main.store :as st]
   [app.main.refs :as refs]
   [app.main.data.workspace.libraries :as dwl]
   [app.main.data.colors :as dc]
   [app.main.data.modal :as modal]
   [app.main.ui.icons :as i]
   [app.util.i18n :as i18n :refer [t]]))

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
