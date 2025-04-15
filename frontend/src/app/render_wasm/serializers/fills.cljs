(ns app.render-wasm.serializers.fills
  (:require
   [app.common.data.macros :as dm]
   [app.render-wasm.serializers.color :as clr]))

(def GRADIENT-STOP-SIZE 8)
(def GRADIENT-BASE-SIZE 24)

(defn serialize-gradient-fill
  [gradient opacity heap offset]
  (let [dview (js/DataView. (.-buffer heap))
        start-x (dm/get-prop gradient :start-x)
        start-y (dm/get-prop gradient :start-y)
        end-x (dm/get-prop gradient :end-x)
        end-y (dm/get-prop gradient :end-y)
        width (or (dm/get-prop gradient :width) 0)
        stops (dm/get-prop gradient :stops)]
    (.setFloat32 dview offset        start-x true)
    (.setFloat32 dview (+ offset 4)  start-y true)
    (.setFloat32 dview (+ offset 8)  end-x true)
    (.setFloat32 dview (+ offset 12) end-y true)
    (.setFloat32 dview (+ offset 16) opacity true)
    (.setFloat32 dview (+ offset 20) width true)
    (loop [stops (seq stops) offset (+ offset GRADIENT-BASE-SIZE)]
      (when-not (empty? stops)
        (let [stop (first stops)
              hex-color (dm/get-prop stop :color)
              opacity (dm/get-prop stop :opacity)
              argb (clr/hex->u32argb hex-color opacity)
              stop-offset (dm/get-prop stop :offset)]
          (.setUint32  dview offset       argb true)
          (.setFloat32 dview (+ offset 4) stop-offset true)
          (recur (rest stops) (+ offset GRADIENT-STOP-SIZE)))))))