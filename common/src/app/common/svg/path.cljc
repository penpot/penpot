;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC

(ns app.common.svg.path
  #?(:cljs
     (:require-macros [app.common.svg.path :refer [assign-to-buffer get-from-buffer]]))
  #?(:clj
     (:import app.common.svg.path.Parser
              app.common.svg.path.Parser$Segment))
  #?(:cljs
     (:require ["./path/parser.js" :as parser])))

(defn arc->beziers
  "A function for convert Arcs to Beziers, used only for testing
  purposes."
  [x1 y1 x2 y2 fa fs rx ry phi]
  #?(:clj (Parser/arcToBeziers (double x1)
                               (double y1)
                               (double x2)
                               (double y2)
                               (double fa)
                               (double fs)
                               (double rx)
                               (double ry)
                               (double phi))
     :cljs (parser/arcToBeziers x1 y1 x2 y2 fa fs rx ry phi)))

(defn parse
  [path-str]
  (if (empty? path-str)
    path-str
    #?(:clj
       (into []
             (map (fn [segment]
                    (.toPersistentMap ^Parser$Segment segment)))
             (Parser/parse path-str))
       :cljs
       (into []
             (map (fn [segment]
                    (.toPersistentMap ^js segment)))
             (parser/parse path-str)))))

(defmacro assign-to-buffer
  [target type offset value]
  (let [target (if (:ns &env)
                 (with-meta target {:tag 'js/DataView})
                 (with-meta target {:tag 'java.nio.ByteBuffer}))]
    (case type
      :float
      (if (:ns &env)
        `(.setFloat32 ~target ~offset ~value)
        `(.putFloat ~target (int ~offset) (float ~value)))
      :short
      (if (:ns &env)
        `(.setInt16 ~target ~offset ~value)
        `(.putShort ~target (int ~offset) (short ~value)))
      (throw (ex-info "invalid type provided" {:type type})))))

(defmacro get-from-buffer
  [target type & params]
  (let [target (if (:ns &env)
                 (with-meta target {:tag 'js/DataView})
                 (with-meta target {:tag 'java.nio.ByteBuffer}))]
    (case type
      :float
      (if (:ns &env)
        `(.getFloat32 ~target ~@params)
        `(.getFloat ~target ~@params))
      :short
      (if (:ns &env)
        `(.getInt16 ~target ~@params)
        `(.getShort ~target ~@params))

      (throw (ex-info "invalid type provided" {:type type})))))

(defn content->buffer
  "Converts the path content into binary format."
  [content]
  (let [total  (count content)
        ssize  28
        #?@(:cljs [buffer (new js/ArrayBuffer (* total ssize))
                   dview  (new js/DataView buffer)]
            :clj  [dview (java.nio.ByteBuffer/allocate (* total ssize))])]

    (loop [index 0]
      (when (< index total)
        (let [segment (nth content index)
              offset  (* index ssize)]
          (case (get segment :command)
            :move-to
            (let [params (get segment :params)
                  x      (get params :x)
                  y      (get params :y)]
              (assign-to-buffer dview :short (+ offset 0) (short 1))
              (assign-to-buffer dview :float (+ offset 20) x)
              (assign-to-buffer dview :float (+ offset 24) y))

            :line-to
            (let [params (get segment :params)
                  x      (get params :x)
                  y      (get params :y)]
              (assign-to-buffer dview :short (+ offset 0) 2)
              (assign-to-buffer dview :float (+ offset 20) x)
              (assign-to-buffer dview :float (+ offset 24) y))
            :curve-to
            (let [params (get segment :params)
                  x      (get params :x)
                  y      (get params :y)
                  c1x    (get params :c1x)
                  c1y    (get params :c1y)
                  c2x    (get params :c2x)
                  c2y    (get params :c2y)]
              (assign-to-buffer dview :short (+ offset 0) 3)
              (assign-to-buffer dview :float (+ offset 4) c1x)
              (assign-to-buffer dview :float (+ offset 8) c1y)
              (assign-to-buffer dview :float (+ offset 12) c2x)
              (assign-to-buffer dview :float (+ offset 16) c2y)
              (assign-to-buffer dview :float (+ offset 20) x)
              (assign-to-buffer dview :float (+ offset 24) y))

            :close-path
            (assign-to-buffer dview :short (+ offset 0) 4)))
        (recur (inc index))))
    #?(:cljs buffer :clj (.array dview))))

(defn buffer->content
  "Converts the a buffer to a path content vector"
  [buffer]
  (let [ssize  28
        total  (/ #?(:cljs (.-byteLength buffer)
                     :clj  (alength buffer))
                  ssize)
        dview  #?(:cljs (new js/DataView buffer)
                  :clj  (java.nio.ByteBuffer/wrap ^bytes buffer))]
    (loop [index  0
           result []]
      (if (< index total)
        (let [offset  (* index ssize)
              type    (get-from-buffer dview :short (+ offset 0))
              command (case type
                        1 :move-to
                        2 :line-to
                        3 :curve-to
                        4 :close-path)
              params  (case type
                        1 {:x (get-from-buffer dview :float (+ offset 20))
                           :y (get-from-buffer dview :float (+ offset 24))}
                        2 {:x (get-from-buffer dview :float (+ offset 20))
                           :y (get-from-buffer dview :float (+ offset 24))}
                        3 {:c1x (get-from-buffer dview :float (+ offset 4))
                           :c1y (get-from-buffer dview :float (+ offset 8))
                           :c2x (get-from-buffer dview :float (+ offset 12))
                           :c2y (get-from-buffer dview :float (+ offset 16))
                           :x   (get-from-buffer dview :float (+ offset 20))
                           :y   (get-from-buffer dview :float (+ offset 24))}
                        4 {})]
          (recur (inc index)
                 (conj result {:command command
                               :params params})))
        result))))
