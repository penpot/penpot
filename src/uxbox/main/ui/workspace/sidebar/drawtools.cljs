;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) 2015-2016 Andrey Antukh <niwi@niwi.nz>
;; Copyright (c) 2015-2016 Juan de la Cruz <delacruzgarciajuan@gmail.com>

(ns uxbox.main.ui.workspace.sidebar.drawtools
  (:require [sablono.core :as html :refer-macros [html]]
            [rum.core :as rum]
            [lentes.core :as l]
            [uxbox.util.i18n :refer (tr)]
            [uxbox.util.router :as r]
            [uxbox.util.rstore :as rs]
            [uxbox.main.state :as st]
            [uxbox.main.library :as library]
            [uxbox.util.data :refer (read-string)]
            [uxbox.main.data.workspace :as dw]
            [uxbox.main.ui.workspace.base :as wb]
            [uxbox.main.ui.icons :as i]
            [uxbox.util.mixins :as mx]
            [uxbox.util.dom :as dom]))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Lenses
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(def ^:private drawing-shape
  "A focused vision of the drawing property
  of the workspace status. This avoids
  rerender the whole toolbox on each workspace
  change."
  (as-> (l/in [:workspace :drawing]) $
    (l/derive $ st/state)))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Draw Tools
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(def ^:const +draw-tool-rect+
  {:type :rect
   :name "Rect"
   :stroke "#000000"})

(def ^:const +draw-tool-circle+
  {:type :circle
   :name "Circle"})

(def ^:const +draw-tool-line+
  {:type :line
   :name "Line"
   :stroke-type :solid
   :stroke "#000000"})

(def ^:const +draw-tool-text+
  {:type :text
   :name "Text"
   :content "Hello world"})

(def ^:const +draw-tools+
  {:rect
   {:icon i/box
    :help (tr "ds.help.rect")
    :shape +draw-tool-rect+
    :priority 1}
   :circle
   {:icon i/circle
    :help (tr "ds.help.circle")
    :shape +draw-tool-circle+
    :priority 2}
   :line
   {:icon i/line
    :help (tr "ds.help.line")
    :shape +draw-tool-line+
    :priority 3}
   :text
   {:icon i/text
    :help (tr "ds.help.text")
    :shape +draw-tool-text+
    :priority 4}})

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Draw Tool Box
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(defn- select-for-draw
  [shape]
  (rs/emit! (dw/select-for-drawing shape)))

(defn draw-tools-render
  [open-toolboxes]
  (let [workspace (rum/react wb/workspace-l)
        drawing (rum/react drawing-shape)
        close #(rs/emit! (dw/toggle-flag :drawtools))
        tools (->> (into [] +draw-tools+)
                   (sort-by (comp :priority second)))]
    (html
     [:div#form-tools.tool-window.drawing-tools
      [:div.tool-window-bar
       [:div.tool-window-icon i/window]
       [:span (tr "ds.draw-tools")]
       [:div.tool-window-close {:on-click close} i/close]]
      [:div.tool-window-content
       (for [[key props] tools
             :let [selected? (= drawing (:shape props))]]
         [:div.tool-btn.tooltip.tooltip-hover
          {:alt (:help props)
           :class (when selected? "selected")
           :key (name key)
           :on-click (partial select-for-draw (:shape props))}
          (:icon props)])]])))

(def draw-toolbox
  (mx/component
   {:render draw-tools-render
    :name "draw-tools"
    :mixins [mx/static rum/reactive]}))
