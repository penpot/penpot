;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) 2015-2016 Andrey Antukh <niwi@niwi.nz>
;; Copyright (c) 2015-2016 Juan de la Cruz <delacruzgarciajuan@gmail.com>

(ns uxbox.main.ui.dashboard.images
  (:require [sablono.core :as html :refer-macros [html]]
            [rum.core :as rum]
            [cuerdas.core :as str]
            [lentes.core :as l]
            [uxbox.util.i18n :as t :refer (tr)]
            [uxbox.main.state :as st]
            [uxbox.util.rstore :as rs]
            [uxbox.main.library :as library]
            [uxbox.main.data.dashboard :as dd]
            [uxbox.main.data.lightbox :as udl]
            [uxbox.main.data.images :as di]
            [uxbox.main.ui.icons :as i]
            [uxbox.util.mixins :as mx]
            [uxbox.main.ui.lightbox :as lbx]
            [uxbox.main.ui.keyboard :as kbd]
            [uxbox.main.ui.library-bar :as ui.library-bar]
            [uxbox.main.ui.dashboard.header :refer (header)]
            [uxbox.util.data :as data]
            [uxbox.util.lens :as ul]
            [uxbox.util.dom :as dom]))

;; --- Helpers & Constants

(def +ordering-options+
  {:name "ds.project-ordering.by-name"
   :created "ds.project-ordering.by-creation-date"})

(defn- sort-images-by
  [ordering projs]
  (case ordering
    :name (sort-by :name projs)
    :created (reverse (sort-by :created-at projs))
    projs))

(defn- contains-term?
  [phrase term]
  (str/contains? (str/lower phrase) (str/trim (str/lower term))))

