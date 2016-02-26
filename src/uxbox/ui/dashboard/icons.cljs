(ns uxbox.ui.dashboard.icons
  (:require [sablono.core :refer-macros [html]]
            [rum.core :as rum]
            [cuerdas.core :as str]
            [cats.labs.lens :as l]
            [uxbox.state :as st]
            [uxbox.rstore :as rs]
            [uxbox.schema :as sc]
            [uxbox.library :as library]
            [uxbox.data.dashboard :as dd]
            [uxbox.util.lens :as ul]
            [uxbox.ui.icons :as i]
            [uxbox.ui.form :as form]
            [uxbox.ui.shapes.core :as uusc]
            [uxbox.ui.lightbox :as lightbox]
            [uxbox.util.dom :as dom]
            [uxbox.ui.mixins :as mx]))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Lenses
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(def ^:static dashboard-l
  (as-> (l/in [:dashboard]) $
    (l/focus-atom $ st/state)))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Page Title
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(defn page-title-render
  [own coll]
  (let [dashboard (rum/react dashboard-l)
        own? (:builtin coll false)]
    (html
     [:div.dashboard-title {}
      [:h2 {}
       [:span #ux/tr "ds.library-title"]
       [:span {:content-editable ""
               :on-key-up (constantly nil)}
        (:name coll)]]
      (if (and (not own?) coll)
        [:div.edition {}
         [:span {:on-click (constantly nil)}
          i/trash]])])))

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
        collid (:collection-id dashboard)
        own? (= (:collection-type dashboard) :own)
        builtin? (= (:collection-type dashboard) :builtin)
        collections (if own?
                      [] #_(sort-by :id (vals colors))
                      library/+icon-collections+)]
    (html
     [:div.library-bar
      [:div.library-bar-inside
       [:ul.library-tabs
        [:li {:class-name (when builtin? "current")
              :on-click #(rs/emit! (dd/set-collection-type :builtin))}
         #ux/tr "ds.standard-title"]
        [:li {:class-name (when own? "current")
              :on-click #(rs/emit! (dd/set-collection-type :own))}
         #ux/tr "ds.your-libraries-title"]]
       [:ul.library-elements
        (when own?
          [:li
           [:a.btn-primary
            {:on-click (constantly nil)}
            "+ New library"]])
        (for [props collections]
          [:li {:key (str (:id props))
                :on-click #(rs/emit! (dd/set-collection (:id props)))
                :class-name (when (= (:id props) collid) "current")}
           [:span.element-title (:name props)]
           [:span.element-subtitle
            (str (count (:icons props)) " elements")]])]]])))

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
        coll (get library/+icon-collections-by-id+ coll-id)]
    (when coll
      (html
       [:section.dashboard-grid.library
        (page-title coll)
        [:div.dashboard-grid-content
         (for [icon (:icons coll)]
           [:div.grid-item.small-item.project-th {}
            [:span.grid-item-image #_i/toggle (uusc/render-shape-svg icon nil)]
            [:h3 (:name icon)]
            #_[:div.project-th-actions
             [:div.project-th-icon.edit i/pencil]
             [:div.project-th-icon.delete i/trash]]])]]))))

(def grid
  (mx/component
   {:render grid-render
    :name "grid"
    :mixins [mx/static rum/reactive]}))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Lightbox
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(defn- new-icon-lightbox-render
  [own]
  (html
   [:div.lightbox-body
    [:h3 "New icon"]
    [:div.row-flex
     [:div.lightbox-big-btn
      [:span.big-svg i/shapes]
      [:span.text "Go to workspace"]
      ]
     [:div.lightbox-big-btn
      [:span.big-svg.upload i/exit]
      [:span.text "Upload file"]
      ]
     ]
    [:a.close {:href "#"
               :on-click #(do (dom/prevent-default %)
                              (lightbox/close!))}
     i/close]]))

(def new-icon-lightbox
  (mx/component
   {:render new-icon-lightbox-render
    :name "new-icon-lightbox"}))
