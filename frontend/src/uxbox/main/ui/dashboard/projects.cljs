;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) 2015-2017 Andrey Antukh <niwi@niwi.nz>
;; Copyright (c) 2015-2017 Juan de la Cruz <delacruzgarciajuan@gmail.com>

(ns uxbox.main.ui.dashboard.projects
  (:require [lentes.core :as l]
            [cuerdas.core :as str]
            [uxbox.main.store :as st]
            [uxbox.main.constants :as c]
            [uxbox.main.data.projects :as udp]
            [uxbox.main.data.lightbox :as udl]
            [uxbox.main.ui.icons :as i]
            [uxbox.main.ui.dashboard.header :refer [header]]
            [uxbox.main.ui.dashboard.projects-createlightbox]
            [uxbox.main.ui.lightbox :as lbx]
            [uxbox.main.ui.messages :refer [messages-widget]]
            [uxbox.main.ui.keyboard :as kbd]
            [uxbox.main.exports :as exports]
            [uxbox.util.i18n :as t :refer (tr)]
            [uxbox.util.router :as r]
            [potok.core :as ptk]
            [uxbox.util.data :refer [read-string]]
            [uxbox.util.dom :as dom]
            [uxbox.util.blob :as blob]
            [uxbox.util.mixins :as mx :include-macros true]
            [uxbox.util.time :as dt]))

;; --- Helpers & Constants

(def +ordering-options+
  {:name "ds.project-ordering.by-name"
   :created "ds.project-ordering.by-creation-date"})

;; --- Refs

(def projects-map-ref
  (-> (l/key :projects)
      (l/derive st/state)))

(def dashboard-ref
  (-> (l/in [:dashboard :projects])
      (l/derive st/state)))

(def project-ordering-ref
  (-> (l/key :project-order)
      (l/derive dashboard-ref)))

(def project-filtering-ref
  (-> (l/in [:project-filter])
      (l/derive dashboard-ref)))

;; --- Helpers

(defn sort-projects-by
  [ordering projs]
  (case ordering
    :name (sort-by :name projs)
    :created (reverse (sort-by :created-at projs))
    projs))

(defn contains-term?
  [phrase term]
  (let [term (name term)]
    (str/includes? (str/lower phrase) (str/trim (str/lower term)))))

