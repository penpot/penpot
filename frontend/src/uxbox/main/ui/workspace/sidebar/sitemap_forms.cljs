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
   [uxbox.main.data.workspace :as udw]
   [uxbox.main.store :as st]
   [uxbox.main.ui.modal :as modal]
   [uxbox.util.dom :as dom]
   [uxbox.util.spec :as us]
   [uxbox.util.forms :as fm]
   [uxbox.util.i18n :refer [tr]]))

(s/def ::id ::us/uuid)
(s/def ::project-id ::us/uuid)
(s/def ::name ::us/not-empty-string)

(s/def ::page-form
  (s/keys :req-un [::project-id ::name]
          :opt-un [::id]))

(defn- on-submit
  [event form]
  (dom/prevent-default event)
  (modal/hide!)
  (let [data (:clean-data form)]
    (if (nil? (:id data))
      (st/emit! (udp/create-page data))
      (st/emit! (udp/rename-page data)))))

(defn- initial-data
  [page]
  (merge {:name ""}
         (select-keys page [:name :id :project-id])))

(mf/defc page-form
  [{:keys [page] :as props}]
  (let [{:keys [data] :as form} (fm/use-form ::page-form #(initial-data page))]
    [:form {:on-submit #(on-submit % form)}
     [:input.input-text
      {:placeholder (tr "ds.page.placeholder")
       :type "text"
       :name "name"
       :class (fm/error-class form :name)
       :on-blur (fm/on-input-blur form :name)
       :on-change (fm/on-input-change form :name)
       :value (:name data)
       :auto-focus true}]
     [:input.btn-primary
      {:value (tr "ds.go")
       :type "submit"
       :class (when-not (:valid form) "btn-disabled")
       :disabled (not (:valid form))}]]))

(mf/defc page-form-dialog
  [{:keys [page] :as props}]
  [:div.lightbox-body
   (if (nil? (:id page))
     [:h3 (tr "ds.page.new")]
     [:h3 (tr "ds.page.edit")])
   [:& page-form {:page page}]
   [:a.close {:on-click modal/hide!} i/close]])

