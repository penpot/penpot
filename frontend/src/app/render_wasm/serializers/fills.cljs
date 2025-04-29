(ns app.render-wasm.serializers.fills
  (:require
   [app.common.uuid :as uuid]
   [app.render-wasm.serializers.color :as clr]))

(def SOLID-BYTE-SIZE 4)

(defn write-solid-fill!
  [offset heap-u32 argb]
  (let [dview (js/DataView. (.-buffer heap-u32))]
    (.setUint32 dview offset argb true)
    (+ offset 4)))

(def IMAGE-BYTE-SIZE 28)

(defn write-image-fill!
  [offset heap-u32 id opacity width height]
  (js/console.log "write-image-fill!" (str id) opacity width height)
  (let [dview (js/DataView. (.-buffer heap-u32))
        uuid-buffer (uuid/get-u32 id)]
    (.setUint32 dview  offset (aget uuid-buffer 0) true)
    (.setUint32 dview  (+ offset 4) (aget uuid-buffer 1) true)
    (.setUint32 dview  (+ offset 8) (aget uuid-buffer 2) true)
    (.setUint32 dview  (+ offset 12) (aget uuid-buffer 3) true)
    (.setFloat32 dview (+ offset 16) opacity true)
    (.setInt32 dview   (+ offset 20) width true)
    (.setInt32 dview   (+ offset 24) height true)
    (+ offset 28)))

(def ^:private GRADIENT-STOP-SIZE 8)
(def ^:private GRADIENT-BASE-SIZE 28)
;; TODO: Define in shape model
(def ^:private MAX-GRADIENT-STOPS 16)

(def GRADIENT-BYTE-SIZE
  (+ GRADIENT-BASE-SIZE (* MAX-GRADIENT-STOPS GRADIENT-STOP-SIZE)))

(defn write-gradient-fill!
  [offset heap-u32 gradient opacity]
  (let [dview   (js/DataView. (.-buffer heap-u32))
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
    (.setUint8   dview (+ offset 24) (count stops) true)
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