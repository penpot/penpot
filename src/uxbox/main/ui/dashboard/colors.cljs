;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) 2015-2016 Andrey Antukh <niwi@niwi.nz>
;; Copyright (c) 2015-2016 Juan de la Cruz <delacruzgarciajuan@gmail.com>

(ns uxbox.main.ui.dashboard.colors
  (:require [sablono.core :refer-macros [html]]
            [rum.core :as rum]
            [cuerdas.core :as str]
            [lentes.core :as l]
            [uxbox.util.i18n :as t :refer (tr)]
            [uxbox.main.state :as st]
            [uxbox.util.rstore :as rs]
            [uxbox.util.schema :as sc]
            [uxbox.main.library :as library]
            [uxbox.main.data.dashboard :as dd]
            [uxbox.main.data.colors :as dc]
            [uxbox.main.data.lightbox :as udl]
            [uxbox.main.ui.icons :as i]
            [uxbox.main.ui.forms :as form]
            [uxbox.main.ui.lightbox :as lbx]
            [uxbox.main.ui.colorpicker :refer (colorpicker)]
            [uxbox.util.mixins :as mx]
            [uxbox.main.ui.dashboard.header :refer (header)]
            [uxbox.main.ui.keyboard :as k]
            [uxbox.util.dom :as dom]
            [uxbox.util.lens :as ul]
            [uxbox.util.color :refer (hex->rgb)]))

;; --- Refs

(def ^:private dashboard-ref
  (-> (l/key :dashboard)
      (l/derive st/state)))

(def ^:private collections-map-ref
  (-> (comp (l/key :colors-by-id)
            (ul/merge library/+color-collections-by-id+))
      (l/derive st/state)))

(def ^:private collections-ref
  (-> (l/lens vals)
      (l/derive collections-map-ref)))

(defn- focus-collection
  [id]
  (-> (l/key id)
      (l/derive collections-map-ref)))

;; --- Page Title

(mx/defcs page-title
  {:mixins [(rum/local {}) mx/static mx/reactive]}
  [own {:keys [id] :as coll}]
  (let [local (:rum/local own)
        dashboard (mx/react dashboard-ref)
        own? (= :builtin (:type coll))
        edit? (:edit @local)]
    (letfn [(save []
              (let [dom (mx/ref-node own "input")
                    name (.-innerText dom)]
                (rs/emit! (dc/rename-collection id (str/trim name)))
                (swap! local assoc :edit false)))
            (cancel []
              (swap! local assoc :edit false))
            (edit []
              (swap! local assoc :edit true))
            (on-input-keydown [e]
              (cond
                (k/esc? e) (cancel)
                (k/enter? e)
                (do
                  (dom/prevent-default e)
                  (dom/stop-propagation e)
                  (save))))
            (delete-collection []
              (rs/emit! (dc/delete-collection (:id coll))))]
      [:div.dashboard-title
       [:h2
        (if edit?
          [:div.dashboard-title-field
           [:span.edit
            {:content-editable true
             :ref "input"
             :on-key-down on-input-keydown}
            (:name coll)]
           [:span.close {:on-click cancel} i/close]]
          [:span.dashboard-title-field
           {:on-double-click edit}
           (:name coll)])]
       (if (and (not own?) coll)
         [:div.edition
          (if edit?
            [:span {:on-click save} i/save]
            [:span {:on-click edit} i/pencil])
          [:span {:on-click delete-collection} i/trash]])])))

;; --- Grid

(mx/defc grid-item
  [color selected?]
  (let [color-rgb (hex->rgb color)]
    (letfn [(toggle-selection [event]
              (rs/emit! (dc/toggle-color-selection color)))
            (toggle-selection-shifted [event]
              (when (k/shift? event)
                (toggle-selection event)))]
      [:div.grid-item.small-item.project-th
       {:on-click toggle-selection-shifted}
       [:span.color-swatch {:style {:background-color color}}]
       [:div.input-checkbox.check-primary
        [:input {:type "checkbox"
                 :id color
                 :on-click toggle-selection
                 :checked selected?}]
        [:label {:for color}]]
       [:span.color-data color]
       [:span.color-data (apply str "RGB " (interpose ", " color-rgb))]])))

(mx/defc grid-options
  [coll]
  (let [own? (= (:type coll) :own)]
    (letfn [(on-delete [event]
              (rs/emit! (dc/delete-selected-colors)))]
      ;; MULTISELECT OPTIONS BAR
      [:div.multiselect-bar
       (if own?
         [:div.multiselect-nav
          #_[:span.move-item.tooltip.tooltip-top
             {:alt "Move to"}
             i/organize]
          [:span.delete.tooltip.tooltip-top
           {:alt "Delete"
            :on-click on-delete}
           i/trash]]
         [:div.multiselect-nav
          [:span.move-item.tooltip.tooltip-top
           {:alt "Copy to"}
           i/organize]])])))

