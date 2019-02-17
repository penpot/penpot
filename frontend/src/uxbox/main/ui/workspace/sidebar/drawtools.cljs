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
            [uxbox.main.data.shapes :as uds]
            [uxbox.main.data.workspace :as udw]
            [uxbox.main.user-events :as uev]
            [uxbox.builtins.icons :as i]
            [uxbox.util.uuid :as uuid]
            [uxbox.util.i18n :refer (tr)]
            [uxbox.util.router :as r]
            [uxbox.util.data :refer (read-string)]
            [rumext.core :as mx :include-macros true]
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
    :help "ds.help.rect"
    :shape +draw-tool-rect+
    :priority 1}
   {:icon i/circle
    :help "ds.help.circle"
    :shape +draw-tool-circle+
    :priority 2}
   {:icon i/text
    :help "ds.help.text"
    :shape +draw-tool-text+
    :priority 4}
   {:icon i/curve
    :help "ds.help.path"
    :shape +draw-tool-path+
    :priority 5}
   {:icon i/pencil
    :help "ds.help.path"
    :shape +draw-tool-curve+
    :priority 6}])

;; --- Draw Toolbox (Component)

(mx/defc draw-toolbox
  {:mixins [mx/static mx/reactive]}
  [flags]
  (let [drawing-tool (mx/react refs/selected-drawing-tool)
        close #(st/emit! (udw/toggle-flag :drawtools))
        tools (->> (into [] +draw-tools+)
                   (sort-by (comp :priority second)))

        select-drawtool #(st/emit! ::uev/interrupt
                                   (udw/deactivate-ruler)
                                   (udw/select-for-drawing %))
        toggle-ruler #(st/emit! (udw/select-for-drawing nil)
                                (uds/deselect-all)
                                (udw/toggle-ruler))]

    [:div#form-tools.tool-window.drawing-tools {}
     [:div.tool-window-bar {}
      [:div.tool-window-icon {} i/window]
      [:span {} (tr "ds.draw-tools")]
      [:div.tool-window-close {:on-click close} i/close]]
     [:div.tool-window-content {}
      (mx/doseq [[i props] (map-indexed vector tools)]
        (let [selected? (= drawing-tool (:shape props))]
          [:div.tool-btn.tooltip.tooltip-hover
           {:alt (tr :help props)
            :class (when selected? "selected")
            :key (str i)
            :on-click (partial select-drawtool (:shape props))}
           (:icon props)]))
      [:div.tool-btn.tooltip.tooltip-hover
       {:alt (tr "ds.help.ruler")
        :on-click toggle-ruler
        :class (when (contains? flags :ruler) "selected")}
       i/ruler-tool]]]))

