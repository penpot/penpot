;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) 2016 Andrey Antukh <niwi@niwi.nz>
;; Copyright (c) 2016 Juan de la Cruz <delacruzgarciajuan@gmail.com>

(ns uxbox.ui.workspace.settings
  (:require [sablono.core :as html :refer-macros [html]]
            [lentes.core :as l]
            [rum.core :as rum]
            [uxbox.constants :as c]
            [uxbox.state :as st]
            [uxbox.rstore :as rs]
            [uxbox.data.pages :as udp]
            [uxbox.data.forms :as udf]
            [uxbox.data.workspace :as udw]
            [uxbox.ui.icons :as i]
            [uxbox.ui.mixins :as mx]
            [uxbox.ui.forms :as forms]
            [uxbox.ui.lightbox :as lightbox]
            [uxbox.ui.colorpicker :as uucp]
            [uxbox.ui.workspace.base :as wb]
            [uxbox.util.dom :as dom]
            [uxbox.util.data :refer (parse-int)]))

;; --- Lentes

(def formdata (udf/focus-form-data :workspace/settings))
(def formerrors (udf/focus-form-errors :workspace/settings))
(def assign-field-value (partial udf/assign-field-value :workspace/settings))

;; --- Form Component

(def settings-form-defaults
  {:grid/x-axis c/grid-x-axis
   :grid/y-axis c/grid-y-axis
   :grid/color "#0000ff"
   :grid/alignment false})

(defn- settings-form-render
  [own]
  (let [page (rum/react wb/page-l)
        form (merge settings-form-defaults
                    (:options page)
                    (rum/react formdata))
        errors (rum/react formerrors)]
    (letfn [(on-field-change [field event]
              (let [value (dom/event->value event)
                    value (parse-int value "")]
                (rs/emit! (assign-field-value field value))))
            (on-color-change [color]
              (rs/emit! (assign-field-value :grid/color color)))
            (on-align-change [event]
              (let [checked? (-> (dom/get-target event)
                                 (dom/checked?))]
                (rs/emit! (assign-field-value :grid/alignment checked?))))
            (on-submit [event]
              (dom/prevent-default event)
              (rs/emit! (udw/submit-workspace-settings (:id page) form)))]
      (html
       [:form {:on-submit on-submit}
        [:span.lightbox-label "Grid size"]
        [:div.project-size
         [:input#grid-x.input-text
          {:placeholder "X px"
           :type "number"
           :class (forms/error-class errors :grid/x-axis)
           :value (:grid/x-axis form "")
           :on-change (partial on-field-change :grid/x-axis)
           :min 1
           :max 100}]
         [:input#grid-y.input-text
          {:placeholder "Y px"
           :type "number"
           :class (forms/error-class errors :grid/y-axis)
           :value (:grid/y-axis form "")
           :on-change (partial on-field-change :grid/y-axis)
           :min 1
           :max 100}]]
        [:span.lightbox-label "Grid color"]
        [:div.color-picker-default
         (uucp/colorpicker
          :value (:grid/color form)
          :on-change on-color-change)]
        [:span.lightbox-label "Grid magnet option"]
        [:div.input-checkbox.check-primary
         [:input
          {:type "checkbox"
           :on-change on-align-change
           :checked (:grid/alignment form)
           :id "magnet"
           :value "Yes"}]
         [:label {:for "magnet"} "Activate magnet"]]
        [:input.btn-primary
         {:type "submit"
          :value "Save"}]]))))

(def settings-form
  (mx/component
   {:render settings-form-render
    :name "settings-form"
    :mixins [(mx/local) rum/reactive mx/static]}))

(defn- settings-dialog-render
  [own]
  (html
   [:div.lightbox-body.settings
    [:h3 "Grid settings"]
    (settings-form)
    [:a.close {:href "#"
               :on-click #(do (dom/prevent-default %)
                              (lightbox/close!))} i/close]]))

(def settings-dialog
  (mx/component
   {:render settings-dialog-render
    :name "settings-dialog"
    :mixins []}))

(defmethod lightbox/render-lightbox :settings
  [_]
  (settings-dialog))
