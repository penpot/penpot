(ns uxbox.ui.workspace.canvas.ruler
  (:require-macros [uxbox.util.syntax :refer [define-once]])
  (:require [sablono.core :as html :refer-macros [html]]
            [rum.core :as rum]
            [beicon.core :as rx]
            [cats.labs.lens :as l]
            [uxbox.rstore :as rs]
            [uxbox.state :as st]
            [uxbox.shapes :as sh]
            [uxbox.data.workspace :as dw]
            [uxbox.util.math :as mth]
            [uxbox.ui.workspace.base :as wb]
            [uxbox.ui.mixins :as mx]
            [uxbox.util.geom.point :as gpt]
            [uxbox.util.dom :as dom]))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Component
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(defn- resolve-position
  [own pt]
  (let [overlay (mx/get-ref-dom own "overlay")
        brect (.getBoundingClientRect overlay)
        bpt (gpt/point (.-left brect) (.-top brect))]
    (gpt/subtract pt bpt)))

(defn- get-position
  [own event]
  (->> (gpt/point (.-clientX event)
                  (.-clientY event))
       (resolve-position own)))

(defn- on-mouse-down
  [own local event]
  (dom/stop-propagation event)
  (let [pos (get-position own event)]
    (reset! local {:active true :pos1 pos :pos2 pos})))

(defn- on-mouse-up
  [own local event]
  (dom/stop-propagation event)
  (swap! local assoc :active false))

(defn- overlay-line-render
  [own center pt]
  (let [distance (-> (gpt/distance pt center)
                     (mth/precision 4))
        angle (-> (gpt/angle pt center)
                  (mth/precision 4))
        {x1 :x y1 :y} center
        {x2 :x y2 :y} pt]
    (html
     [:g
      [:line {:x1 x1 :y1 y1
              :x2 x2 :y2 y2
              :style {:cursor "cell"}
              :stroke-width "1"
              :stroke "red"}]
      [:text
       {:transform (str "translate(" (+ x2 15) "," (- y2 10) ")")}
       [:tspan {:x "0" :dy="1.2em"}
        (str distance " px")]
       [:tspan {:x "0" :y "20" :dy="1.2em"}
        (str angle "°")]]])))

(defn- overlay-render
  [own local]
  (let [p1 (:pos1 @local)
        p2 (:pos2 @local)]
    (html
     [:svg {:on-mouse-down #(on-mouse-down own local %)
            :on-mouse-up #(on-mouse-up own local %)
            :ref "overlay"}
      [:rect {:style {:fill "transparent"
                      :stroke "transparent"
                      :cursor "cell"}
              :width wb/viewport-width
              :height wb/viewport-height}]
      (if (and p1 p2)
        (overlay-line-render own p1 p2))])))

(def ^:private ^:static +immanted-zones+
  (let [transform #(vector (- % 7) (+ % 7) %)]
    (concat
     (mapv transform (range 0 181 15))
     (mapv (comp transform -) (range 0 181 15)))))

(defn- overlay-will-mount
  [own local]
  (letfn [(align-position [angle pos]
            (reduce (fn [pos [a1 a2 v]]
                      (if (< a1 angle a2)
                        (reduced (gpt/update-angle pos v))
                        pos))
                    pos
                    +immanted-zones+))

          (on-value-aligned [pos2]
            (let [center (:pos1 @local)]
              (as-> pos2 $
                (gpt/subtract $ center)
                (align-position (gpt/angle $) $)
                (gpt/add $ center)
                (swap! local assoc :pos2 $))))

          (on-value-simple [pos2]
            (swap! local assoc :pos2 pos2))

          (on-value [[pos ctrl?]]
            (if ctrl?
              (on-value-aligned pos)
              (on-value-simple pos)))]

    (as-> wb/mouse-absolute-s $
      (rx/filter #(:active @local) $)
      (rx/map #(resolve-position own %) $)
      (rx/with-latest-from vector wb/mouse-ctrl-s $)
      (rx/on-value $ on-value)
      (assoc own ::sub $))))

(defn- overlay-will-unmount
  [own]
  (let [subscription (::sub own)]
    (subscription)
    (dissoc own ::sub)))

(defn- overlay-transfer-state
  [old-own own]
  (let [sub (::sub old-own)]
    (assoc own ::sub sub)))

(def ^:static overlay
  (mx/component
   {:render #(overlay-render % (:rum/local %))
    :will-mount #(overlay-will-mount % (:rum/local %))
    :will-unmount overlay-will-unmount
    :transfer-state overlay-transfer-state
    :name "overlay"
    :mixins [mx/static (mx/local)]}))

(defn- ruler-render
  [own]
  (let [flags (rum/react wb/flags-l)]
    (when (contains? flags :workspace/ruler)
      (overlay))))

(def ^:static ruler
  (mx/component
   {:render ruler-render
    :name "ruler"
    :mixins [mx/static rum/reactive (mx/local)]}))
