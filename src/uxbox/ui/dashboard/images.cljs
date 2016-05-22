;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) 2015-2016 Andrey Antukh <niwi@niwi.nz>
;; Copyright (c) 2015-2016 Juan de la Cruz <delacruzgarciajuan@gmail.com>

(ns uxbox.ui.dashboard.images
  (:require [sablono.core :as html :refer-macros [html]]
            [rum.core :as rum]
            [lentes.core :as l]
            [uxbox.locales :as t :refer (tr)]
            [uxbox.state :as st]
            [uxbox.rstore :as rs]
            [uxbox.library :as library]
            [uxbox.data.dashboard :as dd]
            [uxbox.data.lightbox :as udl]
            [uxbox.data.images :as di]
            [uxbox.ui.icons :as i]
            [uxbox.ui.mixins :as mx]
            [uxbox.ui.lightbox :as lbx]
            [uxbox.ui.keyboard :as k]
            [uxbox.ui.library-bar :as ui.library-bar]
            [uxbox.ui.dashboard.header :refer (header)]
            [uxbox.util.lens :as ul]
            [uxbox.util.dom :as dom]))

;; --- Helpers & Constants

(def +ordering-options+
  {:name "ds.project-ordering.by-name"
   :created "ds.project-ordering.by-creation-date"})

;; --- Lenses

(def ^:const ^:private dashboard-l
  (-> (l/key :dashboard)
      (l/focus-atom st/state)))

(def ^:const ^:private collections-by-id-l
  (-> (comp (l/key :images-by-id)
            (ul/merge library/+image-collections-by-id+))
      (l/focus-atom st/state)))

(def images-ordering-l
  (as-> (l/in [:dashboard :images-order]) $
    (l/focus-atom $ st/state)))

(def images-filtering-l
  (as-> (l/in [:dashboard :images-filter]) $
    (l/focus-atom $ st/state)))


(defn- focus-collection
  [collid]
  (-> (l/key collid)
      (l/focus-atom collections-by-id-l)))


;; --- Page Title

