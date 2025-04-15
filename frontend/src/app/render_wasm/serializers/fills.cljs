(ns app.render-wasm.serializers.fills
  (:require
   [app.render-wasm.serializers.color :as clr]))

(def GRADIENT-STOP-SIZE 8)
(def GRADIENT-BASE-SIZE 24)

(defn gradient-byte-size
  [gradient]
  (let [stops (:stops gradient)]
    (+ GRADIENT-BASE-SIZE (* (count stops) GRADIENT-STOP-SIZE))))

(defn serialize-gradient-fill
  [gradient opacity heap offset]
  (let [dview   (js/DataView. (.-buffer heap))
        start-x (:start-x gradient)
        start-y (:start-y gradient)
        end-x   (:end-x gradient)
        end-y   (:end-y  gradient)
        width   (or (:width gradient) 0)
        stops   (:stops gradient)]
    (.setFloat32 dview offset        start-x true)
    (.setFloat32 dview (+ offset 4)  start-y true)
    (.setFloat32 dview (+ offset 8)  end-x true)
    (.setFloat32 dview (+ offset 12) end-y true)
    (.setFloat32 dview (+ offset 16) opacity true)
    (.setFloat32 dview (+ offset 20) width true)
    (loop [stops (seq stops) offset (+ offset GRADIENT-BASE-SIZE)]
      (when-not (empty? stops)
        (let [stop (first stops)
              hex-color (:color stop)
              opacity (:opacity stop)
              argb (clr/hex->u32argb hex-color opacity)
              stop-offset (:offset stop)]
          (.setUint32  dview offset       argb true)
          (.setFloat32 dview (+ offset 4) stop-offset true)
          (recur (rest stops) (+ offset GRADIENT-STOP-SIZE)))))))