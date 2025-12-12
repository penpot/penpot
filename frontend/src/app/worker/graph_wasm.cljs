;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC

(ns app.worker.graph-wasm
  "Graph WASM operations within the worker."
  (:require
   [app.common.data.macros :as dm]
   [app.common.logging :as log]
   [app.common.uuid :as uuid]
   [app.config :as cf]
   [app.graph-wasm.wasm :as wasm]
   [app.render-wasm.helpers :as h]
   [app.render-wasm.serializers :as sr]
   [app.worker.impl :as impl]
   [beicon.v2.core :as rx]
   [promesa.core :as p]))

(log/set-level! :info)

(defn- use-shape
  [module id]
  (let [buffer (uuid/get-u32 id)]
    (h/call module "_use_shape"
            (aget buffer 0)
            (aget buffer 1)
            (aget buffer 2)
            (aget buffer 3))))

(defn- set-shape-parent-id
  [module id]
  (let [buffer (uuid/get-u32 id)]
    (h/call module "_set_shape_parent"
            (aget buffer 0)
            (aget buffer 1)
            (aget buffer 2)
            (aget buffer 3))))

(defn- set-shape-type
  [module type]
  (h/call module "_set_shape_type" (sr/translate-shape-type type)))

(defn- set-shape-selrect
  [module selrect]
  (h/call module "_set_shape_selrect"
          (dm/get-prop selrect :x1)
          (dm/get-prop selrect :y1)
          (dm/get-prop selrect :x2)
          (dm/get-prop selrect :y2)))

(defn- set-object
  [module shape]
  (let [id           (dm/get-prop shape :id)
        type         (dm/get-prop shape :type)
        parent-id    (get shape :parent-id)
        selrect      (get shape :selrect)]
    (use-shape module id)
    (set-shape-type module type)
    (set-shape-parent-id module parent-id)
    (set-shape-selrect module selrect)))

(defonce ^:private graph-wasm-module
  (delay
    (let [module  (unchecked-get js/globalThis "GraphWasmModule")
          init-fn (unchecked-get module "default")
          href    (cf/resolve-href "js/graph-wasm.wasm")]
      (->> (init-fn #js {:locateFile (constantly href)})
           (p/fnly (fn [module cause]
                     (if cause
                       (js/console.error cause)
                       (set! wasm/internal-module module))))))))

(defmethod impl/handler :graph-wasm/init
  [message transfer]
  (rx/create
   (fn [subs]
     (-> @graph-wasm-module
         (p/then (fn [module]
                   (if module
                     (try
                       (h/call module "_init")
                       (rx/push! subs {:status :ok})
                       (rx/end! subs)
                       (catch :default cause
                         (log/error :hint "Error in graph-wasm/init" :cause cause)
                         (rx/error! subs cause)
                         (rx/end! subs)))
                     (do
                       (log/warn :hint "Graph WASM module not available")
                       (rx/push! subs {:status :error :message "Module not available"})
                       (rx/end! subs)))))
         (p/catch (fn [cause]
                    (log/error :hint "Error loading graph-wasm module" :cause cause)
                    (rx/error! subs cause)
                    (rx/end! subs))))
     nil)))

(defmethod impl/handler :graph-wasm/set-objects
  [message transfer]
  (let [objects (:objects message)]
    (rx/create
     (fn [subs]
       (-> @graph-wasm-module
           (p/then (fn [module]
                     (if module
                       (try
                         (doseq [shape (vals objects)]
                           (set-object module shape))
                         (h/call module "_generate_db")
                         (rx/push! subs {:status :ok :processed (count objects)})
                         (rx/end! subs)
                         (catch :default cause
                           (log/error :hint "Error in graph-wasm/set-objects" :cause cause)
                           (rx/error! subs cause)
                           (rx/end! subs)))
                       (do
                         (log/warn :hint "Graph WASM module not available")
                         (rx/push! subs {:status :error :message "Module not available"})
                         (rx/end! subs)))))
           (p/catch (fn [cause]
                      (log/error :hint "Error loading graph-wasm module" :cause cause)
                      (rx/error! subs cause)
                      (rx/end! subs))))
       nil))))

(defmethod impl/handler :graph-wasm/search-similar-shapes
  [message transfer]
  (let [shape-id (:shape-id message)]
    (rx/create
     (fn [subs]
       (-> @graph-wasm-module
           (p/then (fn [module]
                     (if module
                       (try
                         (let [buffer (uuid/get-u32 shape-id)
                               ptr-raw (h/call module "_search_similar_shapes"
                                               (aget buffer 0)
                                               (aget buffer 1)
                                               (aget buffer 2)
                                               (aget buffer 3))
                               ;; Convert pointer to unsigned 32-bit (handle negative numbers from WASM)
                               ;; Use unsigned right shift to convert signed to unsigned 32-bit
                               ptr (unsigned-bit-shift-right ptr-raw 0)
                               heapu8 (unchecked-get module "HEAPU8")

                               ;; Read count (first 4 bytes, little-endian u32)
                               count (bit-or (aget heapu8 ptr)
                                             (bit-shift-left (aget heapu8 (+ ptr 1)) 8)
                                             (bit-shift-left (aget heapu8 (+ ptr 2)) 16)
                                             (bit-shift-left (aget heapu8 (+ ptr 3)) 24))
                               ;; Read UUIDs (16 bytes each, starting at offset 4)
                               similar-shapes (loop [offset (+ ptr 4)
                                                     remaining count
                                                     result []]
                                                (if (zero? remaining)
                                                  result
                                                  (let [uuid-bytes (.slice heapu8 offset (+ offset 16))]
                                                    (recur (+ offset 16)
                                                           (dec remaining)
                                                           (conj result (uuid/from-bytes uuid-bytes))))))]

                           ;; Free the buffer
                           (h/call module "_free_similar_shapes_buffer")

                           (rx/push! subs {:status :ok :similar-shapes similar-shapes})
                           (rx/end! subs))
                         (catch :default cause
                           (log/error :hint "Error in graph-wasm/search-similar-shapes" :cause cause)
                           (rx/error! subs cause)
                           (rx/end! subs)))
                       (do
                         (log/warn :hint "Graph WASM module not available")
                         (rx/push! subs {:status :error :message "Module not available"})
                         (rx/end! subs)))))
           (p/catch (fn [cause]
                      (log/error :hint "Error loading graph-wasm module" :cause cause)
                      (rx/error! subs cause)
                      (rx/end! subs))))
       nil))))
