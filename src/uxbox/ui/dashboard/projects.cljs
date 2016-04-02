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
            [uxbox.util.datetime :as dt]
            [uxbox.data.dashboard :as dd]
            [uxbox.data.projects :as dp]
            [uxbox.data.workspace :as dw]
            [uxbox.ui.icons :as i]
            [uxbox.util.dom :as dom]
            [uxbox.ui.dashboard.header :as dsh.header]
            [uxbox.ui.lightbox :as lightbox]
            [uxbox.ui.mixins :as mx]))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Helpers & Constants
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

;; FIXME rename
(def +ordering-options+ {:name "ds.project-ordering.by-name"
                         :created "ds.project-ordering.by-creation-date"})

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

(def ^:static ^:private
  +project-defaults+ {:name ""
                      :width 1920
                      :height 1080
                      :layout "desktop"})

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Top Menu
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(def ^:const menu-l
  (as-> (l/select-keys [:projects-by-id]) $
    (l/focus-atom $ s/state)))

(def ^:const project-ordering-l
  (as-> (l/in [:dashboard :project-order]) $
    (l/focus-atom $ s/state)))

(def ^:const project-filtering-l
  (as-> (l/in [:dashboard :project-filter]) $
    (l/focus-atom $ s/state)))

(defn project-sort-render
  []
  (let [ordering (rum/react project-ordering-l)
        change-order #(rs/emit! (dd/set-project-ordering
                                 (keyword (.-value (.-target %)))))]
    (html
     [:div
       [:span (tr "ds.project-ordering")]
       [:select.input-select
        {:on-change change-order
         :value (name ordering)}
        (for [option (keys +ordering-options+)
              :let [option-id (get +ordering-options+ option)
                    option-value (name option)
                    option-text (tr option-id)]]
          [:option
           {:key option-id
            :value option-value}
           option-text])]])))

(def project-sorting
  (mx/component
   {:render project-sort-render
    :name "project-sort-order"
    :mixins [rum/reactive]}))

(defn project-search-render
  []
  (let [change-term #(rs/emit! (dd/set-project-filtering (.-value (.-target %))))
        clear-term #(rs/emit! (dd/clear-project-filtering))]
    (html
     [:form.dashboard-search
      [:input.input-text
       {:type "text"
        :on-change change-term
        :auto-focus true
        :placeholder (tr "ds.project-search.placeholder")
        :value (rum/react project-filtering-l)}]
      [:div.clear-search
        {:on-click clear-term}
       i/close]])))

(def project-search
  (mx/component
   {:render project-search-render
    :name "project-search"
    :mixins [rum/reactive]}))

(defn menu-render
  []
  (let [projects (rum/react menu-l)
        pcount (count (:projects-by-id projects))] ;; FIXME: redundant project-by-id key
    (html
     [:section.dashboard-bar
      [:div.dashboard-info
       [:span.dashboard-projects (tr "ds.num-projects" (t/c pcount))]
       (project-sorting)
       (project-search)]])))

(def menu
  (mx/component
   {:render menu-render
    :name "projects-menu"
    :mixins [rum/reactive]}))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Grid
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(def ^:static grid-l
  (as-> (l/select-keys [:projects-by-id]) $
    (l/focus-atom $ s/state)))

(defn project-item-render
  [own project]
  (letfn [(on-navigate [event]
            (rs/emit! (dp/go-to (:id project))))
          (delete []
            (rs/emit! (dp/delete-project project)))
          (on-delete [event]
            (dom/stop-propagation event)
            (lightbox/open! :confirm {:on-accept delete}))]
    (html
     [:div.grid-item.project-th {:on-click on-navigate
                                 :key (:id project)}
      [:h3 (:name project)]
      [:span.project-th-update
       (str "Updated " (dt/timeago (:modified-at project)))]
      [:div.project-th-actions
       [:div.project-th-icon.pages
        i/page
        [:span "0"]]
       [:div.project-th-icon.comments
        i/chat
        [:span "0"]]
       [:div.project-th-icon.delete
        {:on-click on-delete}
        i/trash]]])))

(def project-item
  (mx/component
   {:render project-item-render
    :name "project"
    :mixins [rum/static]}))

(defn grid-render
  [own]
  (letfn [(on-click [e]
            (dom/prevent-default e)
            (lightbox/open! :new-project))]
    (let [state (rum/react grid-l)
          ordering (rum/react project-ordering-l)
          filtering (rum/react project-filtering-l)
          projects (dp/filter-projects-by filtering (vals (:projects-by-id state)))]
      (html
       [:section.dashboard-grid
        [:h2 "Your projects"]
         [:div.dashboard-grid-content
          [:div.grid-item.add-project {:on-click on-click}
           [:span "+ New project"]]
          (for [item (dp/sort-projects-by ordering projects)]
            (-> (project-item item)
                (rum/with-key (:id item))))]]))))

(def grid
  (mx/component
   {:render grid-render
    :name "grid"
    :mixins [rum/reactive]}))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Lightbox
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(defn layout-input
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
        :on-change #(swap! local merge {:layout layout-id :width width :height height})}]
      [:label {:value (:name @local) :for id} name]])))

(defn- layout-selector
  [local]
  (html
   [:div.input-radio.radio-primary
    (layout-input local "mobile")
    (layout-input local "tablet")
    (layout-input local "notebook")
    (layout-input local "desktop")]))

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
                       (lightbox/close!))

          :type "submit"}])]
     [:a.close {:href "#"
                :on-click #(do (dom/prevent-default %)
                               (lightbox/close!))}
      i/close]])))

(def new-project-lightbox
  (mx/component
   {:render new-project-lightbox-render
    :name "new-project-lightbox"
    :mixins [(rum/local +project-defaults+)]}))

(defmethod lightbox/render-lightbox :new-project
  [_]
  (new-project-lightbox))
