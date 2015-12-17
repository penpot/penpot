(ns uxbox.ui.workspace.workarea
  (:require [sablono.core :as html :refer-macros [html]]
            [rum.core :as rum]
            [uxbox.util :as util]
            [uxbox.router :as r]
            [uxbox.rstore :as rs]
            [uxbox.state :as s]
            [uxbox.data.projects :as dp]
            [uxbox.ui.workspace.base :as wb]
            [uxbox.ui.workspace.rules :as wr]))


;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Coordinates Debug
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(defonce canvas-coordinates (atom {}))

(defn coordenates-render
  []
  (let [[x y] (rum/react canvas-coordinates)]
    (html
     [:div
      {:style {:position "absolute" :left "80px" :top "20px"}}
      [:table
       [:tr
        [:td "X:"]
        [:td (or x 1)]]
       [:tr
        [:td "Y:"]
        [:td y]]]])))

(def coordinates
  (util/component
   {:render coordenates-render
    :name "coordenates"
    :mixins [rum/reactive]}))

(defn background-render
  []
  (html
   [:rect
    {:x 0 :y 0 :width "100%" :height "100%" :fill "white"}]))

(def background
  (util/component
   {:render background-render
    :name "background"
    :mixins [rum/static]}))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Work Area
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(defn working-area-render
  [own]
  (html
   [:section.workspace-canvas
    #_{:class (when (empty? open-setting-boxes)
              "no-tool-bar")
     :on-scroll (constantly nil)}
    #_(when (:selected page)
        (element-options conn
                         page-cursor
                         project-cursor
                         zoom-cursor
                         shapes-cursor))
    (coordinates)
    #_(viewport conn page shapes zoom grid?)]))

(def working-area
  (util/component
   {:render working-area-render
    :name "working-area"
    :mixins []}))

