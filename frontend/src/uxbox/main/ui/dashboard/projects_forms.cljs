;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) 2015-2019 Andrey Antukh <niwi@niwi.nz>
;; Copyright (c) 2015-2019 Juan de la Cruz <delacruzgarciajuan@gmail.com>

(ns uxbox.main.ui.dashboard.projects-forms
  (:require
   [cljs.spec.alpha :as s]
   [rumext.alpha :as mf]
   [uxbox.builtins.icons :as i]
   [uxbox.main.data.projects :as udp]
   [uxbox.main.store :as st]
   [uxbox.main.ui.modal :as modal]
   [uxbox.util.dom :as dom]
   [uxbox.util.forms :as fm]
   [uxbox.util.i18n :as t :refer [tr]]))

(s/def ::name ::fm/not-empty-string)
(s/def ::width ::fm/number-str)
(s/def ::height ::fm/number-str)

(s/def ::project-form
  (s/keys :req-un [::name ::width ::height]))

(def defaults
  {:name ""
   :width "1366"
   :height "768"})

;; --- Create Project Form

(defn- on-submit
  [event form]
  (dom/prevent-default event)
  (let [data (:clean-data form)]
    (st/emit! (udp/create-project data))
    (modal/hide!)))

(defn- swap-size
  [event {:keys [data] :as form}]
  (swap! data assoc
         :width (:height data)
         :height (:width data)))

(mf/defc create-project-form
  [props]
  (let [{:keys [data] :as form} (fm/use-form ::project-form defaults)]
    [:form {:on-submit #(on-submit % form)}
     [:input.input-text
      {:placeholder "New project name"
       :type "text"
       :name "name"
       :value (:name data)
       :class (fm/error-class form :name)
       :on-blur (fm/on-input-blur form :name)
       :on-change (fm/on-input-change form :name)
       :auto-focus true}]
     [:div.project-size
      [:div.input-element.pixels
       [:span "Width"]
       [:input#project-witdh.input-text
        {:placeholder "Width"
         :name "width"
         :type "number"
         :min 0
         :max 5000
         :class (fm/error-class form :width)
         :on-blur (fm/on-input-blur form :width)
         :on-change (fm/on-input-change form :width)
         :value (:width data)}]]
      [:a.toggle-layout {:on-click #(swap-size % form)} i/toggle]
      [:div.input-element.pixels
       [:span "Height"]
       [:input#project-height.input-text
        {:placeholder "Height"
         :type "number"
         :name "height"
         :min 0
         :max 5000
         :class (fm/error-class form :height)
         :on-blur (fm/on-input-blur form :height)
         :on-change (fm/on-input-change form :height)
         :value (:height data)}]]]

     ;; Submit
     [:input#project-btn.btn-primary
      {:value "Go go go!"
       :class (when-not (:valid form) "btn-disabled")
       :disabled (not (:valid form))
       :type "submit"}]]))

;; --- Create Project Lightbox

(mf/defc create-project-dialog
  [props]
  [:div.lightbox-body
   [:h3 "New project"]
   [:& create-project-form]
   [:a.close {:on-click modal/hide!} i/close]])

