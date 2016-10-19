;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) 2015-2016 Andrey Antukh <niwi@niwi.nz>
;; Copyright (c) 2015-2016 Juan de la Cruz <delacruzgarciajuan@gmail.com>

(ns uxbox.main.ui.dashboard.elements
  (:require [sablono.core :as html :refer-macros [html]]
            [rum.core :as rum]
            [uxbox.util.rstore :as rs]
            [uxbox.main.data.dashboard :as dd]
            [uxbox.main.data.lightbox :as udl]
            [uxbox.main.ui.icons :as i]
            [uxbox.util.mixins :as mx :include-macros true]
            [uxbox.main.ui.lightbox :as lbx]
            [uxbox.main.ui.library-bar :as ui.library-bar]
            [uxbox.main.ui.dashboard.header :refer (header)]
            [uxbox.util.dom :as dom]))

;; --- Page Title

(defn page-title-render
  []
  (html
   [:div.dashboard-title
    [:h2 "Element library name"]
    [:div.edition
     [:span i/pencil]
     [:span i/trash]]]))

(def ^:private page-title
  (mx/component
   {:render page-title-render
    :name "page-title"
    :mixins [mx/static]}))

;; --- Grid

(defn grid-render
  [own]
  (html
   [:div.dashboard-grid-content
    [:div.dashboard-grid-row
      [:div.grid-item.add-project
       {on-click #(udl/open! :new-element)}
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
        [:div.project-th-icon.delete i/trash]]]]]))

(def ^:private grid
  (mx/component
   {:render grid-render
    :name "grid"
    :mixins [mx/static]}))

;; --- Elements Page

(defn elements-page-render
  [own]
  (html
   [:main.dashboard-main
    (header)
    [:section.dashboard-content
     (ui.library-bar/library-bar)
     [:section.dashboard-grid.library
      (page-title)
      (grid)]]]))

(defn elements-page-will-mount
  [own]
  (rs/emit! (dd/initialize :dashboard/elements))
  own)

(defn elements-page-did-remount
  [old-state state]
  (rs/emit! (dd/initialize :dashboard/elements))
  state)

(def elements-page
  (mx/component
   {:render elements-page-render
    :will-mount elements-page-will-mount
    :did-remount elements-page-did-remount
    :name "elements-page"
    :mixins [mx/static]}))

;; --- New Element Lightbox (TODO)

