;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) 2015-2017 Andrey Antukh <niwi@niwi.nz>
;; Copyright (c) 2015-2017 Juan de la Cruz <delacruzgarciajuan@gmail.com>

(ns uxbox.main.ui.dashboard.projects-createlightbox
  (:require [lentes.core :as l]
            [cuerdas.core :as str]
            [potok.core :as ptk]
            [uxbox.main.store :as st]
            [uxbox.main.constants :as c]
            [uxbox.main.exports :as exports]
            [uxbox.main.data.projects :as udp]
            [uxbox.main.data.lightbox :as udl]
            [uxbox.builtins.icons :as i]
            [uxbox.main.ui.dashboard.header :refer [header]]
            [uxbox.main.ui.lightbox :as lbx]
            [uxbox.main.ui.keyboard :as kbd]
            [uxbox.util.i18n :as t :refer [tr]]
            [uxbox.util.router :as r]
            [uxbox.util.forms :as forms]
            [uxbox.util.data :refer [read-string]]
            [uxbox.util.dom :as dom]
            [uxbox.util.blob :as blob]
            [uxbox.util.mixins :as mx :include-macros true]
            [uxbox.util.time :as dt]))

(def form-data (forms/focus-data :create-project st/state))
(def form-errors (forms/focus-errors :create-project st/state))
(def set-value! (partial forms/set-value! st/store :create-project))
(def set-error! (partial forms/set-error! st/store :create-project))
(def clear! (partial forms/clear! st/store :create-project))

(def ^:private create-project-form
  {:name [forms/required forms/string]
   :width [forms/required forms/integer]
   :height [forms/required forms/integer]
   :layout [forms/required forms/string]})

;; --- Lightbox: Layout input

(mx/defc layout-input
  [data layout-id]
  (let [layout (get c/page-layouts layout-id)]
    [:div
     [:input {:type "radio"
              :key layout-id
              :id layout-id
              :name "project-layout"
              :value (:name layout)
              :checked (when (= layout-id (:layout data)) "checked")
              :on-change #(do
                            (set-value! :layout layout-id)
                            (set-value! :width (:width layout))
                            (set-value! :height (:height layout)))}]
     [:label {:value (:name layout)
              :for layout-id}
      (:name layout)]]))

;; --- Lightbox: Layout selector

(mx/defc layout-selector
  [data]
  [:div.input-radio.radio-primary
   (layout-input data "mobile")
   (layout-input data "tablet")
   (layout-input data "notebook")
   (layout-input data "desktop")])

;; -- New Project Lightbox

(mx/defcs new-project-lightbox
  {:mixins [mx/static mx/reactive
            (forms/clear-mixin st/store :create-project)]}
  [own]
  (let [data (merge c/project-defaults (mx/react form-data))
        errors (mx/react form-errors)
        valid? (forms/valid? data create-project-form)]
    (letfn [(on-submit [event]
              (dom/prevent-default event)
              (when valid?
                (st/emit! (udp/create-project data))
                (udl/close!)))
            (set-value [event attr]
              (set-value! attr (dom/event->value event)))
            (swap-size []
              (set-value! :width (:height data))
              (set-value! :height (:width data)))
            (close []
              (udl/close!)
              (clear!))]
      [:div.lightbox-body
       [:h3 "New project"]
       [:form {:on-submit on-submit}
        [:input#project-name.input-text
         {:placeholder "New project name"
          :type "text"
          :value (:name data)
          :auto-focus true
          :on-change #(set-value % :name)}]
        [:div.project-size
         [:div.input-element.pixels
        [:span "Width"]
          [:input#project-witdh.input-text
           {:placeholder "Width"
            :type "number"
            :min 0 ;;TODO check this value
            :max 666666 ;;TODO check this value
            :value (:width data)
            :on-change #(set-value % :width)}]]
         [:a.toggle-layout {:on-click swap-size} i/toggle]
         [:div.input-element.pixels
          [:span "Height"]
          [:input#project-height.input-text
           {:placeholder "Height"
            :type "number"
            :min 0 ;;TODO check this value
            :max 666666 ;;TODO check this value
            :value (:height data)
            :on-change #(set-value % :height)}]]]

        ;; Layout selector
        (layout-selector data)

        ;; Submit
        [:input#project-btn.btn-primary
         {:value "Go go go!"
          :class (when-not valid? "btn-disabled")
          :disabled (not valid?)
          :type "submit"}]]
       [:a.close {:on-clic #(udl/close!)} i/close]])))

(defmethod lbx/render-lightbox :new-project
  [_]
  (new-project-lightbox))

