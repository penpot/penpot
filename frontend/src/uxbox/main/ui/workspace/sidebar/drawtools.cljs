;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) 2015-2016 Juan de la Cruz <delacruzgarciajuan@gmail.com>
;; Copyright (c) 2015-2019 Andrey Antukh <niwi@niwi.nz>

(ns uxbox.main.ui.workspace.sidebar.drawtools
  (:require
   [rumext.alpha :as mf]
   [uxbox.builtins.icons :as i]
   [uxbox.main.data.shapes :as uds]
   [uxbox.main.data.workspace :as udw]
   [uxbox.main.data.workspace-drawing :as udwd]
   [uxbox.main.store :as st]
   [uxbox.main.user-events :as uev]
   [uxbox.util.i18n :refer (tr)]
   [uxbox.util.uuid :as uuid]))

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
    :help "ds.help.curve"
    :shape +draw-tool-curve+
    :priority 6}])

;; --- Draw Toolbox (Component)

(mf/defc draw-toolbox
  {:wrap [mf/wrap-memo]}
  [{:keys [flags drawing-tool] :as props}]
  (let [close #(st/emit! (udw/toggle-flag :drawtools))
        tools (->> (into [] +draw-tools+)
                   (sort-by (comp :priority second)))

        select-drawtool #(st/emit! ::uev/interrupt
                                   (udw/deactivate-ruler)
                                   (udwd/select-for-drawing %))
        toggle-ruler #(st/emit! (udwd/select-for-drawing nil)
                                (uds/deselect-all)
                                (udw/toggle-ruler))]

    [:div#form-tools.tool-window.drawing-tools
     [:div.tool-window-bar
      [:div.tool-window-icon i/window]
      [:span (tr "ds.settings.draw-tools")]
      [:div.tool-window-close {:on-click close} i/close]]
     [:div.tool-window-content
      (for [[i props] (map-indexed vector tools)]
        (let [selected? (= drawing-tool (:shape props))]
          [:div.tool-btn.tooltip.tooltip-hover
           {:alt (tr (:help props))
            :class (when selected? "selected")
            :key i
            :on-click (partial select-drawtool (:shape props))}
           (:icon props)]))
      [:div.tool-btn.tooltip.tooltip-hover
       {:alt (tr "ds.help.ruler")
        :on-click toggle-ruler
        :class (when (contains? flags :ruler) "selected")}
       i/ruler-tool]]]))

