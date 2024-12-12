;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC

(ns app.common.svg.path
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

#?(:cljs
   (defn content->buffer
     "Converts the path content into binary format."
     [content]
     (let [total  (count content)
           ssize  28
           buffer (new js/ArrayBuffer (* total ssize))
           dview  (new js/DataView buffer)]
       (loop [index 0]
         (when (< index total)
           (let [segment (nth content index)
                 offset  (* index ssize)]
             (case (:command segment)
               :move-to
               (let [{:keys [x y]} (:params segment)]
                 (.setInt16 dview   (+ offset 0) 1)
                 (.setFloat32 dview (+ offset 20) x)
                 (.setFloat32 dview (+ offset 24) y))
               :line-to
               (let [{:keys [x y]} (:params segment)]
                 (.setInt16 dview   (+ offset 0) 2)
                 (.setFloat32 dview (+ offset 20) x)
                 (.setFloat32 dview (+ offset 24) y))
               :curve-to
               (let [{:keys [c1x c1y c2x c2y x y]} (:params segment)]
                 (.setInt16 dview   (+ offset 0) 3)
                 (.setFloat32 dview (+ offset 4) c1x)
                 (.setFloat32 dview (+ offset 8) c1y)
                 (.setFloat32 dview (+ offset 12) c2x)
                 (.setFloat32 dview (+ offset 16) c2y)
                 (.setFloat32 dview (+ offset 20) x)
                 (.setFloat32 dview (+ offset 24) y))

               :close-path
               (.setInt16 dview (+ offset 0) 4))
             (recur (inc index)))))
       buffer)))

#?(:cljs
   (defn buffer->content
     "Converts the a buffer to a path content vector"
     [buffer]
     (assert (instance? js/ArrayBuffer buffer) "expected ArrayBuffer instance")
     (let [ssize  28
           total  (/ (.-byteLength buffer) ssize)
           dview  (new js/DataView buffer)]
       (loop [index  0
              result []]
         (if (< index total)
           (let [offset  (* index ssize)
                 type    (.getInt16 dview (+ offset 0))
                 command (case type
                           1 :move-to
                           2 :line-to
                           3 :curve-to
                           4 :close-path)
                 params  (case type
                           1 {:x (.getFloat32 dview (+ offset 20))
                              :y (.getFloat32 dview (+ offset 24))}
                           2 {:x (.getFloat32 dview (+ offset 20))
                              :y (.getFloat32 dview (+ offset 24))}
                           3 {:c1x (.getFloat32 dview (+ offset 4))
                              :c1y (.getFloat32 dview (+ offset 8))
                              :c2x (.getFloat32 dview (+ offset 12))
                              :c2y (.getFloat32 dview (+ offset 16))
                              :x   (.getFloat32 dview (+ offset 20))
                              :y   (.getFloat32 dview (+ offset 24))}
                           4 {})]
             (recur (inc index)
                    (conj result {:command command
                                  :params params})))
           result)))))
