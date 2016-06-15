;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) 2015-2016 Andrey Antukh <niwi@niwi.nz>
;; Copyright (c) 2015-2016 Juan de la Cruz <delacruzgarciajuan@gmail.com>

(ns uxbox.main.ui.dashboard.icons
  (:require [sablono.core :refer-macros [html]]
            [rum.core :as rum]
            [lentes.core :as l]
            [uxbox.main.state :as st]
            [uxbox.common.rstore :as rs]
            [uxbox.common.schema :as sc]
            [uxbox.common.i18n :refer (tr)]
            [uxbox.main.library :as library]
            [uxbox.main.data.dashboard :as dd]
            [uxbox.main.data.lightbox :as udl]
            [uxbox.main.ui.icons :as i]
            [uxbox.main.ui.shapes.icon :as icon]
            [uxbox.main.ui.lightbox :as lbx]
            [uxbox.common.ui.mixins :as mx]
            [uxbox.main.ui.dashboard.header :refer (header)]
            [uxbox.util.dom :as dom]))

;; --- Lenses

(def ^:static dashboard-l
  (as-> (l/in [:dashboard]) $
    (l/focus-atom $ st/state)))

;; --- Page Title

(defn- page-title-render
  [own coll]
  (let [dashboard (rum/react dashboard-l)
        own? (:builtin coll false)]
    (html
     [:div.dashboard-title {}
      [:h2 {}
       [:span (tr "ds.library-title")]
       [:span {:content-editable ""
               :on-key-up (constantly nil)}
        (:name coll)]]
      (if (and (not own?) coll)
        [:div.edition {}
         [:span {:on-click (constantly nil)}
          i/trash]])])))

(def ^:const ^:private page-title
  (mx/component
   {:render page-title-render
    :name "page-title"
    :mixins [mx/static rum/reactive]}))

;; --- Nav

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
         (tr "ds.standard-title")]
        [:li {:class-name (when own? "current")
              :on-click #(rs/emit! (dd/set-collection-type :own))}
         (tr "ds.your-libraries-title")]]
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

(def ^:const ^:private nav
  (mx/component
   {:render nav-render
    :name "nav"
    :mixins [rum/reactive]}))

;; --- Grid

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
          [:div.dashboard-grid-row
           (for [icon (:icons coll)]
             [:div.grid-item.small-item.project-th {}
              [:span.grid-item-image (icon/icon-svg icon)]
              [:h3 (:name icon)]
              #_[:div.project-th-actions
               [:div.project-th-icon.edit i/pencil]
               [:div.project-th-icon.delete i/trash]]])]]]))))

(def grid
  (mx/component
   {:render grid-render
    :name "grid"
    :mixins [mx/static rum/reactive]}))

;; --- Icons Page

(defn icons-page-render
  [own]
  (html
   [:main.dashboard-main
    (header)
    [:section.dashboard-content
     (nav)
     (grid)]]))

(defn icons-page-will-mount
  [own]
  (rs/emit! (dd/initialize :dashboard/icons))
  own)

(defn icons-page-transfer-state
  [old-state state]
  (rs/emit! (dd/initialize :dashboard/icons))
  state)

(def icons-page
  (mx/component
   {:render icons-page-render
    :will-mount icons-page-will-mount
    :transfer-state icons-page-transfer-state
    :name "icons-page"
    :mixins [mx/static]}))

;; --- New Icon Lightbox (TODO)

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
                              (udl/close!))}
     i/close]]))

(def new-icon-lightbox
  (mx/component
   {:render new-icon-lightbox-render
    :name "new-icon-lightbox"}))

(defmethod lbx/render-lightbox :new-icon
  [_]
  (new-icon-lightbox))
