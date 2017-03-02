;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) 2015-2016 Andrey Antukh <niwi@niwi.nz>
;; Copyright (c) 2015-2016 Juan de la Cruz <delacruzgarciajuan@gmail.com>

(ns uxbox.main.ui.workspace.sidebar.drawtools
  (:require [sablono.core :as html :refer-macros [html]]
            [lentes.core :as l]
            [potok.core :as ptk]
            [uxbox.main.store :as st]
            [uxbox.main.refs :as refs]
            [uxbox.main.data.workspace :as dw]
            [uxbox.main.user-events :as uev]
            [uxbox.builtins.icons :as i]
            [uxbox.util.uuid :as uuid]
            [uxbox.util.i18n :refer (tr)]
            [uxbox.util.router :as r]
            [uxbox.util.data :refer (read-string)]
            [uxbox.util.mixins :as mx :include-macros true]
            [uxbox.util.dom :as dom]))

;; --- Refs

(def ^:private drawing-shape-id-ref
  "A focused vision of the drawing property
  of the workspace status. This avoids
  rerender the whole toolbox on each workspace
  change."
  (-> (l/key :drawing-tool)
      (l/derive refs/workspace)))

;; --- Constants

(def +draw-tool-rect+
  {:type :rect
   :id (uuid/random)
   :name "Rect"
   :stroke-color "#000000"})

(def +draw-tool-circle+
  {:type :circle
   :id (uuid/random)
   :name "Circle"})

(def +draw-tool-path+
  {:type :path
   :id (uuid/random)
   :name "Path"
   :stroke-style :solid
   :stroke-color "#000000"
   :stroke-width 2
   :fill-color "#000000"
   :fill-opacity 0
   ;; :close? true
   :points []})

(def +draw-tool-curve+
  (assoc +draw-tool-path+
         :id (uuid/random)
         :free true))

(def +draw-tool-text+
  {:type :text
   :id (uuid/random)
   :name "Text"
   :content "Hello world"})

(def +draw-tools+
  [{:icon i/box
    :help (tr "ds.help.rect")
    :shape +draw-tool-rect+
    :priority 1}
   {:icon i/circle
    :help (tr "ds.help.circle")
    :shape +draw-tool-circle+
    :priority 2}
   {:icon i/text
    :help (tr "ds.help.text")
    :shape +draw-tool-text+
    :priority 4}
   {:icon i/curve
    :help (tr "ds.help.path")
    :shape +draw-tool-path+
    :priority 5}
   {:icon i/pencil
    :help (tr "ds.help.path")
    :shape +draw-tool-curve+
    :priority 6}])

;; --- Draw Toolbox (Component)

(defn- select-for-draw
  [shape]
  (st/emit! ::uev/interrupt
            (dw/select-for-drawing shape)))

(mx/defc draw-toolbox
  {:mixins [mx/static mx/reactive]}
  []
  (let [drawing-tool (mx/react refs/selected-drawing-tool)
        flags (mx/react refs/flags)
        close #(st/emit! (dw/toggle-flag :drawtools))
        tools (->> (into [] +draw-tools+)
                   (sort-by (comp :priority second)))
        toggle-flag #(st/emit! (dw/toggle-flag %))]

    [:div#form-tools.tool-window.drawing-tools
     [:div.tool-window-bar
      [:div.tool-window-icon i/window]
      [:span (tr "ds.draw-tools")]
      [:div.tool-window-close {:on-click close} i/close]]
     [:div.tool-window-content
      (for [[i props] (map-indexed vector tools)
            :let [selected? (= drawing-tool (:shape props))]]
        [:div.tool-btn.tooltip.tooltip-hover
         {:alt (:help props)
          :class (when selected? "selected")
          :key (str i)
          :on-click (partial select-for-draw (:shape props))}
         (:icon props)])

      [:div.tool-btn.tooltip.tooltip-hover
       {:alt "Ruler"
        :on-click (partial toggle-flag :ruler)
        :class (when (contains? flags :ruler) "selected")}
       i/ruler-tool]]]))

