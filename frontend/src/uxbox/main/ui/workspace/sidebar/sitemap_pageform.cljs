;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) 2015-2016 Andrey Antukh <niwi@niwi.nz>
;; Copyright (c) 2015-2016 Juan de la Cruz <delacruzgarciajuan@gmail.com>

(ns uxbox.main.ui.workspace.sidebar.sitemap-pageform
  (:require [lentes.core :as l]
            [cuerdas.core :as str]
            [uxbox.main.state :as st]
            [uxbox.main.data.pages :as udp]
            [uxbox.main.data.workspace :as dw]
            [uxbox.main.data.lightbox :as udl]
            [uxbox.main.ui.dashboard.projects :refer (+layouts+)]
            [uxbox.main.ui.icons :as i]
            [uxbox.main.ui.lightbox :as lbx]
            [uxbox.util.i18n :refer (tr)]
            [uxbox.util.router :as r]
            [uxbox.util.rstore :as rs]
            [uxbox.util.forms :as forms]
            [uxbox.util.mixins :as mx :include-macros true]
            [uxbox.util.data :refer (deep-merge parse-int)]
            [uxbox.util.dom :as dom]))

(def form-data (forms/focus-data :workspace-page-form st/state))
(def set-value! (partial forms/set-value! :workspace-page-form))

;; --- Lightbox

(def +page-defaults+
  {:width 1920
   :height 1080
   :layout :desktop})

(def +page-form+
  {:name [forms/required forms/string]
   :width [forms/required forms/number]
   :height [forms/required forms/number]
   :layout [forms/required forms/string]})

(mx/defc layout-input
  [data id]
  (let [{:keys [id name width height]} (get +layouts+ id)]
    (letfn [(on-change [event]
              (set-value! :layout id)
              (set-value! :width width)
              (set-value! :height height))]
      [:div
       [:input {:type "radio"
                :id id
                :name "project-layout"
                :value id
                :checked (when (= id (:layout data)) "checked")
                :on-change on-change}]
       [:label {:value id :for id} name]])))

(mx/defc page-form
  {:mixins [mx/static mx/reactive]}
  [{:keys [metadata id] :as page}]
  (let [data (merge +page-defaults+
                    (select-keys page [:name :id])
                    (select-keys metadata [:width :height :layout])
                    (mx/react form-data))
        valid? (forms/valid? data +page-form+)]
    (letfn [(update-size [field e]
              (let [value (dom/event->value e)
                    value (parse-int value)]
                (set-value! field value)))
            (update-name [e]
              (let [value (dom/event->value e)]
                (set-value! :name value)))
            (toggle-sizes []
              (let [{:keys [width height]} data]
                (set-value! :width height)
                (set-value! :height width)))
            (on-cancel [e]
              (dom/prevent-default e)
              (udl/close!))
            (on-save [e]
              (dom/prevent-default e)
              (udl/close!)
              (if (nil? id)
                (rs/emit! (udp/create-page data))
                (rs/emit! (udp/update-page id data))))]
      [:form
       [:input#project-name.input-text
        {:placeholder "Page name"
         :type "text"
         :value (:name data "")
         :auto-focus true
         :on-change update-name}]
       [:div.project-size
        [:div.input-element.pixels
         [:input#project-witdh.input-text
          {:placeholder "Width"
           :type "number"
           :min 0
           :max 4000
           :value (:width data)
           :on-change #(update-size :width %)}]]
        [:a.toggle-layout {:on-click toggle-sizes} i/toggle]
        [:div.input-element.pixels
         [:input#project-height.input-text
          {:placeholder "Height"
           :type "number"
           :min 0
           :max 4000
           :value (:height data)
           :on-change #(update-size :height %)}]]]

       [:div.input-radio.radio-primary
        (layout-input data "mobile")
        (layout-input data "tablet")
        (layout-input data "notebook")
        (layout-input data "desktop")]

       (when valid?
         [:input#project-btn.btn-primary
          {:value "Go go go!"
           :on-click on-save
           :type "button"}])])))

(mx/defc page-form-lightbox
  {:mixins [mx/static]
   :will-unmount (fn [own]
                   (forms/clear! :workspace-page-form)
                   own)}
  [{:keys [id] :as page}]
  (letfn [(on-cancel [event]
            (dom/prevent-default event)
            (udl/close!))]
    (let [creation? (nil? id)]
      [:div.lightbox-body
       (if creation?
         [:h3 "New page"]
         [:h3 "Edit page"])
       (page-form page)
       [:a.close {:on-click on-cancel} i/close]])))

(defmethod lbx/render-lightbox :page-form
  [{:keys [page]}]
  (page-form-lightbox page))


