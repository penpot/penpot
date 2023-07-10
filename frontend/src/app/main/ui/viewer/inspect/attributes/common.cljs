;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC

(ns app.main.ui.viewer.inspect.attributes.common
  (:require
   [app.main.refs :as refs]
   [app.main.store :as st]
   [app.main.ui.components.color-bullet :refer [color-bullet color-name]]
   [app.main.ui.components.copy-button :refer [copy-button]]
   [app.util.color :as uc]
   [app.util.dom :as dom]
   [app.util.i18n :refer [tr]]
   [cuerdas.core :as str]
   [okulary.core :as l]
   [rumext.v2 :as mf]))

(def file-colors-ref
  (l/derived (l/in [:viewer :file :data :colors]) st/state))

(defn make-colors-library-ref [libraries-place file-id]
  (let [get-library
        (fn [state]
          (get-in state [libraries-place file-id :data :colors]))]
    (l/derived get-library st/state)))

(defn- get-colors-library [color]
  (let [colors-library-v  (-> (mf/use-memo
                               (mf/deps (:file-id color))
                               #(make-colors-library-ref :viewer-libraries (:file-id color)))
                              mf/deref)
        colors-library-ws (-> (mf/use-memo
                               (mf/deps (:file-id color))
                               #(make-colors-library-ref :workspace-libraries (:file-id color)))
                              mf/deref)]
    (or colors-library-v colors-library-ws)))

(defn- get-file-colors []
  (or (mf/deref file-colors-ref) (mf/deref refs/workspace-file-colors)))

(mf/defc color-row [{:keys [color format copy-data on-change-format]}]
  (let [colors-library (get-colors-library color)
        file-colors (get-file-colors)
        color-library-name (get-in (or colors-library file-colors) [(:id color) :name])
        color (assoc color :color-library-name color-library-name)]
    [:div.attributes-color-row
     (when color-library-name
       [:div.attributes-color-id
        [:& color-bullet {:color color}]
        [:div color-library-name]])

     [:div.attributes-color-value {:class (when color-library-name "hide-color")}
      [:& color-bullet {:color color}]

      (if (:gradient color)
        [:& color-name {:color color}]
        (case format
          :rgba (let [[r g b a] (uc/hex->rgba (:color color) (:opacity color))]
                  [:div (str/fmt "%s, %s, %s, %s" r g b a)])
          :hsla (let [[h s l a] (uc/hex->hsla (:color color) (:opacity color))
                      result (uc/format-hsla [h s l a])]
                  [:div result])
          [:*
           [:& color-name {:color color}]
           (when-not (:gradient color) [:div (str (* 100 (:opacity color)) "%")])]))

      (when-not (and on-change-format (:gradient color))
        [:select.color-format-select {:on-change #(-> (dom/get-target-val %) keyword on-change-format)}
         [:option {:value "hex"}
          (tr "inspect.attributes.color.hex")]

         [:option {:value "rgba"}
          (tr "inspect.attributes.color.rgba")]

         [:option {:value "hsla"}
          (tr "inspect.attributes.color.hsla")]])]
     (when copy-data
       [:& copy-button {:data copy-data}])]))

