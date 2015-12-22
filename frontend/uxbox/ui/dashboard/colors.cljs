(ns uxbox.ui.dashboard.colors
  (:require [sablono.core :as html :refer-macros [html]]
            [rum.core :as rum]
            [cats.labs.lens :as l]
            [uxbox.state :as s]
            [uxbox.rstore :as rs]
            [uxbox.data.dashboard :as dd]
            [uxbox.ui.dashboard.builtins :as builtins]
            [uxbox.ui.icons :as i]
            [uxbox.ui.lightbox :as lightbox]
            [uxbox.ui.colorpicker :refer (colorpicker)]
            [uxbox.ui.dom :as dom]
            [uxbox.ui.mixins :as mx]
            [uxbox.ui.util :as util]))

(defn- get-collection
  [state type id]
  (case type
    :builtin (get builtins/+color-collections-by-id+ id)
    :own (get-in state [:colors-by-id id])))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Lenses
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(def ^:static dashboard-state
  (as-> (l/in [:dashboard]) $
    (l/focus-atom $ s/state)))

(def ^:static colors-state
  (as-> (l/in [:colors-by-id]) $
    (l/focus-atom $ s/state)))

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
  []
  (let [dashboard (rum/react dashboard-state)
        own? (= (:collection-type dashboard) :own)
        coll (get-collection {}
                             (:collection-type dashboard)
                             (:collection-id dashboard))]
    (html
     [:div.dashboard-title
      (if coll
        [:h2 (str "Library: " (:name coll))]
        [:h2 "No library selected"])
      (when (and own? coll)
        [:div.edition
         [:span i/pencil]
         [:span i/trash]])])))

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
        colors (rum/react colors-state)
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
           [:a.btn-primary {:href "#"} "+ New library"]])
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
        own? (= (:collection-type dashboard) :own)
        coll (get-collection {}
                             (:collection-type dashboard)
                             (:collection-id dashboard))]
    (when coll
      (html
       [:div.dashboard-grid-content
        (when own?
          [:div.grid-item.small-item.add-project
           {:on-click #(lightbox/set! :new-color)}
           [:span "+ New color"]])
        (for [color (:colors coll)
              :let [color-str (name color)
                  color-hex (str "#" color-str)
                    color-rgb (util/hex->rgb color-hex)]]
          [:div.grid-item.small-item.project-th {:key color-str}
           [:span.color-swatch {:style {:background-color color-hex}}]
           [:span.color-data (str "#" color-str)]
           [:span.color-data (apply str "RGB " (interpose ", " color-rgb))]
           (when own?
             [:div.project-th-actions
              [:div.project-th-icon.edit i/pencil]
              [:div.project-th-icon.delete i/trash]])])]))))

(def grid
  (util/component
   {:render grid-render
    :name "colors"
    :mixins [mx/static rum/reactive]}))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Lightbox
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(defn- new-color-lightbox-render
  [own]
   (html
    [:div.lightbox-body
      [:h3 "New color"]
      [:form
        [:div.row-flex
          [:input#color-hex.input-text
            {:placeholder "#"
            :type "text"}]
          [:input#color-rgb.input-text
            {:placeholder "RGB"
            :type "text"}]]
       (colorpicker (fn [{:keys [rgb hex]}]
                      (println "HEX:" hex)
                      (println "RGB:" rgb)))
       [:input#project-btn.btn-primary {:value "+ Add color" :type "submit"}]]
     [:a.close {:href "#"
                :on-click #(do (dom/prevent-default %)
                               (lightbox/close!))}
      i/close]]))

(def new-color-lightbox
  (util/component
   {:render new-color-lightbox-render
    :name "new-color-lightbox"}))

(defmethod lightbox/render-lightbox :new-color
  [_]
  (new-color-lightbox))
