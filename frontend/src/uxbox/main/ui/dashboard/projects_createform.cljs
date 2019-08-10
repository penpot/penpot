;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) 2015-2017 Andrey Antukh <niwi@niwi.nz>
;; Copyright (c) 2015-2017 Juan de la Cruz <delacruzgarciajuan@gmail.com>

(ns uxbox.main.ui.dashboard.projects-createform
  (:require
   [cljs.spec.alpha :as s]
   [cuerdas.core :as str]
   [lentes.core :as l]
   [rumext.core :as mx]
   [rumext.alpha :as mf]
   [uxbox.builtins.icons :as i]
   [uxbox.main.constants :as c]
   [uxbox.main.data.lightbox :as udl]
   [uxbox.main.data.projects :as udp]
   [uxbox.main.store :as st]
   [uxbox.main.ui.lightbox :as lbx]
   [uxbox.util.data :refer [read-string parse-int]]
   [uxbox.util.dom :as dom]
   [uxbox.util.forms :as fm]
   [uxbox.util.i18n :as t :refer [tr]]
   [uxbox.util.router :as r]
   [uxbox.util.time :as dt]))

(def form-data (fm/focus-data :create-project st/state))
(def form-errors (fm/focus-errors :create-project st/state))
(def assoc-value (partial fm/assoc-value :create-project))
(def clear-form (partial fm/clear-form :create-project))

(s/def ::name ::fm/non-empty-string)
(s/def ::layout ::fm/non-empty-string)
(s/def ::width number?)
(s/def ::height number?)

(s/def ::project-form
  (s/keys :req-un [::name
                   ::width
                   ::height
                   ::layout]))

;; --- Create Project Form

(mx/defc layout-input
  [{:keys [::layout-id] :as data}]
  (let [layout (get c/page-layouts layout-id)]
    [:div
     [:input {:type "radio"
              :key layout-id
              :id layout-id
              :name "project-layout"
              :value (:name layout)
              :checked (= layout-id (:layout data))
              :on-change #(st/emit! (assoc-value :layout layout-id)
                                    (assoc-value :width (:width layout))
                                    (assoc-value :height (:height layout)))}]
     [:label {:value (:name layout)
              :for layout-id}
      (:name layout)]]))

(mx/defc layout-selector
  [props]
  [:div.input-radio.radio-primary
   (layout-input (assoc props ::layout-id "mobile"))
   (layout-input (assoc props ::layout-id "tablet"))
   (layout-input (assoc props ::layout-id "notebook"))
   (layout-input (assoc props ::layout-id "desktop"))])

(mf/def create-project-form
  :mixins #{mf/reactive}
  :render
  (fn [own props]
    (let [data (merge c/project-defaults (mf/react form-data))
          valid? (fm/valid? ::project-form data)]
      (letfn [(on-submit [event]
                (dom/prevent-default event)
                (when valid?
                  (st/emit! (udp/create-project data)
                            (udl/close-lightbox))))

              (update-size [field e]
                (let [value (dom/event->value e)
                      value (parse-int value)]
                  (st/emit! (assoc-value field value))))

              (update-name [e]
                (let [value (dom/event->value e)]
                  (st/emit! (assoc-value :name value))))
              (swap-size []
                (st/emit! (assoc-value :width (:height data))
                          (assoc-value :height (:width data))))]
        [:form {:on-submit on-submit}
         [:input#project-name.input-text
          {:placeholder (tr "ds.project.placeholder")
           :type "text"
           :value (:name data)
           :auto-focus true
           :on-change update-name}]
         [:div.project-size
          [:div.input-element.pixels
           [:span (tr "ds.width")]
           [:input#project-witdh.input-text
            {:placeholder (tr "ds.width")
             :type "number"
             :min 0 ;;TODO check this value
             :max 666666 ;;TODO check this value
             :value (str (:width data))
             :on-change (partial update-size :width)}]]
          [:a.toggle-layout {:on-click swap-size} i/toggle]
          [:div.input-element.pixels
           [:span (tr "ds.height")]
           [:input#project-height.input-text
            {:placeholder (tr "ds.height")
             :type "number"
             :min 0 ;;TODO check this value
             :max 666666 ;;TODO check this value
             :value (str (:height data))
             :on-change (partial update-size :height)}]]]

         ;; Layout selector
         (layout-selector data)

         ;; Submit
         [:input#project-btn.btn-primary
          {:value (tr "ds.go")
           :class (when-not valid? "btn-disabled")
           :disabled (not valid?)
           :type "submit"}]]))))

;; --- Create Project Lightbox

(mf/def create-project-lightbox
  :will-unmount
  (fn [own]
    (st/emit! (fm/clear-form :create-project))
    own)

  :render
  (fn [own]
    [:div.lightbox-body
     [:h3 (tr "ds.project.new")]
     (create-project-form)
     [:a.close {:on-click #(st/emit! (udl/close-lightbox))}
      i/close]]))

(defmethod lbx/render-lightbox :create-project
  [_]
  (create-project-lightbox))