(defn filter-projects-by
  [term projs]
  (if (str/blank? term)
    projs
    (filter #(contains-term? (:name %) term) projs)))

;; --- Menu (Filter & Sort)

(mx/defc menu
  {:mixins [mx/static]}
  [state projects]
  (let [ordering (:order state :created)
        filtering (:filter state "")
        count (count projects)]
    (letfn [(on-term-change [event]
              (let [term (-> (dom/get-target event)
                             (dom/get-value))]
                (st/emit! (udp/update-opts :filter term))))
            (on-ordering-change [event]
              (let [value (dom/event->value event)
                    value (read-string value)]
                (st/emit! (udp/update-opts :order value))))
            (on-clear [event]
              (st/emit! (udp/update-opts :filter "")))]
      [:section.dashboard-bar
       [:div.dashboard-info

        ;; Counter
        [:span.dashboard-images (tr "ds.num-projects" (t/c count))]

        ;; Sorting
        [:div
         [:span (tr "ds.project-ordering")]
         [:select.input-select
          {:on-change on-ordering-change
           :value (pr-str ordering)}
          (for [[key value] (seq +ordering-options+)
                :let [ovalue (pr-str key)
                      olabel (tr value)]]
            [:option {:key ovalue :value ovalue} olabel])]]
        ;; Search
        [:form.dashboard-search
         [:input.input-text
          {:key :images-search-box
           :type "text"
           :on-change on-term-change
           :auto-focus true
           :placeholder (tr "ds.project-search.placeholder")
           :value (or filtering "")}]
         [:div.clear-search {:on-click on-clear} i/close]]]])))

;; --- Grid Item Thumbnail

(defn- grid-item-thumbnail-will-mount
  [own]
  (let [[project] (:rum/args own)
        svg (exports/render-page* (:page-id project))
        url (some-> svg
                    (blob/create "image/svg+xml")
                    (blob/create-uri))]
    (assoc own ::url url)))

(defn- grid-item-thumbnail-will-unmount
  [own]
  (let [url (::url own)]
    (when url (blob/revoke-uri url))
    own))

(mx/defcs grid-item-thumbnail
  {:mixins [mx/static]
   :will-mount grid-item-thumbnail-will-mount
   :will-unmount grid-item-thumbnail-will-unmount}
  [own project]
  (if-let [url (::url own)]
    [:div.grid-item-th
     {:style {:background-image (str "url('" url "')")}}]
    [:div.grid-item-th
     {:style {:background-image "url('/images/project-placeholder.svg')"}}]))

;; --- Grid Item

(mx/defcs grid-item
  {:mixins [mx/static (mx/local)]}
  [{:keys [rum/local] :as own} project]
  (letfn [(on-navigate [event]
            (st/emit! (udp/go-to (:id project))))
          (delete []
            (st/emit! (udp/delete-project project)))
          (on-delete [event]
            (dom/stop-propagation event)
            (udl/open! :confirm {:on-accept delete}))
          (on-key-down [event]
            (when (kbd/enter? event)
              (on-blur event)))
          (on-blur [event]
            (let [target (dom/event->target event)
                  name (dom/get-value target)
                  id (:id project)]
              (swap! local assoc :edition false)
              (st/emit! (udp/rename-project id name))))
          (on-edit [event]
            (dom/stop-propagation event)
            (dom/prevent-default event)
            (swap! local assoc :edition true))]
    [:div.grid-item.project-th {:on-click on-navigate}
     (grid-item-thumbnail project)
     [:div.item-info
      (if (:edition @local)
        [:input.element-name {:type "text"
                 :auto-focus true
                 :on-key-down on-key-down
                 :on-blur on-blur
                 :on-click on-edit
                 :default-value (:name project)}]
        [:h3 (:name project)])
      [:span.date
       (str "Updated " (dt/timeago (:modified-at project)))]]
     [:div.project-th-actions
      [:div.project-th-icon.pages
       i/page
       [:span (:total-pages project)]]
      #_[:div.project-th-icon.comments
         i/chat
         [:span "0"]]
      [:div.project-th-icon.edit
       {:on-click on-edit}
       i/pencil]
      [:div.project-th-icon.delete
       {:on-click on-delete}
       i/trash]]]))

;; --- Grid

(mx/defc grid
  {:mixins [mx/static]}
  [state projects]
  (let [ordering (:order state :created)
        filtering (:filter state "")
        projects (->> (vals projects)
                      (filter-projects-by filtering)
                      (sort-projects-by ordering))]
    (letfn [(on-click [e]
              (dom/prevent-default e)
              (udl/open! :new-project))]
      [:section.dashboard-grid
       [:h2 "Your projects"]
       [:div.dashboard-grid-content
        [:div.dashboard-grid-row
         [:div.grid-item.add-project
          {:on-click on-click}
          [:span "+ New project"]]
         (for [item projects]
           (-> (grid-item item)
               (mx/with-key (:id item))))]]])))

;; --- Projects Page

(defn projects-page-will-mount
  [own]
  (st/emit! (udp/initialize))
  own)

(defn projects-page-did-remount
  [old-own own]
  (st/emit! (udp/initialize))
  own)

(mx/defc projects-page
  {:will-mount projects-page-will-mount
   :did-remount projects-page-did-remount
   :mixins [mx/static mx/reactive]}
  []
  (let [state (mx/react dashboard-ref)
        projects-map (mx/react projects-map-ref)]
    [:main.dashboard-main
     (messages-widget)
     (header)
     [:section.dashboard-content
      (menu state projects-map)
      (grid state projects-map)]]))

