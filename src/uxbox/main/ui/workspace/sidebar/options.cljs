;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) 2015-2016 Andrey Antukh <niwi@niwi.nz>
;; Copyright (c) 2015-2016 Juan de la Cruz <delacruzgarciajuan@gmail.com>

(ns uxbox.main.ui.workspace.sidebar.options
  (:require [sablono.core :as html :refer-macros [html]]
            [rum.core :as rum]
            [lentes.core :as l]
            [uxbox.util.i18n :refer (tr)]
            [uxbox.util.router :as r]
            [uxbox.util.rstore :as rs]
            [uxbox.main.state :as st]
            [uxbox.main.library :as library]
            [uxbox.main.data.workspace :as udw]
            [uxbox.main.data.shapes :as uds]
            [uxbox.main.ui.workspace.base :as wb]
            [uxbox.main.ui.icons :as i]
            [uxbox.util.mixins :as mx]
            [uxbox.main.ui.workspace.colorpicker :refer (colorpicker)]
            [uxbox.main.ui.workspace.recent-colors :refer (recent-colors)]
            [uxbox.main.ui.workspace.sidebar.options.icon-measures :as options-iconm]
            [uxbox.main.ui.workspace.sidebar.options.circle-measures :as options-circlem]
            [uxbox.main.ui.workspace.sidebar.options.rect-measures :as options-rectm]
            [uxbox.main.ui.workspace.sidebar.options.line-measures :as options-linem]
            [uxbox.main.ui.workspace.sidebar.options.fill :as options-fill]
            [uxbox.main.ui.workspace.sidebar.options.text :as options-text]
            [uxbox.main.ui.workspace.sidebar.options.stroke :as options-stroke]
            [uxbox.main.ui.workspace.sidebar.options.interactions :as options-interactions]
            [uxbox.main.geom :as geom]
            [uxbox.util.dom :as dom]
            [uxbox.util.data :refer (parse-int parse-float read-string)]))

;; --- Constants

(def ^:private +menus-map+
  {:icon [:menu/icon-measures :menu/fill :menu/stroke :menu/interactions]
   :rect [:menu/rect-measures :menu/fill :menu/stroke :menu/interactions]
   :line [:menu/line-measures :menu/stroke :menu/interactions]
   :circle [:menu/circle-measures :menu/fill :menu/stroke :menu/interactions]
   :text [:menu/fill :menu/text :menu/interactions]
   :group []})

(def ^:private +menus+
  [{:name "Size, position & rotation"
    :id :menu/icon-measures
    :icon i/infocard
    :comp options-iconm/icon-measures-menu}
   {:name "Size, position & rotation"
    :id :menu/rect-measures
    :icon i/infocard
    :comp options-rectm/rect-measures-menu}
   {:name "Size, position & rotation"
    :id :menu/line-measures
    :icon i/infocard
    :comp options-linem/line-measures-menu}
   {:name "Size, position & rotation"
    :id :menu/circle-measures
    :icon i/infocard
    :comp options-circlem/circle-measures-menu}
   {:name "Fill"
    :id :menu/fill
    :icon i/fill
    :comp options-fill/fill-menu}
   {:name "Stroke"
    :id :menu/stroke
    :icon i/stroke
    :comp options-stroke/stroke-menu}
   {:name "Text"
    :id :menu/text
    :icon i/text
    :comp options-text/text-menu}
   {:name "Interactions"
    :id :menu/interactions
    :icon i/action
    :comp options-interactions/interactions-menu}])

(def ^:private +menus-by-id+
  (into {} (map #(vector (:id %) %)) +menus+))

;; --- Options

(defn- options-render
  [own shape]
  (let [local (:rum/local own)
        menus (get +menus-map+ (:type shape))
        active-menu (:menu @local (first menus))]
    (html
     [:div
      [:ul.element-icons
       (for [menu-id (get +menus-map+ (:type shape))
             :let [menu (get +menus-by-id+ menu-id)
                   selected? (= active-menu menu-id)]]
         [:li#e-info {:on-click #(swap! local assoc :menu menu-id)
                      :key (str "menu-" (:id menu))
                      :class (when selected? "selected")}
          (:icon menu)])]
      (when-let [menu (get +menus-by-id+ active-menu)]
        ((:comp menu) menu shape))])))

(def ^:private options
  (mx/component
   {:render options-render
    :name "options"
    :mixins [mx/static (mx/local)]}))

(def ^:const selected-shape-l
  (letfn [(getter [state]
            (let [selected (get-in state [:workspace :selected])]
              (when (= 1 (count selected))
                (get-in state [:shapes-by-id (first selected)]))))]
    (-> (l/lens getter)
        (l/derive st/state))))

(defn options-toolbox-render
  [own]
  (let [shape (rum/react selected-shape-l)
        close #(rs/emit! (udw/toggle-flag :element-options))]
    (html
     [:div.elementa-options.tool-window
      [:div.tool-window-bar
       [:div.tool-window-icon i/options]
       [:span (tr "ds.element-options")]
       [:div.tool-window-close {:on-click close} i/close]]
      [:div.tool-window-content
       [:div.element-options
        (if shape
          (options shape))]]])))

(def options-toolbox
  (mx/component
   {:render options-toolbox-render
    :name "options-toolbox"
    :mixins [mx/static mx/reactive (mx/local)]}))