(defn page-title-render
  [own coll]
  (let [local (:rum/local own)
        dashboard (rum/react dashboard-l)
        own? (:builtin coll false)]
    (letfn [(on-title-save [e]
              (rs/emit! (di/rename-collection (:id coll) (:coll-name @local)))
              (swap! local assoc :edit false))
            (on-title-edited [e]
              (cond
                (k/esc? e) (swap! local assoc :edit false)
                (k/enter? e) (on-title-save e)
                :else (let [content (dom/event->inner-text e)]
                        (swap! local assoc :coll-name content))))
            (on-title-edit [e]
              (swap! local assoc :edit true :coll-name (:name coll)))
            (on-delete [e]
              (rs/emit! (di/delete-collection (:id coll))))]
      (html
       [:div.dashboard-title {}
        [:h2 {}
         (if (:edit @local)
           [:div.dashboard-title-field
            [:span.edit
             {:content-editable ""
              :on-key-up on-title-edited}
             (:name coll)]
            [:span.close
             {:on-click #(swap! local assoc :edit false)}
             i/close]]
           [:span.dashboard-title-field
            (:name coll)])]
        (if (and (not own?) coll)
          [:div.edition
           (if (:edit @local)
             [:span {:on-click on-title-save} i/save]
             [:span {:on-click on-title-edit} i/pencil])
           [:span {:on-click on-delete} i/trash]])]))))

(def ^:private page-title
  (mx/component
   {:render page-title-render
    :name "page-title"
    :mixins [(rum/local {}) mx/static rum/reactive]}))

;; --- Nav

(defn nav-render
  [own]
  (let [dashboard (rum/react dashboard-l)
        collections-by-id (rum/react collections-by-id-l)
        collid (:collection-id dashboard)
        own? (= (:collection-type dashboard) :own)
        builtin? (= (:collection-type dashboard) :builtin)
        collections (as-> (vals collections-by-id) $
                      (if own?
                        (filter (comp not :builtin) $)
                        (filter :builtin $)))]
    (html
     [:div.library-bar
      [:div.library-bar-inside
       [:ul.library-tabs
        [:li {:class-name (when builtin? "current")
              :on-click #(rs/emit! (di/set-collection-type :builtin))}
         "STANDARD"]
        [:li {:class-name (when own? "current")
              :on-click #(rs/emit! (di/set-collection-type :own))}
         "YOUR LIBRARIES"]]
       [:ul.library-elements
        (when own?
          [:li
           [:a.btn-primary
            {:on-click #(rs/emit! (di/create-collection))}
            "+ New library"]])
        (for [props collections
              :let [num (count (:images props))]]
          [:li {:key (str (:id props))
                :on-click #(rs/emit! (di/set-collection (:id props)))
                :class-name (when (= (:id props) collid) "current")}
           [:span.element-title (:name props)]
           [:span.element-subtitle
            (tr "ds.num-elements" (t/c num))]])]]])))

(def ^:const ^:private nav
  (mx/component
   {:render nav-render
    :name "nav"
    :mixins [rum/reactive]}))


;; --- Grid

(defn grid-render
  [own]
  (let [local (:rum/local own)
        dashboard (rum/react dashboard-l)
        coll-type (:collection-type dashboard)
        coll-id (:collection-id dashboard)
        own? (= coll-type :own)
        coll (rum/react (focus-collection coll-id))
        images-filtering (rum/react images-filtering-l)
        images-ordering (rum/react images-ordering-l)
        coll-images (->> (:images coll)
                         (remove nil?)
                         (di/filter-images-by images-filtering)
                         (di/sort-images-by images-ordering))
        toggle-image-check (fn [image]
                             (swap! local update :selected #(if (% image) (disj % image) (conj % image))))
        delete-selected-images #(doseq [image (:selected @local)]
                                   (rs/emit! (di/delete-image coll-id image)))]
    (when coll
      (html
       [:section.dashboard-grid.library
        (page-title coll)
        [:div.dashboard-grid-content
         [:div.dashboard-grid-row
           (if own?
             [:div.grid-item.add-project
              {:on-click #(dom/click (dom/get-element-by-class "upload-image-input"))}
              [:span "+ New image"]
              [:input.upload-image-input {:style {:display "none"}
                                          :type "file"
                                          :on-change #(rs/emit! (di/create-images coll-id (dom/get-event-files %)))}]])

           (for [image coll-images]
             [:div.grid-item.images-th
              {:key (:id image) :on-click #(when (k/shift? %) (toggle-image-check image))}
              [:div.grid-item-th
               {:style
                 {:background-image (str "url('" (:thumbnail image) "')")}}
                 [:div.input-checkbox.check-primary
                  [:input {:type "checkbox"
                           :id (:id image)
                           :on-click #(toggle-image-check image)
                           :checked ((:selected @local) image)}]
                  [:label {:for (:id image)}]]]
              [:span (:name image)]])]

         (when (not (empty? (:selected @local)))
           ;; MULTISELECT OPTIONS BAR
           [:div.multiselect-bar
            [:div.multiselect-nav
             [:span.move-item.tooltip.tooltip-top
              {:alt "Copy to"}
              i/organize]
             (if own?
               [:span.copy.tooltip.tooltip-top
                {:alt "Duplicate"}
                i/copy])
             (if own?
               [:span.delete.tooltip.tooltip-top
                {:alt "Delete"
                 :on-click #(delete-selected-images)}
                i/trash])]])]]))))

(def ^:const ^:private grid
  (mx/component
   {:render grid-render
    :name "grid"
    :mixins [(rum/local {:selected #{}})
             mx/static
             rum/reactive]}))

;; --- Sort Widget

(defn sort-widget-render
  []
  (let [ordering (rum/react images-ordering-l)
        on-change #(rs/emit! (di/set-images-ordering
                              (keyword (.-value (.-target %)))))]
    (html
     [:div
      [:span (tr "ds.project-ordering")]
      [:select.input-select
       {:on-change on-change
        :value (:name ordering)}
       (for [option (keys +ordering-options+)
             :let [option-id (get +ordering-options+ option)
                   option-value (:name option)
                   option-text (tr option-id)]]
         [:option
          {:key option-id
           :value option-value}
          option-text])]])))

(def ^:private sort-widget
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
                (di/set-images-filtering)
                (rs/emit!)))
          (on-clear [event]
            (rs/emit! (di/clear-images-filtering)))]
    (html
     [:form.dashboard-search
      [:input.input-text
       {:key :images-search-box
        :type "text"
        :on-change on-term-change
        :auto-focus true
        :placeholder (tr "ds.project-search.placeholder")
        :value (rum/react images-filtering-l)}]
      [:div.clear-search
       {:on-click on-clear}
       i/close]])))

(def ^:private search-widget
  (mx/component
   {:render search-widget-render
    :name "search-widget"
    :mixins [rum/reactive mx/static]}))


;; --- Menu

(defn menu-render
  []
  (let [dashboard (rum/react dashboard-l)
        coll-id (:collection-id dashboard)
        coll (rum/react (focus-collection coll-id))
        icount (count (:images coll)) ]
    (html
     [:section.dashboard-bar.library-gap
      [:div.dashboard-info
       [:span.dashboard-images (tr "ds.num-images" (t/c icount))]
       (sort-widget)
       (search-widget)]])))

(def menu
  (mx/component
   {:render menu-render
    :name "menu"
    :mixins [rum/reactive mx/static]}))


;; --- Images Page

(defn images-page-render
  [own]
  (html
   [:main.dashboard-main
    (header)
    [:section.dashboard-content
     (nav)
     (menu)
     (grid)]]))

(defn images-page-will-mount
  [own]
  (rs/emit! (dd/initialize :dashboard/images)
            (di/initialize))
  own)

(defn images-page-transfer-state
  [old-state state]
  (rs/emit! (dd/initialize :dashboard/images))
  state)

(def images-page
  (mx/component
   {:render images-page-render
    :will-mount images-page-will-mount
    :transfer-state images-page-transfer-state
    :name "images-page"
    :mixins [mx/static]}))