(defn- filter-images-by
  [term projs]
  (if (str/blank? term)
    projs
    (filter #(contains-term? (:name %) term) projs)))

;; --- Lenses

(def ^:private dashboard-l
  (-> (l/key :dashboard)
      (l/derive st/state)))

(def ^:private collections-by-id-l
  (-> (l/key :images-by-id)
      (l/derive st/state)))

(def ^:private images-ordering-l
  (-> (l/in [:dashboard :images-order])
      (l/derive st/state)))

(def ^:private images-filtering-l
  (-> (l/in [:dashboard :images-filter])
      (l/derive st/state)))

(defn- focus-collection
  [collid]
  (-> (l/key collid)
      (l/derive collections-by-id-l)))

;; --- Page Title

(defn page-title-render
  [own coll]
  (let [local (:rum/local own)
        dashboard (rum/react dashboard-l)
        own? (:builtin coll false)]
    (letfn [(persist [event]
              (let [name (:coll-name @local)]
                (rs/emit! (di/rename-collection (:id coll) name))
                (swap! local assoc :edit false)))

            (on-key-down [event]
              (cond
                (kbd/esc? event)
                (swap! local assoc :edit false)

                (kbd/enter? event)
                (do
                  (dom/stop-propagation event)
                  (persist event))))

            (on-input [event]
              (let [content (dom/event->inner-text event)]
                (swap! local assoc :coll-name content)))

            (on-edit [e]
              (swap! local assoc :edit true :coll-name (:name coll)))

            (on-delete [e]
              (rs/emit! (di/delete-collection (:id coll))))]
      (html
       [:div.dashboard-title
        [:h2
         (if (:edit @local)
           [:div.dashboard-title-field
            [:span.edit
             {:content-editable ""
              :on-input on-input
              :on-key-down on-key-down}
             (:name coll)]
            [:span.close
             {:on-click #(swap! local assoc :edit false)}
             i/close]]
           [:span.dashboard-title-field
            (:name coll)])]
        (if (and (not own?) coll)
          [:div.edition
           (if (:edit @local)
             [:span {:on-click persist} i/save]
             [:span {:on-click on-edit} i/pencil])
           [:span {:on-click on-delete} i/trash]])]))))

(def ^:private page-title
  (mx/component
   {:render page-title-render
    :name "page-title"
    :mixins [(mx/local) mx/static mx/reactive]}))

;; --- Nav

(defn nav-render
  [own]
  (let [dashboard (rum/react dashboard-l)
        collid (:collection-id dashboard)
        own? (= (:collection-type dashboard) :own)
        builtin? (= (:collection-type dashboard) :builtin)
        collections (if builtin?
                      (vals library/+image-collections-by-id+)
                      (vals (rum/react collections-by-id-l)))
        show-builtin #(rs/emit! (di/set-collection-type :builtin))
        show-own #(rs/emit! (di/set-collection-type :own))
        new-coll #(rs/emit! (di/create-collection))
        select-coll #(rs/emit! (di/set-collection % builtin?))]
    (html
     [:div.library-bar
      [:div.library-bar-inside
       [:ul.library-tabs
        [:li {:class-name (when builtin? "current")
              :on-click show-builtin}
         "STANDARD"]
        [:li {:class-name (when own? "current")
              :on-click show-own}
         "YOUR LIBRARIES"]]
       [:ul.library-elements
        (when own?
          [:li
           [:a.btn-primary {:on-click new-coll} "+ New library"]])
        (for [{:keys [id images name]} collections
              :let [num (count images)]]
          [:li {:key (str id)
                :on-click (partial select-coll id)
                :class (when (= id collid) "current")}
           [:span.element-title name]
           [:span.element-subtitle
            (tr "ds.num-elements" (t/c num))]])]]])))

(def ^:private nav
  (mx/component
   {:render nav-render
    :name "nav"
    :mixins [mx/reactive mx/static]}))

;; --- Grid

(defn- grid-render
  [own]
  (let [local (:rum/local own)
        dashboard (rum/react dashboard-l)
        coll-type (:collection-type dashboard)
        coll-id (:collection-id dashboard)
        own? (= coll-type :own)
        builtin? (= coll-type :builtin)
        coll (if builtin?
               (get library/+image-collections-by-id+ coll-id)
               (rum/react (focus-collection coll-id)))
        images-filtering (rum/react images-filtering-l)
        images-ordering (rum/react images-ordering-l)
        images (->> (:images coll)
                    (remove nil?)
                    (filter-images-by images-filtering)
                    (sort-images-by images-ordering))
        show-menu? (not (empty? (:selected @local)))]
    (letfn [(toggle-check [image]
              (swap! local update :selected #(data/conj-or-disj % image)))
            (toggle-check-card [image event]
              (when (kbd/shift? event)
                (toggle-check image)))
            (forward-click [event]
              (dom/click (mx/ref-node own "file-input")))
            (delete-selected []
              (->> (:selected @local)
                   (run! #(rs/emit! (di/delete-image coll-id %)))))
            (on-file-selected [event]
              (let [files (dom/get-event-files event)]
                (rs/emit! (di/create-images coll-id files))))]
      (when coll
        (html
         [:section.dashboard-grid.library
          (page-title coll)
          [:div.dashboard-grid-content
           [:div.dashboard-grid-row
            (if own?
              [:div.grid-item.add-project {:on-click forward-click}
               [:span "+ New image"]
               [:input.upload-image-input
                {:style {:display "none"}
                 :multiple true
                 :ref "file-input"
                 :value ""
                 :type "file"
                 :on-change on-file-selected}]])

            (for [image images
                  :let [selected? (contains? (:selected @local) image)]]
              [:div.grid-item.images-th
               {:key (:id image)
                :on-click (partial toggle-check-card image)}
               [:div.grid-item-th
                {:style {:background-image (str "url('" (:thumbnail image) "')")}}
                [:div.input-checkbox.check-primary
                 [:input {:type "checkbox"
                          :id (:id image)
                          :on-click (partial toggle-check image)
                          :checked selected?}]
                 [:label {:for (:id image)}]]]
               [:span (:name image)]])]

           (when show-menu?
             ;; MULTISELECT OPTIONS BAR
             [:div.multiselect-bar
              [:div.multiselect-nav
               [:span.move-item.tooltip.tooltip-top {:alt "Copy to"} i/organize]
               (if own?
                 [:span.copy.tooltip.tooltip-top {:alt "Duplicate"} i/copy])
               (if own?
                 [:span.delete.tooltip.tooltip-top
                  {:alt "Delete"
                   :on-click delete-selected}
                  i/trash])]])]])))))

(def ^:private grid
  (mx/component
   {:render grid-render
    :name "grid"
    :mixins [(rum/local {:selected #{}}) mx/static mx/reactive]}))

;; --- Sort Widget

(defn- sort-widget-render
  []
  (let [ordering (rum/react images-ordering-l)
        on-change #(rs/emit! (di/set-images-ordering
                              (keyword (.-value (.-target %)))))]
    (html
     [:div
      [:span (tr "ds.project-ordering")]
      [:select.input-select
       {:on-change on-change
        :value (:name ordering "")}
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
    :mixins [mx/reactive mx/static]}))

;; --- Filtering Widget

(defn- search-widget-render
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
      [:div.clear-search {:on-click on-clear} i/close]])))

(def ^:private search-widget
  (mx/component
   {:render search-widget-render
    :name "search-widget"
    :mixins [mx/reactive mx/static]}))

;; --- Menu

(defn- menu-render
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

(def ^:private menu
  (mx/component
   {:render menu-render
    :name "menu"
    :mixins [mx/reactive mx/static]}))


;; --- Images Page

(defn- images-page-render
  [own]
  (html
   [:main.dashboard-main
    (header)
    [:section.dashboard-content
     (nav)
     (menu)
     (grid)]]))

(defn- images-page-will-mount
  [own]
  (rs/emit! (dd/initialize :dashboard/images)
            (di/initialize))
  own)

(defn- images-page-transfer-state
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
