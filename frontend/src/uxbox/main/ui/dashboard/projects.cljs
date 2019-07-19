;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) 2015-2017 Andrey Antukh <niwi@niwi.nz>
;; Copyright (c) 2015-2017 Juan de la Cruz <delacruzgarciajuan@gmail.com>

(ns uxbox.main.ui.dashboard.projects
  (:require
   [cuerdas.core :as str]
   [lentes.core :as l]
   [rumext.core :as mx]
   [rumext.alpha :as mf]
   [uxbox.builtins.icons :as i]
   [uxbox.main.constants :as c]
   [uxbox.main.data.lightbox :as udl]
   [uxbox.main.data.projects :as udp]
   [uxbox.main.exports :as exports]
   [uxbox.main.store :as st]
   [uxbox.main.ui.dashboard.projects-createform]
   [uxbox.main.ui.keyboard :as kbd]
   [uxbox.util.blob :as blob]
   [uxbox.util.data :refer [read-string]]
   [uxbox.util.dom :as dom]
   [uxbox.util.i18n :as t :refer (tr)]
   [uxbox.util.router :as r]
   [uxbox.util.time :as dt]))

;; --- Helpers & Constants

(def +ordering-options+
  {:name "ds.ordering.by-name"
   :created "ds.ordering.by-creation-date"})

;; --- Refs

(def opts-ref
  (-> (l/in [:dashboard :projects])
      (l/derive st/state)))

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

(mf/def menu
  :mixins #{mf/static mf/reactive}
  :init
  (fn [own props]
    (assoc own ::num-projects (-> (comp (l/key :projects)
                                        (l/lens #(-> % vals count)))
                                  (l/derive st/state))))
  :render
  (fn [own props]
    (let [ordering (:order props :created)
          filtering (:filter props "")
          num-projects (mf/react (::num-projects own))]
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
          [:span.dashboard-images (tr "ds.num-projects" (t/c num-projects))]

          ;; Sorting
          [:div
           [:span (tr "ds.ordering")]
           [:select.input-select
            {:on-change on-ordering-change
             :value (pr-str ordering)}
            (for [[key value] (seq +ordering-options+)]
              (let [key (pr-str key)]
                [:option {:key key :value key} (tr value)]))]]
          ;; Search
          [:form.dashboard-search
           [:input.input-text
            {:key :images-search-box
             :type "text"
             :on-change on-term-change
             :auto-focus true
             :placeholder (tr "ds.search.placeholder")
             :value (or filtering "")}]
           [:div.clear-search {:on-click on-clear} i/close]]]]))))

;; --- Grid Item Thumbnail

(mf/def grid-item-thumbnail
  :mixins #{mf/static}

  :init
  (fn [own project]
    (let [svg (exports/render-page (:page-id project))
          url (some-> svg
                      (blob/create "image/svg+xml")
                      (blob/create-uri))]
      (assoc own ::url url)))

  :will-unmount
  (fn [own]
    (let [url (::url own)]
      (when url (blob/revoke-uri url))
      own))

  :render
  (fn [own project]
    (if-let [url (::url own)]
      [:div.grid-item-th
       {:style {:background-image (str "url('" url "')")}}]
      [:div.grid-item-th
       [:img.img-th {:src "/images/project-placeholder.svg"
                     :alt "Project title"}]])))

;; --- Grid Item

(mf/def grid-item
  :key-fn :id
  :mixins #{mf/static (mf/local)}

  :render
  (fn [{:keys [::mf/local] :as own} project]
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
         i/trash]]])))

;; --- Grid

(mf/def grid
  :mixins #{mf/static mf/reactive}

  :init
  (fn [own props]
    (assoc own ::projects (-> (l/key :projects)
                              (l/derive st/state))))

  :render
  (fn [own props]
    (let [ordering (:order props :created)
          filtering (:filter props "")
          projects (->> (vals (mf/react (::projects own)))
                        (filter-projects-by filtering)
                        (sort-projects-by ordering))]
      [:section.dashboard-grid
       [:h2 (tr "ds.project-title")]
       [:div.dashboard-grid-content
        [:div.dashboard-grid-row
         [:div.grid-item.add-project {:on-click (fn [e]
                                                  (dom/prevent-default e)
                                                  (udl/open! :create-project))}
          [:span (tr "ds.project-new")]]
         (for [item projects]
           (grid-item item))]]])))

;; --- Projects Page

(mf/def projects-page
  :mixins [mf/static mf/reactive]

  :init
  (fn [own props]
    (st/emit! (udp/initialize))
    own)

  :render
  (fn [own props]
    (let [opts (mf/react opts-ref)
          props (merge opts props)]
      [:section.dashboard-content
       (menu props)
       (grid props)])))

