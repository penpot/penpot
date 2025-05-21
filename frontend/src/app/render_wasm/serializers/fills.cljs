(ns app.render-wasm.serializers.fills
  (:require
   [app.common.types.shape :as shp]
   [app.common.uuid :as uuid]
   [app.render-wasm.serializers.color :as clr]))

(def ^:private GRADIENT-STOP-SIZE 8)


(def GRADIENT-BYTE-SIZE 156)
(def SOLID-BYTE-SIZE 4)
(def IMAGE-BYTE-SIZE 28)

;; FIXME: get it from the wasm module
(def FILL-BYTE-SIZE (+ 4 (max GRADIENT-BYTE-SIZE IMAGE-BYTE-SIZE SOLID-BYTE-SIZE)))

(defn write-solid-fill!
  [offset dview argb]
  (.setUint8  dview offset       0x00 true)
  (.setUint32 dview (+ offset 4) argb true)
  (+ offset FILL-BYTE-SIZE))

(defn write-image-fill!
  [offset dview id opacity width height]
  (let [uuid-buffer (uuid/get-u32 id)]
    (.setUint8 dview   offset        0x03 true)
    (.setUint32 dview  (+ offset 4)  (aget uuid-buffer 0) true)
    (.setUint32 dview  (+ offset 8)  (aget uuid-buffer 1) true)
    (.setUint32 dview  (+ offset 12) (aget uuid-buffer 2) true)
    (.setUint32 dview  (+ offset 16) (aget uuid-buffer 3) true)
    (.setFloat32 dview (+ offset 20) opacity true)
    (.setInt32 dview   (+ offset 24) width true)
    (.setInt32 dview   (+ offset 28) height true)
    (+ offset FILL-BYTE-SIZE)))

(defn write-gradient-fill!
  [offset dview gradient opacity]
  (let [start-x (:start-x gradient)
        start-y (:start-y gradient)
        end-x   (:end-x gradient)
        end-y   (:end-y gradient)
        width   (or (:width gradient) 0)
        stops   (take shp/MAX-GRADIENT-STOPS (:stops gradient))
        type    (if (= (:type gradient) :linear) 0x01 0x02)]
    (.setUint8   dview offset        type true)
    (.setFloat32 dview (+ offset 4)  start-x true)
    (.setFloat32 dview (+ offset 8)  start-y true)
    (.setFloat32 dview (+ offset 12) end-x true)
    (.setFloat32 dview (+ offset 16) end-y true)
    (.setFloat32 dview (+ offset 20) opacity true)
    (.setFloat32 dview (+ offset 24) width true)
    (.setUint8   dview (+ offset 28) (count stops) true)
    (loop [stops (seq stops) loop-offset (+ offset 32)]
      (if (empty? stops)
        (+ offset FILL-BYTE-SIZE)
        (let [stop (first stops)
              hex-color (:color stop)
              stop-opacity (:opacity stop)
              argb (clr/hex->u32argb hex-color stop-opacity)
              stop-offset (:offset stop)]
          (.setUint32  dview  loop-offset       argb true)
          (.setFloat32 dview  (+ loop-offset 4) stop-offset true)
          (recur (rest stops) (+ loop-offset GRADIENT-STOP-SIZE)))))))