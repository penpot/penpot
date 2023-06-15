(ns app.main.ui.css-cursors
  (:require
   [app.common.data.macros :as dm]
   [app.main.ui.cursors :as cur]
   [app.util.css :as css]))

;; angle per rotation
(def angle-step 10)

(defn get-static
  "Returns the class name of a static cursor"
  [name]
  (dm/str "cursor-" name))

(defn get-dynamic
  "Returns the class name of a dynamic cursor (with rotation)"
  [name rotation]
  (dm/str "cursor-" name "-" (mod (* (.floor js/Math (/ rotation angle-step)) angle-step) 360)))

(defn init-static-cursor-style
  "Initializes a static cursor style"
  [style name value]
  (.add style (dm/str ".cursor-" name) (js-obj "cursor" (dm/str value " !important"))))

(defn init-dynamic-cursor-style
  "Initializes a dynamic cursor style"
  [style name fn]
  (let [rotations (seq (range 0 360 angle-step))]
    (doseq [rotation rotations]
      (.add style (dm/str ".cursor-" name "-" rotation) (js-obj "cursor" (dm/str (fn rotation) " !important"))))))

(defn init-styles
  "Initializes all cursor styles"
  []
  (let [style (css/create-style "css-cursors")]
    ;; static
    (init-static-cursor-style style "comments" cur/comments)
    (init-static-cursor-style style "create-artboard" cur/create-artboard)
    (init-static-cursor-style style "create-ellipse" cur/create-ellipse)
    (init-static-cursor-style style "create-polygon" cur/create-polygon)
    (init-static-cursor-style style "create-rectangle" cur/create-rectangle)
    (init-static-cursor-style style "create-shape" cur/create-shape)
    (init-static-cursor-style style "duplicate" cur/duplicate)
    (init-static-cursor-style style "hand" cur/hand)
    (init-static-cursor-style style "move-pointer" cur/move-pointer)
    (init-static-cursor-style style "pen" cur/pen)
    (init-static-cursor-style style "pen-node" cur/pen-node)
    (init-static-cursor-style style "pencil" cur/pencil)
    (init-static-cursor-style style "picker" cur/picker)
    (init-static-cursor-style style "pointer-inner" cur/pointer-inner)
    (init-static-cursor-style style "pointer-move" cur/pointer-move)
    (init-static-cursor-style style "pointer-node" cur/pointer-node)
    (init-static-cursor-style style "resize-alt" cur/resize-alt)
    (init-static-cursor-style style "zoom" cur/zoom)
    (init-static-cursor-style style "zoom-in" cur/zoom-in)
    (init-static-cursor-style style "zoom-out" cur/zoom-out)

    ;; dynamic
    (init-dynamic-cursor-style style "resize-ew" cur/resize-ew)
    (init-dynamic-cursor-style style "resize-nesw" cur/resize-nesw)
    (init-dynamic-cursor-style style "resize-ns" cur/resize-ns)
    (init-dynamic-cursor-style style "resize-nwse" cur/resize-nwse)
    (init-dynamic-cursor-style style "rotate" cur/rotate)
    (init-dynamic-cursor-style style "text" cur/text)
    (init-dynamic-cursor-style style "scale-ew" cur/scale-ew)
    (init-dynamic-cursor-style style "scale-nesw" cur/scale-nesw)
    (init-dynamic-cursor-style style "scale-ns" cur/scale-ns)
    (init-dynamic-cursor-style style "scale-nwse" cur/scale-nwse)
    (init-dynamic-cursor-style style "resize-ew-2" cur/resize-ew-2)
    (init-dynamic-cursor-style style "resize-ns-2" cur/resize-ns-2)))