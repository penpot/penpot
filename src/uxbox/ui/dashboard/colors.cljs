;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) 2015-2016 Andrey Antukh <niwi@niwi.nz>
;; Copyright (c) 2015-2016 Juan de la Cruz <delacruzgarciajuan@gmail.com>

(ns uxbox.ui.dashboard.colors
  (:require [sablono.core :refer-macros [html]]
            [rum.core :as rum]
            [cuerdas.core :as str]
            [lentes.core :as l]
            [uxbox.locales :as t :refer (tr)]
            [uxbox.state :as st]
            [uxbox.rstore :as rs]
            [uxbox.schema :as sc]
            [uxbox.library :as library]
            [uxbox.data.dashboard :as dd]
            [uxbox.util.lens :as ul]
            [uxbox.util.color :refer (hex->rgb)]
            [uxbox.ui.icons :as i]
            [uxbox.ui.form :as form]
            [uxbox.ui.lightbox :as lightbox]
            [uxbox.ui.colorpicker :refer (colorpicker)]
            [uxbox.util.dom :as dom]
            [uxbox.ui.mixins :as mx]))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Lenses
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(def ^:static ^:private dashboard-l
  (-> (l/in [:dashboard])
      (l/focus-atom st/state)))

(def ^:static ^:private collections-by-id-l
  (-> (comp (l/in [:colors-by-id])
            (ul/merge library/+color-collections-by-id+))
      (l/focus-atom st/state)))

(defn- focus-collection
  [collid]
  (-> (l/key collid)
      (l/focus-atom collections-by-id-l)))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Page Title
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(defn page-title-render
  [own coll]
  (letfn [(on-title-edited [e]
            (let [content (dom/event->inner-text e)
                  collid (:id coll)]
              (rs/emit! (dd/rename-color-collection collid content))))
          (on-delete [e]
            (rs/emit! (dd/delete-color-collection (:id coll))))]
    (let [dashboard (rum/react dashboard-l)
          own? (:builtin coll false)]
      (html
       [:div.dashboard-title {}
        [:h2 {}
         [:span #ux/tr "ds.library-title"]
         [:span {:content-editable ""
                 :on-key-up on-title-edited}
          (:name coll)]]
        (if (and (not own?) coll)
          [:div.edition {}
           #_[:span i/pencil]
           [:span {:on-click on-delete} i/trash]])]))))

(def ^:static page-title
  (mx/component
   {:render page-title-render
    :name "page-title"
    :mixins [mx/static rum/reactive]}))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Nav
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

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
        (when own?
          [:li
           [:a.btn-primary
            {:on-click #(rs/emit! (dd/mk-color-collection))}
            "+ New library"]])
        (for [props collections
              :let [num (count (:colors props))]]
          [:li {:key (str (:id props))
                :on-click #(rs/emit! (dd/set-collection (:id props)))
                :class-name (when (= (:id props) collid) "current")}
           [:span.element-title (:name props)]
           [:span.element-subtitle
            (tr "ds.num-elements" (t/c num))]])]]])))

(def ^:static nav
  (mx/component
   {:render nav-render
    :name "nav"
    :mixins [rum/reactive]}))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Grid
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(defn grid-render
  [own]
  (let [dashboard (rum/react dashboard-l)
        coll-type (:collection-type dashboard)
        coll-id (:collection-id dashboard)
        own? (= coll-type :own)
        coll (rum/react (focus-collection coll-id))
        edit-cb #(lightbox/open! :color-form {:coll coll :color %})
        remove-cb #(rs/emit! (dd/remove-color {:id (:id coll) :color %}))]
    (when coll
      (html
       [:section.dashboard-grid.library
        (page-title coll)
        [:div.dashboard-grid-content
         (when own?
           [:div.grid-item.small-item.add-project
            {:on-click #(lightbox/open! :color-form {:coll coll})}
            [:span "+ New color"]])
         (for [color (remove nil? (:colors coll))
               :let [color-rgb (hex->rgb color)]]
           [:div.grid-item.small-item.project-th {:key color}
            [:span.color-swatch {:style {:background-color color}}]
            [:span.color-data color]
            [:span.color-data (apply str "RGB " (interpose ", " color-rgb))]
            (if own?
              [:div.project-th-actions
               [:div.project-th-icon.edit
                {:on-click #(edit-cb color)} i/pencil]
               [:div.project-th-icon.delete
                {:on-click #(remove-cb color)}
                i/trash]])])]]))))

(def grid
  (mx/component
   {:render grid-render
    :name "grid"
    :mixins [mx/static rum/reactive]}))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Lightbox
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(def ^:static +color-form-schema+
  {:hex [sc/required sc/color]})

(defn- color-lightbox-render
  [own {:keys [coll color]}]
  (let [local (:rum/local own)]
    (letfn [(submit [e]
              (if-let [errors (sc/validate +color-form-schema+ @local)]
                (swap! local assoc :errors errors)
                (let [params {:id (:id coll) :from color
                              :to (:hex @local)}]
                  (rs/emit! (dd/replace-color params))
                  (lightbox/close!))))
            (on-change [e]
              (let [value (str/trim (dom/event->value e))]
                (swap! local assoc :hex value)))]
      (html
       [:div.lightbox-body
        [:h3 "New color"]
        [:form
         [:div.row-flex
          [:input#color-hex.input-text
           {:placeholder "#"
            :class (form/error-class local :hex)
            :on-change on-change
            :value (or (:hex @local) color "")
            :type "text"}]]
         (colorpicker :library #(swap! local merge %))
         [:input#project-btn.btn-primary
          {:value "+ Add color"
           :on-click submit
           :type "button"}]]
        [:a.close {:on-click #(lightbox/close!)}
       i/close]]))))

(def color-lightbox
  (mx/component
   {:render color-lightbox-render
    :name "color-lightbox"
    :mixins [(rum/local {})
             mx/static]}))

(defmethod lightbox/render-lightbox :color-form
  [params]
  (color-lightbox params))
