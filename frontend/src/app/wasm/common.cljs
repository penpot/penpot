(ns app.wasm.common
  (:require
   [app.common.data.macros :as dm]))

(defn set-point
  [target point]
  (set! (.-x target) (dm/get-prop point :x))
  (set! (.-y target) (dm/get-prop point :y)))

(defn set-point-from
  [target x y]
  (set! (.-x target) x)
  (set! (.-y target) y))

(defn set-matrix
  [target matrix]
  (set! (.-a target) (dm/get-prop matrix :a))
  (set! (.-b target) (dm/get-prop matrix :b))
  (set! (.-c target) (dm/get-prop matrix :c))
  (set! (.-d target) (dm/get-prop matrix :d))
  (set! (.-e target) (dm/get-prop matrix :e))
  (set! (.-f target) (dm/get-prop matrix :f)))

(defn set-rect
  [target rect]
  (set-point-from (.-position target) (:x rect) (:y rect))
  (set-point-from (.-size target) (:width rect) (:height rect)))
