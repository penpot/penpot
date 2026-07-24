(ns app.main.ui.flex-controls.common
  (:require
   [app.main.constants :as mconst]
   [app.main.streams :as ms]
   [app.main.ui.formats :as fmt]
   [beicon.v2.core :as rx]
   [rumext.v2 :as mf]))

;; ------------------------------------------------
;; INTERACTIVE TRANSFORM CLEANUP
;; ------------------------------------------------

(defn clear-transform-preview!
  "Reset the interactive-transform preview behaviour-subjects. Flex
  spacing controls (padding/margin/gap) set these via
  `set-wasm-modifiers` while dragging and only clear them through
  `finish-transform` on `lost-pointer-capture`. If the control unmounts
  mid-drag (e.g. the selection changes) that handler never fires and the
  stale selrect keeps displacing the DOM selection overlay of the next
  selection. Call from an unmount cleanup guarded on the resizing flag."
  []
  (rx/push! ms/wasm-modifiers nil)
  (rx/push! ms/workspace-selrect nil))

;; ------------------------------------------------
;; CONSTANTS
;; ------------------------------------------------

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
           :style {:fill mconst/distance-text-color
                   :font-size font-size}}
    (fmt/format-number (or value 0))]])
