;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) 2015-2016 Andrey Antukh <niwi@niwi.nz>
;; Copyright (c) 2015-2016 Juan de la Cruz <delacruzgarciajuan@gmail.com>

(ns uxbox.ui.dashboard.elements
  (:require [sablono.core :as html :refer-macros [html]]
            [rum.core :as rum]
            ;; [uxbox.ui.library-bar :as ui.library-bar]
            [uxbox.ui.icons :as i]
            [uxbox.ui.lightbox :as lightbox]
            [uxbox.util.dom :as dom]
            [uxbox.ui.mixins :as mx]))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Page Title
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(defn page-title-render
  []
  (html
   [:div.dashboard-title
    [:h2 "Element library name"]
    [:div.edition
     [:span i/pencil]
     [:span i/trash]]]))

(def ^:static page-title
  (mx/component
   {:render page-title-render
    :name "page-title"
    :mixins [mx/static]}))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Grid
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(defn grid-render
  [own]
  (html
   [:div.dashboard-grid-content
    [:div.grid-item.add-project
     {on-click #(lightbox/open! :new-element)}
     [:span "+ New element"]]
    [:div.grid-item.project-th
     [:span.grid-item-image i/image]
            [:h3 "Custom element"]
     [:div.project-th-actions
      [:div.project-th-icon.edit i/pencil]
      [:div.project-th-icon.delete i/trash]]]
    [:div.grid-item.project-th
            [:span.grid-item-image i/image]
     [:h3 "Custom element"]
     [:div.project-th-actions
      [:div.project-th-icon.edit i/pencil]
      [:div.project-th-icon.delete i/trash]]]
    [:div.grid-item.project-th
     [:span.grid-item-image i/image]
     [:h3 "Custom element"]
     [:div.project-th-actions
      [:div.project-th-icon.edit i/pencil]
      [:div.project-th-icon.delete i/trash]]]
    [:div.grid-item.project-th
     [:span.grid-item-image i/image]
     [:h3 "Custom element"]
     [:div.project-th-actions
      [:div.project-th-icon.edit i/pencil]
      [:div.project-th-icon.delete i/trash]]]
    [:div.grid-item.project-th
     [:span.grid-item-image i/image]
     [:h3 "Custom element"]
     [:div.project-th-actions
      [:div.project-th-icon.edit i/pencil]
      [:div.project-th-icon.delete i/trash]]]
    [:div.grid-item.project-th
     [:span.grid-item-image i/image]
     [:h3 "Custom element"]
     [:div.project-th-actions
      [:div.project-th-icon.edit i/pencil]
      [:div.project-th-icon.delete i/trash]]]
    [:div.grid-item.project-th
     [:span.grid-item-image i/image]
     [:h3 "Custom element"]
     [:div.project-th-actions
      [:div.project-th-icon.edit i/pencil]
      [:div.project-th-icon.delete i/trash]]]
    [:div.grid-item.project-th
     [:span.grid-item-image i/image]
     [:h3 "Custom element"]
     [:div.project-th-actions
      [:div.project-th-icon.edit i/pencil]
      [:div.project-th-icon.delete i/trash]]]
    [:div.grid-item.project-th
     [:span.grid-item-image i/image]
     [:h3 "Custom element"]
     [:div.project-th-actions
      [:div.project-th-icon.edit i/pencil]
      [:div.project-th-icon.delete i/trash]]]
    [:div.grid-item.project-th
     [:span.grid-item-image i/image]
     [:h3 "Custom element"]
     [:div.project-th-actions
      [:div.project-th-icon.edit i/pencil]
      [:div.project-th-icon.delete i/trash]]]
    [:div.grid-item.project-th
     [:span.grid-item-image i/image]
     [:h3 "Custom element"]
     [:div.project-th-actions
      [:div.project-th-icon.edit i/pencil]
      [:div.project-th-icon.delete i/trash]]]
    [:div.grid-item.project-th
     [:span.grid-item-image i/image]
     [:h3 "Custom element"]
     [:div.project-th-actions
      [:div.project-th-icon.edit i/pencil]
      [:div.project-th-icon.delete i/trash]]]
    [:div.grid-item.project-th
     [:span.grid-item-image i/image]
     [:h3 "Custom element"]
     [:div.project-th-actions
      [:div.project-th-icon.edit i/pencil]
      [:div.project-th-icon.delete i/trash]]]]))

(def ^:static grid
  (mx/component
   {:render grid-render
    :name "grid"
    :mixins [mx/static]}))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Lightbox
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(defn- new-element-lightbox-render
  [own]
  (html
   [:div.lightbox-body
    [:h3 "New element"]
    [:div.row-flex
     [:div.lightbox-big-btn
      [:span.big-svg i/shapes]
      [:span.text "Go to workspace"]]
     [:div.lightbox-big-btn
      [:span.big-svg.upload i/exit]
      [:span.text "Upload file"]]]
    [:a.close {:href "#"
               :on-click #(do (dom/prevent-default %)
                              (lightbox/close!))}
     i/close]]))

(def new-element-lightbox
  (mx/component
   {:render new-element-lightbox-render
    :name "new-element-lightbox"}))

(defmethod lightbox/render-lightbox :new-element
  [_]
  (new-element-lightbox))
