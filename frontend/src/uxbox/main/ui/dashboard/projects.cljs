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

(def projects-ref
  (-> (l/key :projects)
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
  :mixins #{mf/memo mf/reactive}
  :init
  (fn [own props]
    (assoc own ::num-projects (-> (comp (l/key :projects)
                                        (l/lens #(-> % vals count)))
                                  (l/derive st/state))))
  :render
  (fn [own {:keys [opts] :as props}]
    (let [ordering (:order opts :created)
          filtering (:filter opts "")
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

(mf/defc grid-item-thumbnail
  [{:keys [project] :as props}]
  (let [url (mf/use-state nil)]
    (mf/use-effect
     {:deps #js [(:page-id project)]
      :init (fn []
              (when-let [page-id (:page-id project)]
                (let [svg (exports/render-page page-id)
                      uri (some-> svg
                                  (blob/create "image/svg+xml")
                                  (blob/create-uri))]
                  (reset! url uri)
                  uri)))
      :end #(when % (blob/revoke-uri %))})
    (if @url
      [:div.grid-item-th
       {:style {:background-image (str "url('" @url "')")}}]
      [:div.grid-item-th
       [:img.img-th {:src "/images/project-placeholder.svg"
                     :alt "Project title"}]])))



;; --- Grid Item

(mf/defc grid-item
  {:wrap [mf/wrap-memo]}
  [{:keys [project] :as props}]
  (let [local (mf/use-state {})
        on-navigate #(st/emit! (udp/go-to (:id project)))
        delete #(st/emit! (udp/delete-project project))
        on-delete #(do
                     (dom/stop-propagation %)
                     (udl/open! :confirm {:on-accept delete}))
        on-blur #(let [target (dom/event->target %)
                       name (dom/get-value target)
                       id (:id project)]
                   (swap! local assoc :edition false)
                   (st/emit! (udp/rename-project id name)))
        on-key-down #(when (kbd/enter? %) (on-blur %))
        on-edit #(do
                   (dom/stop-propagation %)
                   (dom/prevent-default %)
                   (swap! local assoc :edition true))]
    [:div.grid-item.project-th {:on-click on-navigate}
     [:& grid-item-thumbnail {:project project :key (select-keys project [:id :page-id])}]
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

(mf/defc grid
  [{:keys [opts] :as props}]
  (let [order (:order opts :created)
        filter (:filter opts "")
        projects (mf/deref projects-ref)
        projects (->> (vals projects)
                      (filter-projects-by filter)
                      (sort-projects-by order))
        on-click #(do
                    (dom/prevent-default %)
                    (udl/open! :create-project))]
    [:section.dashboard-grid
     [:h2 (tr "ds.project-title")]
     [:div.dashboard-grid-content
      [:div.dashboard-grid-row
       [:div.grid-item.add-project {:on-click on-click}
        [:span (tr "ds.project-new")]]
       (for [item projects]
         [:& grid-item {:project item :key (:id item)}])]]]))

;; --- Projects Page

(mf/defc projects-page
  [_]
  (mf/use-effect
   {:init #(st/emit! (udp/initialize))})
  (let [opts (mf/deref opts-ref)]
    [:section.dashboard-content
     [:& menu {:opts opts}]
     [:& grid {:opts opts}]]))
