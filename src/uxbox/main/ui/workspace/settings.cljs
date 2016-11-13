;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) 2016 Andrey Antukh <niwi@niwi.nz>
;; Copyright (c) 2016 Juan de la Cruz <delacruzgarciajuan@gmail.com>

(ns uxbox.main.ui.workspace.settings
  (:require [lentes.core :as l]
            [uxbox.main.constants :as c]
            [uxbox.main.state :as st]
            [uxbox.util.rstore :as rs]
            [uxbox.main.data.pages :as udp]
            [uxbox.main.data.workspace :as udw]
            [uxbox.main.data.lightbox :as udl]
            [uxbox.main.ui.icons :as i]
            [uxbox.util.forms :as forms]
            [uxbox.util.mixins :as mx :include-macros true]
            [uxbox.main.ui.lightbox :as lbx]
            [uxbox.main.ui.colorpicker :as uucp]
            [uxbox.main.ui.workspace.base :as wb]
            [uxbox.util.dom :as dom]
            [uxbox.util.data :refer (parse-int)]))

(def form-data (forms/focus-data :workspace-settings st/state))
(def form-errors (forms/focus-errors :workspace-settings st/state))
(def set-value! (partial forms/set-value! :workspace-settings))
(def set-errors! (partial forms/set-errors! :workspace-settings))
(def page-ref wb/page-ref)

;; --- Form Component

(def +settings-defaults+
  {:grid-x-axis c/grid-x-axis
   :grid-y-axis c/grid-y-axis
   :grid-color "#b5bdb9"
   :grid-alignment false})

(def +settings-form+
  {:grid-y-axis [forms/required forms/integer [forms/in-range 2 100]]
   :grid-x-axis [forms/required forms/integer [forms/in-range 2 100]]
   :grid-alignment [forms/boolean]
   :grid-color [forms/required forms/color]})

(mx/defc settings-form
  {:mixins [mx/reactive]}
  []
  (let [{:keys [id] :as page} (mx/react page-ref)
        errors (mx/react form-errors)
        data (merge +settings-defaults+
                    (:metadata page)
                    (mx/react form-data))]
    (letfn [(on-field-change [field event]
              (let [value (dom/event->value event)
                    value (parse-int value "")]
                (set-value! field value)))
            (on-color-change [color]
              (set-value! :grid-color color))
            (on-align-change [event]
              (let [checked? (-> (dom/get-target event)
                                 (dom/checked?))]
                (set-value! :grid-alignment checked?)))
            (on-submit [event]
              (dom/prevent-default event)
              (let [[errors data] (forms/validate data +settings-form+)]
                (if errors
                  (set-errors! errors)
                  (rs/emit! (udw/update-metadata id data)
                            (forms/clear :workspace-settings)
                            (udl/hide-lightbox)))))]
      [:form {:on-submit on-submit}
       [:span.lightbox-label "Grid size"]
       [:div.project-size
        [:div.input-element.pixels
         [:input#grid-x.input-text
          {:placeholder "X"
           :type "number"
           :class (forms/error-class errors :grid-x-axis)
           :value (:grid-x-axis data "")
           :on-change (partial on-field-change :grid-x-axis)
           :min 2
           :max 100}]]
        [:div.input-element.pixels
         [:input#grid-y.input-text
          {:placeholder "Y"
           :type "number"
           :class (forms/error-class errors :grid-y-axis)
           :value (:grid-y-axis data "")
           :on-change (partial on-field-change :grid-y-axis)
           :min 2
           :max 100}]]]
       [:span.lightbox-label "Grid color"]
       (uucp/colorpicker
        :value (:grid-color data)
        :on-change on-color-change)
       [:span.lightbox-label "Grid magnet option"]
       [:div.input-checkbox.check-primary
        [:input
         {:type "checkbox"
          :on-change on-align-change
          :checked (:grid-alignment data)
          :id "magnet"
          :value "Yes"}]
        [:label {:for "magnet"} "Activate magnet"]]
       [:input.btn-primary
        {:type "submit"
         :value "Save"}]])))

(mx/defc settings-dialog
  [own]
  [:div.lightbox-body.settings
   [:h3 "Grid settings"]
   (settings-form)
   [:a.close {:href "#"
              :on-click #(do (dom/prevent-default %)
                             (udl/close!))} i/close]])

(defmethod lbx/render-lightbox :settings
  [_]
  (settings-dialog))
