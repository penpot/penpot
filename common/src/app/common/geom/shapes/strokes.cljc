(ns app.common.geom.shapes.strokes)

(defn update-stroke-width
  [stroke scale]
  (update stroke :stroke-width * scale))

(defn update-strokes-width
  [shape scale]
  (update shape :strokes 
    (fn [strokes] 
      (mapv #(update-stroke-width % scale) strokes))))
