(ns app.common.geom.shapes.effects)

(defn update-shadow-scale
  [shadow scale]
  (-> shadow
      (update :offset-x * scale)
      (update :offset-y * scale)
      (update :spread * scale)
      (update :blur * scale)))

(defn update-shadows-scale
  [shape scale]
  (update shape :shadow
          (fn [shadow]
            (mapv #(update-shadow-scale % scale) shadow))))

(defn update-blur-scale
  [shape scale]
  (update-in shape [:blur :value] * scale))

