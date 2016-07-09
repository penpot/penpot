;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) 2015-2016 Andrey Antukh <niwi@niwi.nz>
;; Copyright (c) 2015-2016 Juan de la Cruz <delacruzgarciajuan@gmail.com>

(ns uxbox.main.ui.dashboard.projects
  (:require [sablono.core :as html :refer-macros [html]]
            [rum.core :as rum]
            [lentes.core :as l]
            [cuerdas.core :as str]
            [uxbox.util.i18n :as t :refer (tr)]
            [uxbox.util.router :as r]
            [uxbox.util.rstore :as rs]
            [uxbox.main.state :as s]
            [uxbox.main.data.dashboard :as dd]
            [uxbox.main.data.projects :as dp]
            [uxbox.main.data.workspace :as dw]
            [uxbox.main.data.lightbox :as udl]
            [uxbox.main.ui.icons :as i]
            [uxbox.util.dom :as dom]
            [uxbox.main.ui.dashboard.header :refer (header)]
            [uxbox.main.ui.lightbox :as lbx]
            [uxbox.main.ui.messages :as uum]
            [uxbox.util.mixins :as mx]
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

;; --- Lenses

(def projects-by-id-ref
  (as-> (l/key :projects-by-id) $
    (l/derive $ s/state)))

(def project-ordering-ref
  (as-> (l/in [:dashboard :project-order]) $
    (l/derive $ s/state)))

(def project-filtering-ref
  (as-> (l/in [:dashboard :project-filter]) $
    (l/derive $ s/state)))

;; --- Sort Widget

(defn sort-widget-render
  []
  (let [ordering (mx/react project-ordering-ref)
        on-change #(rs/emit! (dp/set-project-ordering
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

(def ^:private sort-widget
  (mx/component
   {:render sort-widget-render
    :name "sort-widget-render"
    :mixins [mx/reactive mx/static]}))

;; --- Filtering Widget

(defn search-widget-render
  []
  (letfn [(on-term-change [event]
            (-> (dom/get-target event)
                (dom/get-value)
                (dp/set-project-filtering)
                (rs/emit!)))
          (on-clear [event]
            (rs/emit! (dp/clear-project-filtering)))]
    (html
     [:form.dashboard-search
      [:input.input-text
       {:type "text"
        :on-change on-term-change
        :auto-focus true
        :placeholder (tr "ds.project-search.placeholder")
        :value (mx/react project-filtering-ref)}]
      [:div.clear-search
       {:on-click on-clear}
       i/close]])))

(def ^:private search-widget
  (mx/component
   {:render search-widget-render
    :name "search-widget"
    :mixins [mx/reactive mx/static]}))

;; --- Sort & Search Menu

(defn menu-render
  []
  (let [projects (mx/react projects-by-id-ref)
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
    :mixins [mx/reactive mx/static]}))

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

(def ^:private grid-item
  (mx/component
   {:render grid-item-render
    :name "grid-item"
    :mixins [rum/static]}))

;; --- Grid

(defn grid-render
  [own]
  (let [projects (mx/react projects-by-id-ref)
        ordering (mx/react project-ordering-ref)
        filtering (mx/react project-filtering-ref)]
    (letfn [(on-click [e]
              (dom/prevent-default e)
              (udl/open! :new-project))]
      (html
       [:section.dashboard-grid
        [:h2 "Your projects"]
         [:div.dashboard-grid-content
          [:div.dashboard-grid-row
            [:div.grid-item.add-project
             {:on-click on-click}
             [:span "+ New project"]]
            (for [item (->> (vals projects)
                            (dp/filter-projects-by filtering)
                            (dp/sort-projects-by ordering))]
              (-> (grid-item item)
                  (rum/with-key (:id item))))]]]))))

(def grid
  (mx/component
   {:render grid-render
    :name "grid"
    :mixins [mx/reactive]}))

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
  (rs/emit! (dd/initialize :dashboard/projects)
            (dp/initialize))
  own)

(defn projects-page-did-remount
  [old-state state]
  (rs/emit! (dd/initialize :dashboard/projects))
  state)

(def projects-page
  (mx/component
   {:render projects-page-render
    :will-mount projects-page-will-mount
    :did-remount projects-page-did-remount
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
