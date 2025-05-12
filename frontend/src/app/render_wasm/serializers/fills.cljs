(ns app.render-wasm.serializers.fills
  (:require
   [app.render-wasm.serializers.color :as clr]))

(def ^:private GRADIENT-STOP-SIZE 8)
(def ^:private GRADIENT-BASE-SIZE 28)
;; TODO: Define in shape model
(def ^:private MAX-GRADIENT-STOPS 16)

(def GRADIENT-BYTE-SIZE
  (+ GRADIENT-BASE-SIZE (* MAX-GRADIENT-STOPS GRADIENT-STOP-SIZE)))

(defn write-gradient-fill!
  [offset heap gradient opacity]
  (let [dview   (js/DataView. (.-buffer heap))
        start-x (:start-x gradient)
        start-y (:start-y gradient)
        end-x   (:end-x gradient)
        end-y   (:end-y  gradient)
        width   (or (:width gradient) 0)
        stops   (take MAX-GRADIENT-STOPS (:stops gradient))]
    (.setFloat32 dview offset        start-x true)
    (.setFloat32 dview (+ offset 4)  start-y true)
    (.setFloat32 dview (+ offset 8)  end-x true)
    (.setFloat32 dview (+ offset 12) end-y true)
    (.setFloat32 dview (+ offset 16) opacity true)
    (.setFloat32 dview (+ offset 20) width true)
    (.setUint32  dview (+ offset 24) (count stops) true)
    (loop [stops (seq stops) offset (+ offset GRADIENT-BASE-SIZE)]
      (if (empty? stops)
        offset
        (let [stop (first stops)
              hex-color (:color stop)
              opacity (:opacity stop)
              argb (clr/hex->u32argb hex-color opacity)
              stop-offset (:offset stop)]
          (.setUint32  dview offset       argb true)
          (.setFloat32 dview (+ offset 4) stop-offset true)
          (recur (rest stops) (+ offset GRADIENT-STOP-SIZE)))))))