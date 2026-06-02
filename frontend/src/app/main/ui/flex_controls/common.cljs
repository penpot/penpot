(ns app.main.ui.flex-controls.common
  (:require
   [app.main.ui.formats :as fmt]
   [rumext.v2 :as mf]))

;; ------------------------------------------------
;; CONSTANTS
;; ------------------------------------------------

(def font-size 11)
(def distance-color "var(--color-accent-quaternary)")
(def distance-text-color "var(--app-white)")
(def warning-color "var(--status-color-warning-500)")
(def flex-display-pill-width 40)
(def flex-display-pill-height 20)
(def flex-display-pill-border-radius 4)

(mf/defc flex-display-pill
  [{:keys [x y width height font-size border-radius value color]}]
  [:g.distance-pill
   [:rect {:x x
           :y y
           :width width
           :height height
           :rx border-radius
           :ry border-radius
           :style {:fill color}}]

   [:text {:x (+ x (/ width 2))
           :y (+ y (/ height 2))
           :text-anchor "middle"
           :dominant-baseline "central"
           :style {:fill distance-text-color
                   :font-size font-size}}
    (fmt/format-number (or value 0))]])