(mx/defc grid
  {:mixins [mx/static]}
  [selected coll]
  (let [own? (= (:type coll) :own)]
    [:div.dashboard-grid-content
     [:div.dashboard-grid-row
      (when own?
        [:div.grid-item.small-item.add-project
         {:on-click #(udl/open! :color-form {:coll coll})}
         [:span "+ New color"]])
      (for [color (remove nil? (:data coll))
            :let [selected? (contains? selected color)]]
        (-> (grid-item color selected?)
            (mx/with-key (str color))))]]))

(mx/defc content
  {:mixins [mx/static mx/reactive]}
  []
  (let [dashboard (mx/react dashboard-ref)
        selected (:selected dashboard)
        coll-type (:type dashboard)
        coll-id (:id dashboard)
        coll (mx/react (focus-collection coll-id))
        own? (= coll-type :own)]
    (when coll
      [:section.dashboard-grid.library
       (page-title coll)
       (grid selected coll)
       (when (and (seq selected))
         (grid-options coll))])))

;; --- Nav

(mx/defc nav-collection
  {:mixins [mx/static]}
  [collection selected?]
  (letfn [(on-click [event]
            (let [type (:type collection)
                  id (:id collection)]
              (rs/emit! (dc/select-collection type id))))]
    (let [colors (count (:data collection))]
      [:li {:on-click on-click
            :class-name (when selected? "current")}
       [:span.element-title (:name collection)]
       [:span.element-subtitle
        (tr "ds.num-elements" (t/c colors))]])))

(mx/defc nav-collections
  {:mixins [mx/static mx/reactive]}
  [type selected]
  (let [own? (= type :own)
        builtin? (= type :builtin)
        collections (cond->> (rum/react collections-ref)
                      own? (filter #(= :own (:type %)))
                      builtin? (filter #(= :builtin (:type %)))
                      own? (sort-by :id))]
    [:ul.library-elements
     (when own?
       [:li
        [:a.btn-primary
         {:on-click #(rs/emit! (dc/create-collection))}
         "+ New library"]])
     (for [coll collections
           :let [selected? (= (:id coll) selected)
                 key (str (:id coll))]]
       (-> (nav-collection coll selected?)
           (mx/with-key key)))]))

(mx/defc nav
  {:mixins [mx/static mx/reactive]}
  []
  (let [dashboard (mx/react dashboard-ref)
        collections (rum/react collections-ref)
        selected (:id dashboard)
        type (:type dashboard)
        own? (= type :own)
        builtin? (= type :builtin)]
    (letfn [(select-tab [type]
              (let [xf (filter #(= type (:type %)))
                    colls (sequence xf collections)]
                (if-let [item (first colls)]
                  (rs/emit! (dc/select-collection type (:id item)))
                  (rs/emit! (dc/select-collection type)))))]
      [:div.library-bar
       [:div.library-bar-inside
        [:ul.library-tabs
         [:li {:class-name (when builtin? "current")
               :on-click (partial select-tab :builtin)}
          "STANDARD"]
         [:li {:class-name (when own? "current")
               :on-click (partial select-tab :own)}
          "YOUR LIBRARIES"]]
        (nav-collections type selected)]])))

;; --- Colors Page

(defn- colors-page-will-mount
  [own]
  (let [[type id] (:rum/args own)]
    (rs/emit! (dc/initialize type id))
    own))

(defn- colors-page-did-remount
  [old-own own]
  (let [[old-type old-id] (:rum/args old-own)
        [new-type new-id] (:rum/args own)]
    (when (or (not= old-type new-type)
              (not= old-id new-id))
      (rs/emit! (dc/initialize new-type new-id)))
    own))

(mx/defc colors-page
  {:will-mount colors-page-will-mount
   :did-remount colors-page-did-remount
   :mixins [mx/static]}
  [type id]
  [:main.dashboard-main
   (header)
   [:section.dashboard-content
    (nav)
    (content)]])

;; --- Colors Lightbox (Component)

(mx/defcs color-lightbox
  {:mixins [(rum/local {}) mx/static]}
  [own {:keys [coll color]}]
  (let [local (:rum/local own)]
    (letfn [(on-submit [event]
              (let [params {:id (:id coll)
                            :from color
                            :to (:hex @local)}]
                (rs/emit! (dc/replace-color params))
                (udl/close!)))
            (on-change [event]
              (let [value (str/trim (dom/event->value event))]
                (swap! local assoc :hex value)))
            (on-close [event]
              (udl/close!))]
      [:div.lightbox-body
       [:h3 "New color"]
       [:form
        [:div.row-flex.center
         (colorpicker
          :value (or (:hex @local) color "#00ccff")
          :on-change #(swap! local assoc :hex %))]

        [:input#project-btn.btn-primary
         {:value "+ Add color"
          :on-click on-submit
          :type "button"}]]
       [:a.close {:on-click on-close} i/close]])))

(defmethod lbx/render-lightbox :color-form
  [params]
  (color-lightbox params))
