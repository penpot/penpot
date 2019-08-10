;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) 2015-2016 Andrey Antukh <niwi@niwi.nz>
;; Copyright (c) 2015-2016 Juan de la Cruz <delacruzgarciajuan@gmail.com>

(ns uxbox.main.ui.workspace.sidebar.sitemap-pageform
  (:require [cljs.spec.alpha :as s :include-macros true]
            [lentes.core :as l]
            [uxbox.builtins.icons :as i]
            [uxbox.main.store :as st]
            [uxbox.main.constants :as c]
            [uxbox.main.data.pages :as udp]
            [uxbox.main.data.lightbox :as udl]
            [uxbox.main.ui.lightbox :as lbx]
            [uxbox.util.data :refer [parse-int]]
            [uxbox.util.dom :as dom]
            [uxbox.util.forms :as fm]
            [uxbox.util.i18n :refer [tr]]
            [uxbox.util.router :as r]
            [rumext.core :as mx :include-macros true]))


(def form-data (fm/focus-data :workspace-page-form st/state))
(def form-errors (fm/focus-errors :workspace-page-form st/state))

(def assoc-value (partial fm/assoc-value :workspace-page-form))
(def assoc-error (partial fm/assoc-error :workspace-page-form))
(def clear-form (partial fm/clear-form :workspace-page-form))

;; --- Lightbox

(s/def ::name ::fm/non-empty-string)
(s/def ::layout ::fm/non-empty-string)
(s/def ::width number?)
(s/def ::height number?)

(s/def ::page-form
  (s/keys :req-un [::name
                   ::width
                   ::height
                   ::layout]))

(mx/defc layout-input
  [data id]
  (let [{:keys [id name width height]} (get c/page-layouts id)]
    (letfn [(on-change [event]
              (st/emit! (assoc-value :layout id)
                        (assoc-value :width width)
                        (assoc-value :height height)))]
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
  (let [data (merge c/page-defaults
                    (select-keys page [:name :id :project])
                    (select-keys metadata [:width :height :layout])
                    (mx/react form-data))
        valid? (fm/valid? ::page-form data)]
    (letfn [(update-size [field e]
              (let [value (dom/event->value e)
                    value (parse-int value)]
                (st/emit! (assoc-value field value))))
            (update-name [e]
              (let [value (dom/event->value e)]
                (st/emit! (assoc-value :name value))))
            (toggle-sizes []
              (let [{:keys [width height]} data]
                (st/emit! (assoc-value :width width)
                          (assoc-value :height height))))
            (on-cancel [e]
              (dom/prevent-default e)
              (udl/close!))
            (on-save [e]
              (dom/prevent-default e)
              (udl/close!)
              (if (nil? id)
                (st/emit! (udp/create-page data))
                (st/emit! (udp/persist-page-update-form id data))))]
      [:form
       [:input#project-name.input-text
        {:placeholder (tr "ds.page.placeholder")
         :type "text"
         :value (:name data "")
         :auto-focus true
         :on-change update-name}]
       [:div.project-size
        [:div.input-element.pixels
         [:span (tr "ds.width")]
         [:input#project-witdh.input-text
          {:placeholder (tr "ds.width")
           :type "number"
           :min 0
           :max 4000
           :value (:width data)
           :on-change #(update-size :width %)}]]
        [:a.toggle-layout {:on-click toggle-sizes} i/toggle]
        [:div.input-element.pixels
         [:span (tr "ds.height")]
         [:input#project-height.input-text
           {:placeholder (tr "ds.height")
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

       [:input#project-btn.btn-primary
        {:value (tr "ds.go")
         :disabled (not valid?)
         :on-click on-save
         :type "button"}]])))

(mx/defc page-form-lightbox
  {:mixins [mx/static (fm/clear-mixin st/store :workspace-page-form)]}
  [{:keys [id] :as page}]
  (letfn [(on-cancel [event]
            (dom/prevent-default event)
            (udl/close!))]
    (let [creation? (nil? id)]
      [:div.lightbox-body
       (if creation?
         [:h3 (tr "ds.page.new")]
         [:h3 (tr "ds.page.edit")])
       (page-form page)
       [:a.close {:on-click on-cancel} i/close]])))

(defmethod lbx/render-lightbox :page-form
  [{:keys [page]}]
  (page-form-lightbox page))
