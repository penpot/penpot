;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) 2015-2019 Andrey Antukh <niwi@niwi.nz>
;; Copyright (c) 2015-2019 Juan de la Cruz <delacruzgarciajuan@gmail.com>

(ns uxbox.main.ui.workspace.sidebar.sitemap-forms
  (:require
   [rumext.alpha :as mf]
   [cljs.spec.alpha :as s]
   [uxbox.builtins.icons :as i]
   [uxbox.main.constants :as c]
   [uxbox.main.data.pages :as udp]
   [uxbox.main.store :as st]
   [uxbox.main.ui.modal :as modal]
   [uxbox.util.dom :as dom]
   [uxbox.util.spec :as us]
   [uxbox.util.forms :as fm]
   [uxbox.util.i18n :refer [tr]]))

(s/def ::id ::us/uuid)
(s/def ::project ::us/uuid)
(s/def ::name ::us/not-empty-string)
(s/def ::width ::us/number-str)
(s/def ::height ::us/number-str)

(s/def ::page-form
  (s/keys :req-un [::id
                   ::project
                   ::name
                   ::width
                   ::height]))

(def defaults
  {:name ""
   :width "1366"
   :height "768"})

(defn- on-submit
  [event form]
  (dom/prevent-default event)
  (modal/hide!)
  (let [data (:clean-data form)]
    (if (nil? (:id data))
      (st/emit! (udp/form->create-page data))
      (st/emit! (udp/form->update-page data)))))

(defn- swap-size
  [event {:keys [data] :as form}]
  (swap! data assoc
         :width (:height data)
         :height (:width data)))

(defn- initial-data
  [page]
  (merge {:name "" :width "1366" :height "768"}
         (select-keys page [:name :id :project])
         (select-keys (:metadata page) [:width :height])))

(mf/defc page-form
  [{:keys [page] :as props}]
  (let [{:keys [data] :as form} (fm/use-form ::page-form #(initial-data page))]
    [:form {:on-submit #(on-submit % form)}
     [:input.input-text
      {:placeholder "Page name"
       :type "text"
       :name "name"
       :class (fm/error-class form :name)
       :on-blur (fm/on-input-blur form :name)
       :on-change (fm/on-input-change form :name)
       :value (:name data)
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
         :name "height"
         :type "number"
         :min 0
         :max 5000
         :class (fm/error-class form :height)
         :on-blur (fm/on-input-blur form :height)
         :on-change (fm/on-input-change form :height)
         :value (:height data)}]]]
     [:input.btn-primary
      {:value "Go go go!"
       :type "submit"
       :class (when-not (:valid form) "btn-disabled")
       :disabled (not (:valid form))}]]))

(mf/defc page-form-dialog
  [{:keys [page] :as props}]
  [:div.lightbox-body
   (if (nil? (:id page))
     [:h3 "New page"]
     [:h3 "Edit page"])
   [:& page-form {:page page}]
   [:a.close {:on-click modal/hide!} i/close]])

