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
            [uxbox.rstore :as rs]
            [uxbox.data.pages :as udp]
            [uxbox.ui.icons :as i]
            [uxbox.ui.mixins :as mx]
            [uxbox.ui.lightbox :as lightbox]
            [uxbox.ui.colorpicker :as uucp]
            [uxbox.ui.workspace.base :as wb]
            [uxbox.util.dom :as dom]
            [uxbox.util.data :refer (parse-int)]))

;; --- Lentes

(def page-metadata-l
  (-> (l/key :metadata)
      (l/focus-atom wb/page-l)))

;; --- Form Component

(defn- settings-form-render
  [own]
  (let [local (:rum/local own)
        page (rum/react wb/page-l)
        opts (merge (:options page)
                    (deref local))]
    (letfn [(on-field-change [field event]
              (let [value (dom/event->value event)
                    value (parse-int value)]
                (swap! local assoc field value)))
            (on-color-change [color]
              (swap! local assoc :grid/color color))
            (on-align-change [event]
              (let [checked? (-> (dom/get-target event)
                                 (dom/checked?))]
                (swap! local assoc :grid/align checked?)))
            (on-submit [event]
              (dom/prevent-default event)
              (let [page (assoc page :options opts)]
                (rs/emit! (udp/update-page-metadata page))
                (lightbox/close!)))]
      (html
       [:form {:on-submit on-submit}
        [:span.lightbox-label "Grid size"]
        [:div.project-size
         [:input#grid-x.input-text
          {:placeholder "X px"
           :type "number"
           ;; TODO: put here the default from constants
           :value (:grid/x-axis opts "10")
           :on-change (partial on-field-change :grid/x-axis)
           :min 1 ;;TODO check this value
           :max 100}]
         [:input#grid-y.input-text
          {:placeholder "Y px"
           :type "number"
           :value (:grid/y-axis opts "10")
           :on-change (partial on-field-change :grid/y-axis)
           :min 1
           :max 100}]]
        [:span.lightbox-label "Grid color"]
        [:div.color-picker-default
         (uucp/colorpicker
          :value (:grid/color opts "#0000ff")
          :on-change on-color-change)]
        [:span.lightbox-label "Grid magnet option"]
        [:div.input-checkbox.check-primary
         [:input
          {:type "checkbox"
           :on-change on-align-change
           :checked (:grid/align opts)
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
