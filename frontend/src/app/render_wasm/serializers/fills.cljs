(ns app.render-wasm.serializers.fills
  (:require
   [app.common.data.macros :as dm]
   [app.render-wasm.serializers.color :as clr]))

(def GRADIENT-STOP-SIZE 5)
(def GRADIENT-BASE-SIZE 24)

(defn serialize-linear-fill
  [gradient opacity heap-u8 offset]
  (let [dview (js/DataView. (.-buffer heap-u8))
        start-x (dm/get-prop gradient :start-x)
        start-y (dm/get-prop gradient :start-y)
        end-x (dm/get-prop gradient :end-x)
        end-y (dm/get-prop gradient :end-y)
        stops (dm/get-prop gradient :stops)
        width 0]
    (.setFloat32 dview offset        start-x true)
    (.setFloat32 dview (+ offset 4)  start-y true)
    (.setFloat32 dview (+ offset 8)  end-x true)
    (.setFloat32 dview (+ offset 12) end-y true)
    (.setFloat32 dview (+ offset 16) opacity true)
    (.setFloat32 dview (+ offset 20) width true)
    (loop [stops (seq stops) idx 0]
      (when-not (empty? stops)
        (let [stop (first stops)
              hex-color (dm/get-prop stop :color)
              opacity (dm/get-prop stop :opacity)
              rgba (clr/hex->u32argb hex-color opacity)
              stop-offset (* 100 (dm/get-prop stop :offset))
              dview-offset (+ (* idx 5) offset 24)]
          (.setUint32 dview dview-offset       rgba true)
          (.setUint8  dview (+ dview-offset 4) stop-offset)
          (recur (rest stops) (+ idx 1)))))))