;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS SUBSIDIARY SL

(ns app.common.test-helpers.render-wasm
  (:require
   [app.common.geom.matrix :as gmt]
   [app.common.uuid :as uuid]))

(def serializers
  #js {"transform-entry-kind" #js {"parent" 0 "child" 1}})

(defn- get-uuid
  [view offset]
  (uuid/from-unsigned-parts
   (.getUint32 view offset true)
   (.getUint32 view (+ offset 4) true)
   (.getUint32 view (+ offset 8) true)
   (.getUint32 view (+ offset 12) true)))

(defn- get-matrix
  [view offset]
  (apply gmt/matrix
         (mapv #(.getFloat32 view (+ offset (* 4 %)) true) (range 6))))

(defn- write-transform
  [view offset [id matrix]]
  (doseq [[index value] (map-indexed vector (uuid/get-u32 id))]
    (.setUint32 view (+ offset (* 4 index)) value true))
  (doseq [[index key] (map-indexed vector [:a :b :c :d :e :f])]
    (.setFloat32 view (+ offset 16 (* 4 index)) (get matrix key) true)))

(defn create-module
  "Creates a fake module implementing modifier payload exchanges.

  Args:
    options: Map with :output transforms, :grow-on-alloc, :grow-on-propagate,
      :propagate-error or :set-error code, and :invalid-output-pointer.

  Returns:
    A map with :module and an atom :state containing decoded native inputs,
    the active payload slot, previews and interactive mode."
  [{:keys [output grow-on-alloc grow-on-propagate propagate-error set-error invalid-output-pointer]
    :or {output []}}]
  (let [module #js {}
        state  (atom {:slot nil :propagated nil :preview [] :structure []
                      :interactive false :error-code 0})
        resize (fn [size]
                 (let [buffer (js/ArrayBuffer. size)]
                   (set! (.-HEAPU8 module) (js/Uint8Array. buffer))
                   (set! (.-HEAPU32 module) (js/Uint32Array. buffer))
                   (set! (.-HEAPF32 module) (js/Float32Array. buffer))))
        alloc  (fn [size]
                 (when (or (:slot @state) (not (pos? size)))
                   (throw (js/Error. "Payload slot is unavailable")))
                 (when grow-on-alloc
                   (resize (* 2 (.-byteLength (.-HEAPU8 module)))))
                 (swap! state assoc :slot {:offset 64 :size size})
                 64)
        take-input
        (fn [entry-size decode]
          (let [{:keys [offset size]} (:slot @state)]
            (when (or (nil? offset) (not (zero? (mod size entry-size))))
              (throw (js/Error. "Invalid modifier payload")))
            (swap! state assoc :slot nil :error-code 0)
            (let [view (js/DataView. (.-buffer (.-HEAPU8 module)))]
              (mapv #(decode view (+ offset %)) (range 0 size entry-size)))))
        get-transform
        (fn [view offset]
          [(get-uuid view offset) (get-matrix view (+ offset 16))])]
    (resize 4096)
    (set! (.-_alloc_bytes module) alloc)
    (set! (.-_free_bytes module)
          (fn []
            (swap! state assoc :slot nil :error-code 0)
            js/undefined))
    (set! (.-_read_error_code module) (fn [] (:error-code @state)))
    (set! (.-_propagate_modifiers module)
          (fn [precision]
            (let [entries (take-input
                           44
                           (fn [view offset]
                             [(get-uuid view offset)
                              {:transform (get-matrix view (+ offset 16))
                               :kind (case (.getUint32 view (+ offset 40) true)
                                       0 :parent
                                       1 :child)}]))]
              (swap! state assoc :propagated {:precision precision :entries entries})
              (when propagate-error
                (swap! state assoc :error-code propagate-error)
                (throw (js/Error. "Native propagation failed")))
              (when grow-on-propagate
                (resize (* 2 (.-byteLength (.-HEAPU8 module)))))
              (let [pointer (alloc (+ 4 (* 40 (count output))))
                    view    (js/DataView. (.-buffer (.-HEAPU8 module)))]
                (.setUint32 view pointer (count output) true)
                (doseq [[index entry] (map-indexed vector output)]
                  (write-transform view (+ pointer 4 (* 40 index)) entry))
                (if invalid-output-pointer
                  #js {"valueOf" (fn [] (throw (js/Error. "Invalid output pointer")))}
                  pointer)))))
    (set! (.-_set_modifiers module)
          (fn []
            (let [transforms (take-input 40 get-transform)]
              (when set-error
                (swap! state assoc :error-code set-error)
                (throw (js/Error. "Native preview failed")))
              (swap! state assoc :preview transforms))))
    (set! (.-_set_structure_modifiers module)
          (fn []
            (swap! state assoc :structure
                   (take-input
                    44
                    (fn [view offset]
                      {:type (case (.getUint32 view offset true)
                               1 :remove-children
                               2 :add-children
                               3 :scale-content)
                       :index (.getUint32 view (+ offset 4) true)
                       :parent (get-uuid view (+ offset 8))
                       :id (get-uuid view (+ offset 24))
                       :value (.getFloat32 view (+ offset 40) true)})))))
    (set! (.-_set_modifiers_start module)
          (fn [] (swap! state assoc :interactive true)))
    (set! (.-_set_modifiers_end module)
          (fn [] (swap! state assoc :interactive false)))
    (set! (.-_clean_modifiers module)
          (fn [] (swap! state assoc :preview [] :structure [])))
    {:module module :state state}))
