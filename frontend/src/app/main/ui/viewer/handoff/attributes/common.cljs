;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; This Source Code Form is "Incompatible With Secondary Licenses", as
;; defined by the Mozilla Public License, v. 2.0.
;;
;; Copyright (c) 2020 UXBOX Labs SL

(ns app.main.ui.viewer.handoff.attributes.common
  (:require
   [rumext.alpha :as mf]
   [cuerdas.core :as str]
   [app.util.dom :as dom]
   [app.util.i18n :refer [t] :as i18n]
   [app.util.color :as uc]
   [app.common.math :as mth]
   [app.main.ui.icons :as i]
   [app.util.code-gen :as cg]
   [app.util.webapi :as wapi]
   [app.main.ui.components.copy-button :refer [copy-button]]
   [app.main.ui.components.color-bullet :refer [color-bullet color-name]]))

(mf/defc color-row [{:keys [color format copy-data on-change-format]}]
  (let [locale (mf/deref i18n/locale)]
    [:div.attributes-color-row
     [:& color-bullet {:color color}]

     (if (:gradient color)
       [:& color-name {:color color}]
       (case format
         :rgba (let [[r g b a] (->> (uc/hex->rgba (:color color) (:opacity color)) (map #(mth/precision % 2)))]
                 [:div (str/fmt "%s, %s, %s, %s" r g b a)])
         :hsla (let [[h s l a] (->> (uc/hex->hsla (:color color) (:opacity color)) (map #(mth/precision % 2)))]
                 [:div (str/fmt "%s, %s, %s, %s" h s l a)])
         [:*
          [:& color-name {:color color}]
          (when-not (:gradient color) [:div (str (* 100 (:opacity color)) "%")])]))

     (when-not (and on-change-format (:gradient color))
       [:select {:on-change #(-> (dom/get-target-val %) keyword on-change-format)}
        [:option {:value "hex"}
         (t locale "handoff.attributes.color.hex")]

        [:option {:value "rgba"}
         (t locale "handoff.attributes.color.rgba")]

        [:option {:value "hsla"}
         (t locale "handoff.attributes.color.hsla")]])

     (when copy-data
       [:& copy-button {:data copy-data}])]))