(defn- new-element-lightbox-render
  [own]
  (html
  ;;------Element lightbox

   ;;[:div.lightbox-body
    ;;[:h3 "New element"]
    ;;[:div.row-flex
     ;;[:div.lightbox-big-btn
      ;;[:span.big-svg i/shapes]
      ;;[:span.text "Go to workspace"]]
     ;;[:div.lightbox-big-btn
      ;;[:span.big-svg.upload i/exit]
      ;;[:span.text "Upload file"]]]
    ;;[:a.close {:href "#"
               ;;:on-click #(do (dom/prevent-default %)
                              ;;(udl/close!))}
     ;;i/close]]

  ;;------Upload image lightbox

   ;;[:div.lightbox-body
    ;;[:h3 "Import image"]
    ;;[:div.row-flex
     ;;[:div.lightbox-big-btn
      ;;[:span.big-svg i/image]
      ;;[:span.text "Select from library"]]
     ;;[:div.lightbox-big-btn
      ;;[:span.big-svg.upload i/exit]
      ;;[:span.text "Upload file"]]]
    ;;[:a.close {:href "#"
               ;;:on-click #(do (dom/prevent-default %)
                              ;;(udl/close!))}
     ;;i/close]]

  ;;------Upload image library lightbox

   [:div.lightbox-body.big-lightbox
    [:h3 "Import image from library"]
    [:div.import-img-library
     [:div.library-actions
      [:ul.toggle-library
       [:li.standard.current "STANDARD"]
       [:li.your-images "YOUR IMAGES"]]
      [:select.input-select
       [:option "Library 1"]
       [:option "Library 2"]
       [:option "Library 3"]
       [:option "Library 4"]
       [:option "Library 5"]
       [:option "Library 6"]]]

     [:div.library-content

       [:div.library-item
        [:div.library-item-th
         {:style
           {:background-image "url('https://images.unsplash.com/photo-1441986380878-c4248f5b8b5b?ixlib=rb-0.3.5&q=80&fm=jpg&crop=entropy&w=1080&fit=max&s=486f09671860a11e70bdd0a45e7c5014')"}}]
        [:span "image_name.jpg"]]

       [:div.library-item
        [:div.library-item-th
         {:style
           {:background-image "url('https://images.unsplash.com/photo-1441986380878-c4248f5b8b5b?ixlib=rb-0.3.5&q=80&fm=jpg&crop=entropy&w=1080&fit=max&s=486f09671860a11e70bdd0a45e7c5014')"}}]
        [:span "image_name.jpg"]]

       [:div.library-item
        [:div.library-item-th
         {:style
           {:background-image "url('https://images.unsplash.com/photo-1441986380878-c4248f5b8b5b?ixlib=rb-0.3.5&q=80&fm=jpg&crop=entropy&w=1080&fit=max&s=486f09671860a11e70bdd0a45e7c5014')"}}]
        [:span "image_name.jpg"]]

       [:div.library-item
        [:div.library-item-th
         {:style
           {:background-image "url('https://images.unsplash.com/photo-1441986380878-c4248f5b8b5b?ixlib=rb-0.3.5&q=80&fm=jpg&crop=entropy&w=1080&fit=max&s=486f09671860a11e70bdd0a45e7c5014')"}}]
        [:span "image_name.jpg"]]

       [:div.library-item
        [:div.library-item-th
         {:style
           {:background-image "url('https://images.unsplash.com/photo-1441986380878-c4248f5b8b5b?ixlib=rb-0.3.5&q=80&fm=jpg&crop=entropy&w=1080&fit=max&s=486f09671860a11e70bdd0a45e7c5014')"}}]
        [:span "image_name.jpg"]]

       [:div.library-item
        [:div.library-item-th
         {:style
           {:background-image "url('https://images.unsplash.com/photo-1441986380878-c4248f5b8b5b?ixlib=rb-0.3.5&q=80&fm=jpg&crop=entropy&w=1080&fit=max&s=486f09671860a11e70bdd0a45e7c5014')"}}]
        [:span "image_name.jpg"]]

       [:div.library-item
        [:div.library-item-th
         {:style
           {:background-image "url('https://images.unsplash.com/photo-1441986380878-c4248f5b8b5b?ixlib=rb-0.3.5&q=80&fm=jpg&crop=entropy&w=1080&fit=max&s=486f09671860a11e70bdd0a45e7c5014')"}}]
        [:span "image_name.jpg"]]

       [:div.library-item
        [:div.library-item-th
         {:style
           {:background-image "url('https://images.unsplash.com/photo-1441986380878-c4248f5b8b5b?ixlib=rb-0.3.5&q=80&fm=jpg&crop=entropy&w=1080&fit=max&s=486f09671860a11e70bdd0a45e7c5014')"}}]
        [:span "image_name.jpg"]]

       [:div.library-item
        [:div.library-item-th
         {:style
           {:background-image "url('https://images.unsplash.com/photo-1441986380878-c4248f5b8b5b?ixlib=rb-0.3.5&q=80&fm=jpg&crop=entropy&w=1080&fit=max&s=486f09671860a11e70bdd0a45e7c5014')"}}]
        [:span "image_name.jpg"]]

       [:div.library-item
        [:div.library-item-th
         {:style
           {:background-image "url('https://images.unsplash.com/photo-1441986380878-c4248f5b8b5b?ixlib=rb-0.3.5&q=80&fm=jpg&crop=entropy&w=1080&fit=max&s=486f09671860a11e70bdd0a45e7c5014')"}}]
        [:span "image_name.jpg"]]

       [:div.library-item
        [:div.library-item-th
         {:style
           {:background-image "url('https://images.unsplash.com/photo-1441986380878-c4248f5b8b5b?ixlib=rb-0.3.5&q=80&fm=jpg&crop=entropy&w=1080&fit=max&s=486f09671860a11e70bdd0a45e7c5014')"}}]
        [:span "image_name.jpg"]]

       [:div.library-item
        [:div.library-item-th
         {:style
           {:background-image "url('https://images.unsplash.com/photo-1441986380878-c4248f5b8b5b?ixlib=rb-0.3.5&q=80&fm=jpg&crop=entropy&w=1080&fit=max&s=486f09671860a11e70bdd0a45e7c5014')"}}]
        [:span "image_name.jpg"]]

       [:div.library-item
        [:div.library-item-th
         {:style
           {:background-image "url('https://images.unsplash.com/photo-1441986380878-c4248f5b8b5b?ixlib=rb-0.3.5&q=80&fm=jpg&crop=entropy&w=1080&fit=max&s=486f09671860a11e70bdd0a45e7c5014')"}}]
        [:span "image_name.jpg"]]

       [:div.library-item
        [:div.library-item-th
         {:style
           {:background-image "url('https://images.unsplash.com/photo-1441986380878-c4248f5b8b5b?ixlib=rb-0.3.5&q=80&fm=jpg&crop=entropy&w=1080&fit=max&s=486f09671860a11e70bdd0a45e7c5014')"}}]
        [:span "image_name.jpg"]]

     ]]
    [:a.close {:href "#"
               :on-click #(do (dom/prevent-default %)
                              (udl/close!))}
     i/close]]

     ))

(def ^:private new-element-lightbox
  (mx/component
   {:render new-element-lightbox-render
    :name "new-element-lightbox"}))

(defmethod lbx/render-lightbox :new-element
  [_]
  (new-element-lightbox))
