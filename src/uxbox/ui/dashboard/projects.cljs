;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) 2015-2016 Andrey Antukh <niwi@niwi.nz>
;; Copyright (c) 2015-2016 Juan de la Cruz <delacruzgarciajuan@gmail.com>

(ns uxbox.ui.dashboard.projects
  (:require [sablono.core :as html :refer-macros [html]]
            [rum.core :as rum]
            [lentes.core :as l]
            [cuerdas.core :as str]
            [uxbox.locales :as t :refer (tr)]
            [uxbox.router :as r]
            [uxbox.rstore :as rs]
            [uxbox.state :as s]
            [uxbox.data.dashboard :as dd]
            [uxbox.data.projects :as dp]
            [uxbox.data.workspace :as dw]
            [uxbox.data.lightbox :as udl]
            [uxbox.ui.icons :as i]
            [uxbox.util.dom :as dom]
            [uxbox.ui.dashboard.header :refer (header)]
            [uxbox.ui.lightbox :as lbx]
            [uxbox.ui.messages :as uum]
            [uxbox.ui.mixins :as mx]
            [uxbox.util.datetime :as dt]))

;; --- Helpers & Constants

(def ^:const ^:private
  +project-defaults+ {:name ""
                      :width 1920
                      :height 1080
                      :layout "desktop"})

(def ^:const +layouts+
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

(def ^:const +ordering-options+
  {:name "ds.project-ordering.by-name"
   :created "ds.project-ordering.by-creation-date"})

;; --- Lenses

(def ^:const projects-by-id-l
  (as-> (l/key :projects-by-id) $
    (l/focus-atom $ s/state)))

(def ^:const project-ordering-l
  (as-> (l/in [:dashboard :project-order]) $
    (l/focus-atom $ s/state)))

(def ^:const project-filtering-l
  (as-> (l/in [:dashboard :project-filter]) $
    (l/focus-atom $ s/state)))

;; --- Sort Widget

(defn sort-widget-render
  []
  (let [ordering (rum/react project-ordering-l)
        on-change #(rs/emit! (dd/set-project-ordering
                              (keyword (.-value (.-target %)))))]
    (html
     [:div
      [:span (tr "ds.project-ordering")]
      [:select.input-select
       {:on-change on-change
        :value (name ordering)}
       (for [option (keys +ordering-options+)
             :let [option-id (get +ordering-options+ option)
                   option-value (name option)
                   option-text (tr option-id)]]
         [:option
          {:key option-id
           :value option-value}
          option-text])]])))

(def ^:const ^:private sort-widget
  (mx/component
   {:render sort-widget-render
    :name "sort-widget-render"
    :mixins [rum/reactive mx/static]}))

;; --- Filtering Widget

(defn search-widget-render
  []
  (letfn [(on-term-change [event]
            (-> (dom/get-target event)
                (dom/get-value)
                (dd/set-project-filtering)
                (rs/emit!)))
          (on-clear [event]
            (rs/emit! (dd/clear-project-filtering)))]
    (html
     [:form.dashboard-search
      [:input.input-text
       {:type "text"
        :on-change on-term-change
        :auto-focus true
        :placeholder (tr "ds.project-search.placeholder")
        :value (rum/react project-filtering-l)}]
      [:div.clear-search
       {:on-click on-clear}
       i/close]])))

(def ^:const ^:private search-widget
  (mx/component
   {:render search-widget-render
    :name "search-widget"
    :mixins [rum/reactive mx/static]}))

;; --- Sort & Search Menu

(defn menu-render
  []
  (let [projects (rum/react projects-by-id-l)
        pcount (count projects)]
    (html
     [:section.dashboard-bar
      [:div.dashboard-info
       [:span.dashboard-projects (tr "ds.num-projects" (t/c pcount))]
       (sort-widget)
       (search-widget)]])))

(def menu
  (mx/component
   {:render menu-render
    :name "menu"
    :mixins [rum/reactive mx/static]}))

;; --- Grid Item

(defn grid-item-render
  [own project]
  (letfn [(on-navigate [event]
            (rs/emit! (dp/go-to (:id project))))
          (delete []
            (rs/emit! (dp/delete-project project)))
          (on-delete [event]
            (dom/stop-propagation event)
            (udl/open! :confirm {:on-accept delete}))]
    (html
     [:div.grid-item.project-th {:on-click on-navigate
                                 :key (:id project)}
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
        i/trash]]])))

(def ^:const ^:private grid-item
  (mx/component
   {:render grid-item-render
    :name "grid-item"
    :mixins [rum/static]}))

;; --- Grid

(defn grid-render
  [own]
  (let [projects (rum/react projects-by-id-l)
        ordering (rum/react project-ordering-l)
        filtering (rum/react project-filtering-l)]
    (letfn [(on-click [e]
              (dom/prevent-default e)
              (udl/open! :new-project))]
      (html
       [:section.dashboard-grid
        ;; LOADER WIP
        [:div.loader-content
         i/loader
         [:div.btn-primary i/loader-pencil]]
        ;; LOADER WIP
        [:h2 "Your projects"]
         [:div.dashboard-grid-content
          [:div.grid-item.add-project
           {:on-click on-click}
           [:span "+ New project"]]
          (for [item (->> (vals projects)
                          (dp/filter-projects-by filtering)
                          (dp/sort-projects-by ordering))]
            (-> (grid-item item)
                (rum/with-key (:id item))))]]))))

(def grid
  (mx/component
   {:render grid-render
    :name "grid"
    :mixins [rum/reactive]}))

;; --- Page

(defn projects-page-render
  [own]
  (html
   [:main.dashboard-main
    (uum/messages)
    (header)
    [:section.dashboard-content
     (menu)
     (grid)]]))

(defn projects-page-will-mount
  [own]
  (rs/emit! (dd/initialize :dashboard/projects))
  own)

(defn projects-page-transfer-state
  [old-state state]
  (rs/emit! (dd/initialize :dashboard/projects))
  state)

(def projects-page
  (mx/component
   {:render projects-page-render
    :will-mount projects-page-will-mount
    :transfer-state projects-page-transfer-state
    :name "projects-page"
    :mixins [rum/static]}))

;; --- Lightbox: Layout input

(defn- layout-input
  [local layout-id]
  (let [layout (get-in +layouts+ [layout-id])
        id (:id layout)
        name (:name layout)
        width (:width layout)
        height (:height layout)]
    (html
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
      [:label {:value (:name @local) :for id} name]])))

;; --- Lightbox: Layout selector

(defn- layout-selector
  [local]
  (html
   [:div.input-radio.radio-primary
    (layout-input local "mobile")
    (layout-input local "tablet")
    (layout-input local "notebook")
    (layout-input local "desktop")]))

;; -- New Project Lightbox

(defn- new-project-lightbox-render
  [own]
  (let [local (:rum/local own)
        name (:name @local)
        width (:width @local)
        height (:height @local)]
   (html
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
      i/close]])))

(def new-project-lightbox
  (mx/component
   {:render new-project-lightbox-render
    :name "new-project-lightbox"
    :mixins [(rum/local +project-defaults+)]}))

(defmethod lbx/render-lightbox :new-project
  [_]
  (new-project-lightbox))
