;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) 2015-2016 Andrey Antukh <niwi@niwi.nz>
;; Copyright (c) 2015-2016 Juan de la Cruz <delacruzgarciajuan@gmail.com>

(ns uxbox.main.ui.dashboard.projects
  (:require [lentes.core :as l]
            [cuerdas.core :as str]
            [uxbox.main.state :as st]
            [uxbox.main.data.projects :as dp]
            [uxbox.main.data.lightbox :as udl]
            [uxbox.main.ui.icons :as i]
            [uxbox.main.ui.dashboard.header :refer (header)]
            [uxbox.main.ui.lightbox :as lbx]
            [uxbox.main.ui.messages :as uum]
            [uxbox.util.i18n :as t :refer (tr)]
            [uxbox.util.router :as r]
            [uxbox.util.rstore :as rs]
            [uxbox.util.data :refer (read-string)]
            [uxbox.util.dom :as dom]
            [uxbox.util.mixins :as mx :include-macros true]
            [uxbox.util.datetime :as dt]))

;; --- Helpers & Constants

(def ^:private +project-defaults+
  {:name ""
   :width 1920
   :height 1080
   :layout "desktop"})

(def +layouts+
  {"mobile"
   {:name "Mobile"
    :id "mobile"
    :width 320
    :height 480}
   "tablet"
   {:name "Tablet"
    :id "tablet"
    :width 1024
    :height 768}
   "notebook"
   {:name "Notebook"
    :id "notebook"
    :width 1366
    :height 768}
   "desktop"
   {:name "Desktop"
    :id "desktop"
    :width 1920
    :height 1080}})

(def +ordering-options+
  {:name "ds.project-ordering.by-name"
   :created "ds.project-ordering.by-creation-date"})

;; --- Refs

(def projects-map-ref
  (-> (l/key :projects-by-id)
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
  (let [ordering (:order state :name)
        filtering (:filter state "")
        count (count projects)]
    (letfn [(on-term-change [event]
              (let [term (-> (dom/get-target event)
                             (dom/get-value))]
                (rs/emit! (dp/update-opts :filter term))))
            (on-ordering-change [event]
              (let [value (dom/event->value event)
                    value (read-string value)]
                (rs/emit! (dp/update-opts :order value))))
            (on-clear [event]
              (rs/emit! (dp/update-opts :filter "")))]
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

;; --- Grid Item

(mx/defc  grid-item
  {:mixins [mx/static]}
  [project]
  (letfn [(on-navigate [event]
            (rs/emit! (dp/go-to (:id project))))
          (delete []
            (rs/emit! (dp/delete-project project)))
          (on-delete [event]
            (dom/stop-propagation event)
            (udl/open! :confirm {:on-accept delete}))]
    [:div.grid-item.project-th {:on-click on-navigate}
     [:h3 (:name project)]
     [:span.project-th-update
      (str "Updated " (dt/timeago (:modified-at project)))]
     [:div.project-th-actions
      [:div.project-th-icon.pages
       i/page
       [:span (:total-pages project)]]
      #_[:div.project-th-icon.comments
         i/chat
        [:span "0"]]
      #_[:div.project-th-icon.edit
         i/pencil]
      [:div.project-th-icon.delete
       {:on-click on-delete}
       i/trash]]]))

;; --- Grid

(mx/defc grid
  {:mixins [mx/static]}
  [state projects]
  {:pre [(map? projects)]}
  (let [ordering (:order state :name)
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
  (rs/emit! (dp/initialize))
  own)

(defn projects-page-did-remount
  [old-own own]
  (rs/emit! (dp/initialize))
  own)

(mx/defc projects-page
  {:will-mount projects-page-will-mount
   :did-remount projects-page-did-remount
   :mixins [mx/static mx/reactive]}
  []
  (let [state (mx/react dashboard-ref)
        projects-map (mx/react projects-map-ref)]
    [:main.dashboard-main
     (uum/messages)
     (header)
     [:section.dashboard-content
      (menu state projects-map)
      (grid state projects-map)]]))

;; --- Lightbox: Layout input

(mx/defc layout-input
  [local layout-id]
  (let [layout (get-in +layouts+ [layout-id])
        id (:id layout)
        name (:name layout)
        width (:width layout)
        height (:height layout)]
    [:div
     [:input
      {:type "radio"
       :key id
       :id id
       :name "project-layout"
       :value name
       :checked (= layout-id (:layout @local))
       :on-change #(swap! local merge {:layout layout-id
                                       :width width
                                       :height height})}]
     [:label {:value (:name @local) :for id} name]]))

;; --- Lightbox: Layout selector

(mx/defc layout-selector
  [local]
  [:div.input-radio.radio-primary
   (layout-input local "mobile")
   (layout-input local "tablet")
   (layout-input local "notebook")
   (layout-input local "desktop")])

;; -- New Project Lightbox

(mx/defcs new-project-lightbox
  {:mixins [(mx/local +project-defaults+)]}
  [own]
  (let [local (:rum/local own)
        name (:name @local)
        width (:width @local)
        height (:height @local)]
    [:div.lightbox-body
     [:h3 "New project"]
     [:form {:on-submit (constantly nil)}
      [:input#project-name.input-text
        {:placeholder "New project name"
         :type "text"
         :value name
         :auto-focus true
         :on-change #(swap! local assoc :name (.-value (.-target %)))}]
      [:div.project-size
       [:input#project-witdh.input-text
        {:placeholder "Width"
         :type "number"
         :min 0 ;;TODO check this value
         :max 666666 ;;TODO check this value
         :value width
         :on-change #(swap! local assoc :width (.-value (.-target %)))}]
       [:a.toggle-layout
        {:href "#"
         :on-click #(swap! local assoc :width width :height height)}
        i/toggle]
       [:input#project-height.input-text
        {:placeholder "Height"
         :type "number"
         :min 0 ;;TODO check this value
         :max 666666 ;;TODO check this value
         :value height
         :on-change #(swap! local assoc :height (.-value (.-target %)))}]]
      ;; Layout selector
      (layout-selector local)
      ;; Submit
      (when-not (empty? (str/trim name))
        [:input#project-btn.btn-primary
         {:value "Go go go!"
          :on-click #(do
                       (dom/prevent-default %)
                       (rs/emit! (dp/create-project @local))
                       (udl/close!))

          :type "submit"}])]
     [:a.close {:href "#"
                :on-click #(do (dom/prevent-default %)
                               (udl/close!))}
      i/close]]))

(defmethod lbx/render-lightbox :new-project
  [_]
  (new-project-lightbox))
