;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) 2015-2016 Andrey Antukh <niwi@niwi.nz>
;; Copyright (c) 2015-2016 Juan de la Cruz <delacruzgarciajuan@gmail.com>

(ns uxbox.main.ui.workspace.sidebar.options.interactions
  (:require [sablono.core :as html :refer-macros [html]]
            [rum.core :as rum]
            [lentes.core :as l]
            [uxbox.locales :refer (tr)]
            [uxbox.router :as r]
            [uxbox.rstore :as rs]
            [uxbox.state :as st]
            [uxbox.library :as library]
            [uxbox.data.shapes :as uds]
            [uxbox.data.lightbox :as udl]
            [uxbox.ui.icons :as i]
            [uxbox.ui.mixins :as mx]
            [uxbox.util.dom :as dom]
            [uxbox.util.data :refer (parse-int parse-float read-string)]))

(defn- interactions-menu-render
  [own menu shape]
    (html
     [:div.element-set {:key (str (:id menu))}
      [:div.element-set-title (:name menu)]

      [:div.element-set-content

       [:span "Trigger"]
       [:div.row-flex
        [:select#style.input-select {:placeholder "Choose a trigger"
                                     :value ""}
         [:option {:value ":click"} "Click"]
         [:option {:value ":doubleclick"} "Double-click"]
         [:option {:value ":rightclick"} "Right-click"]
         [:option {:value ":hover"} "Hover"]
         [:option {:value ":mousein"} "Mouse in"]]]

       [:span "Action"]
       [:div.row-flex
        [:select#style.input-select {:placeholder "Choose an action"
                                     :value ""}
         [:option {:value ":show"} "Show"]
         [:option {:value ":hide"} "Hide"]
         [:option {:value ":toggle"} "Toggle"]
         [:option {:value ":moveto"} "Move to"]
         [:option {:value ":moveby"} "Move by"]]]

       [:span "Element"]
       [:div.row-flex
        [:select#style.input-select {:placeholder "Choose an element"
                                     :value ""}
         [:option {:value ":1"} "Box 1"]
         [:option {:value ":2"} "Circle 1"]
         [:option {:value ":3"} "Circle 2"]
         [:option {:value ":4"} "Icon 1"]
         [:option {:value ":5"} "Icon 2"]]]

       [:span "Page"]
       [:div.row-flex
        [:select#style.input-select {:placeholder "Choose a page"
                                     :value ""}
         [:option {:value ":1"} "page 1"]
         [:option {:value ":2"} "page 2"]
         [:option {:value ":3"} "page 3"]
         [:option {:value ":4"} "page 4"]
         [:option {:value ":5"} "page 5"]]]

       [:span "Key"]
       [:div.row-flex
        [:select#style.input-select {:placeholder "Choose a key"
                                     :value ""}
         [:option {:value ":1"} "key 1"]
         [:option {:value ":2"} "key 2"]
         [:option {:value ":3"} "key 3"]
         [:option {:value ":4"} "key 4"]
         [:option {:value ":5"} "key 5"]]]]
         ]))

(def interactions-menu
  (mx/component
   {:render interactions-menu-render
    :name "interactions-menu"
    :mixed [mx/static]}))
