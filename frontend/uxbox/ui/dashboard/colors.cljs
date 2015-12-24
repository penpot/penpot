(ns uxbox.ui.dashboard.colors
  (:require [sablono.core :refer-macros [html]]
            [rum.core :as rum]
            [cuerdas.core :as str]
            [cats.labs.lens :as l]
            [uxbox.state :as st]
            [uxbox.rstore :as rs]
            [uxbox.data.dashboard :as dd]
            [uxbox.util.lens :as ul]
            [uxbox.ui.dashboard.builtins :as builtins]
            [uxbox.ui.icons :as i]
            [uxbox.ui.lightbox :as lightbox]
            [uxbox.ui.colorpicker :refer (colorpicker)]
            [uxbox.ui.dom :as dom]
            [uxbox.ui.mixins :as mx]
            [uxbox.ui.util :as util]))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Lenses
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(def ^:static dashboard-state
  (as-> (l/in [:dashboard]) $
    (l/focus-atom $ st/state)))

(def ^:static collections-state
  (as-> (l/in [:colors-by-id]) $
    (l/focus-atom $ st/state)))

(def ^:static collection-state
  (as-> (ul/dep-in [:colors-by-id] [:dashboard :collection-id]) $
    (l/focus-atom $ st/state)))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Helpers
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(defn- get-collection
  [type id]
  (case type
    :builtin (get builtins/+color-collections-by-id+ id)
    :own (get-in @st/state [:colors-by-id id])))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Menu
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(defn menu-render
  []
  (let [pcount 20]
    (html
     [:section#dashboard-bar.dashboard-bar
      [:div.dashboard-info
       [:span.dashboard-projects pcount " projects"]
       [:span "Sort by"]]
      [:div.dashboard-search i/search]])))

(def ^:static menu
  (util/component
   {:render menu-render
    :name "icons-menu"
    :mixins [rum/reactive]}))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Page Title
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(defn page-title-render
  [own coll]
  (letfn [(on-title-edited [e]
            (let [content (.-innerText (.-target e))
                  collid (:id coll)]
              (rs/emit! (dd/rename-color-collection collid content))))
          (on-delete [e]
            (rs/emit! (dd/delete-color-collection (:id coll))))]
    (let [dashboard (rum/react dashboard-state)
          own? (:builtin coll false)]
      (html
       [:div.dashboard-title {}
        (if coll
          [:h2 {}
           [:span "Library: "]
           [:span {:content-editable ""
                   :on-key-up on-title-edited}
            (:name coll)]]
          [:h2 "No library selected"])
        (if (and (not own?) coll)
          [:div.edition {}
           #_[:span i/pencil]
           [:span {:on-click on-delete} i/trash]])]))))

(def ^:static page-title
  (util/component
   {:render page-title-render
    :name "page-title"
    :mixins [mx/static rum/reactive]}))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Nav
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(defn nav-render
  [own]
  (let [dashboard (rum/react dashboard-state)
        colors (rum/react collections-state)
        collid (:collection-id dashboard)
        own? (= (:collection-type dashboard) :own)
        builtin? (= (:collection-type dashboard) :builtin)
        collections (if own?
                      (sort-by :id (vals colors))
                      builtins/+color-collections+)]
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
        (for [props collections]
          [:li {:key (str (:id props))
                :on-click #(rs/emit! (dd/set-collection (:id props)))
                :class-name (when (= (:id props) collid) "current")}
           [:span.element-title (:name props)]
           [:span.element-subtitle
            (str (count (:colors props)) " elements")]])]]])))

(def ^:static nav
  (util/component
   {:render nav-render
    :name "nav"
    :mixins [rum/reactive]}))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Nav
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(defn grid-render
  [own]
  (let [dashboard (rum/react dashboard-state)
        coll-type (:collection-type dashboard)
        coll-id (:collection-id dashboard)
        own? (= coll-type :own)
        coll (case coll-type
               :builtin (get builtins/+color-collections-by-id+ coll-id)
               :own (rum/react collection-state))
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
               :let [color-rgb (util/hex->rgb color)]]
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
  (util/component
   {:render grid-render
    :name "grid"
    :mixins [mx/static rum/reactive]}))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Lightbox
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

;; TODO: implement proper form validation

(defn- color-lightbox-render
  [own {:keys [coll color]}]
  (let [local (:rum/local own)]
    (letfn [(submit [e]
              (let [params {:id (:id coll) :from color
                            :to (:hex @local)}]
                (rs/emit! (dd/replace-color params))
                (lightbox/close!)))
            (on-change [e]
              (let [value (str/trim (.-value (.-target e)))]
                (swap! local assoc :hex value)))]
      (html
       [:div.lightbox-body
        [:h3 "New color"]
        [:form
         [:div.row-flex
          [:input#color-hex.input-text
           {:placeholder "#"
            :on-change on-change
            :value (or (:hex @local) color "")
            :type "text"}]]
         (colorpicker #(swap! local merge %))
         [:input#project-btn.btn-primary
          {:value "+ Add color"
           :on-click submit
           :type "submit"}]]
        [:a.close {:on-click #(lightbox/close!)}
       i/close]]))))

(def color-lightbox
  (util/component
   {:render color-lightbox-render
    :name "color-lightbox"
    :mixins [(rum/local {})
             mx/static]}))

(defmethod lightbox/render-lightbox :color-form
  [params]
  (color-lightbox params))
