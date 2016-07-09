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

;; --- Lenses

(def ^:private dashboard-l
  (-> (l/key :dashboard)
      (l/derive st/state)))

(def ^:private collections-by-id-l
  (-> (comp (l/key :colors-by-id)
            (ul/merge library/+color-collections-by-id+))
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
    (letfn [(on-title-save [e]
              (rs/emit! (dc/rename-collection (:id coll) (:coll-name @local)))
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
              (rs/emit! (dc/delete-collection (:id coll))))]
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
    :mixins [(rum/local {}) mx/static mx/reactive]}))

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
              :on-click #(rs/emit! (dd/set-collection-type :builtin))}
         "STANDARD"]
        [:li {:class-name (when own? "current")
              :on-click #(rs/emit! (dd/set-collection-type :own))}
         "YOUR LIBRARIES"]]
       [:ul.library-elements
        (if own?
          [:li
           [:a.btn-primary
            {:on-click #(rs/emit! (dc/create-collection))}
            "+ New library"]])
        (for [props collections
              :let [num (count (:data props))]]
          [:li {:key (str (:id props))
                :on-click #(rs/emit! (dd/set-collection (:id props)))
                :class-name (when (= (:id props) collid) "current")}
           [:span.element-title (:name props)]
           [:span.element-subtitle
            (tr "ds.num-elements" (t/c num))]])]]])))

(def ^:private nav
  (mx/component
   {:render nav-render
    :name "nav"
    :mixins [mx/reactive]}))

;; --- Grid

(defn grid-render
  [own]
  (let [local (:rum/local own)
        dashboard (rum/react dashboard-l)
        coll-type (:collection-type dashboard)
        coll-id (:collection-id dashboard)
        own? (= coll-type :own)
        coll (rum/react (focus-collection coll-id))
        toggle-color-check (fn [color]
                             (swap! local update :selected #(if (% color) (disj % color) (conj % color))))
        delete-selected #(rs/emit! (dc/remove-colors (:id coll) (:selected @local)))]
    (when coll
      (html
       [:section.dashboard-grid.library
        (page-title coll)
        [:div.dashboard-grid-content
          [:div.dashboard-grid-row
           (if own?
             [:div.grid-item.small-item.add-project
              {:on-click #(udl/open! :color-form {:coll coll})}
              [:span "+ New color"]])
           (for [color (remove nil? (:data coll))
                 :let [color-rgb (hex->rgb color)]]
             [:div.grid-item.small-item.project-th
              {:key color :on-click #(when (k/shift? %) (toggle-color-check color))}
              [:span.color-swatch {:style {:background-color color}}]
              [:div.input-checkbox.check-primary
               [:input {:type "checkbox"
                        :id color
                        :on-click #(toggle-color-check color)
                        :checked ((:selected @local) color)}]
               [:label {:for color}]]
              [:span.color-data color]
              [:span.color-data (apply str "RGB " (interpose ", " color-rgb))]])]]

        (when (not (empty? (:selected @local)))
          ;; MULTISELECT OPTIONS BAR
          [:div.multiselect-bar
           (if own?
             [:div.multiselect-nav
              [:span.move-item.tooltip.tooltip-top
               {:alt "Move to"}
               i/organize]
              [:span.delete.tooltip.tooltip-top
               {:alt "Delete" :on-click delete-selected}
               i/trash]]
             [:div.multiselect-nav
              [:span.move-item.tooltip.tooltip-top
               {:alt "Copy to"}
               i/organize]])])]))))

(def ^:private grid
  (mx/component
   {:render grid-render
    :name "grid"
    :mixins [(rum/local {:selected #{}})
             mx/static
             mx/reactive]}))

;; --- Menu

(defn menu-render
  []
  (let [dashboard (rum/react dashboard-l)
        coll-id (:collection-id dashboard)
        coll (rum/react (focus-collection coll-id))
        ccount (count (:data coll)) ]
    (html
     [:section.dashboard-bar.library-gap
      [:div.dashboard-info
       [:span.dashboard-colors (tr "ds.num-colors" (t/c ccount))]]])))

(def menu
  (mx/component
   {:render menu-render
    :name "menu"
    :mixins [mx/reactive mx/static]}))


;; --- Colors Page

(defn colors-page-render
  [own]
  (html
   [:main.dashboard-main
    (header)
    [:section.dashboard-content
     (nav)
     (menu)
     (grid)]]))

(defn colors-page-will-mount
  [own]
  (rs/emit! (dd/initialize :dashboard/colors)
            (dc/initialize))
  own)

(defn colors-page-did-remount
  [old-state state]
  (rs/emit! (dd/initialize :dashboard/colors))
  state)

(def colors-page
  (mx/component
   {:render colors-page-render
    :will-mount colors-page-will-mount
    :did-remount colors-page-did-remount
    :name "colors"
    :mixins [mx/static]}))

;; --- Colors Create / Edit Lightbox

(defn- color-lightbox-render
  [own {:keys [coll color]}]
  (html
  (let [local (:rum/local own)]
    (letfn [(submit [e]
              (let [params {:id (:id coll) :from color :to (:hex @local)}]
                (rs/emit! (dc/replace-color params))
                (udl/close!)))
            (on-change [e]
              (let [value (str/trim (dom/event->value e))]
                (swap! local assoc :hex value)))]
      (html
       [:div.lightbox-body
        [:h3 "New color"]
        [:form
         [:div.row-flex.center
          (colorpicker
           :value (or (:hex @local) color "#00ccff")
           :on-change #(swap! local assoc :hex %))]

         [:input#project-btn.btn-primary
          {:value "+ Add color"
           :on-click submit
           :type "button"}]]
        [:a.close {:on-click #(udl/close!)}
       i/close]])))))

(def color-lightbox
  (mx/component
   {:render color-lightbox-render
    :name "color-lightbox"
    :mixins [(rum/local {})
             mx/static]}))

(defmethod lbx/render-lightbox :color-form
  [params]
  (color-lightbox params))
