(ns app.render-wasm.path)

(def command-size 28)

#_(defn content->buffer
    "Converts the path content into binary format."
    [content]
    (let [total  (count content)
          buffer (new js/ArrayBuffer (* total command-size))
          dview  (new js/DataView buffer)]
      (loop [index 0]
        (when (< index total)
          (let [segment (nth content index)
                offset  (* index command-size)]
            (case (:command segment)
              :move-to
              (let [{:keys [x y]} (:params segment)]
                (.setUint16 dview  (+ offset 0) 1)
                (.setFloat32 dview (+ offset 20) x)
                (.setFloat32 dview (+ offset 24) y))
              :line-to
              (let [{:keys [x y]} (:params segment)]
                (.setUint16 dview  (+ offset 0) 2)
                (.setFloat32 dview (+ offset 20) x)
                (.setFloat32 dview (+ offset 24) y))
              :curve-to
              (let [{:keys [c1x c1y c2x c2y x y]} (:params segment)]
                (.setUint16 dview  (+ offset 0) 3)
                (.setFloat32 dview (+ offset 4) c1x)
                (.setFloat32 dview (+ offset 8) c1y)
                (.setFloat32 dview (+ offset 12) c2x)
                (.setFloat32 dview (+ offset 16) c2y)
                (.setFloat32 dview (+ offset 20) x)
                (.setFloat32 dview (+ offset 24) y))

              :close-path
              (.setUint16 dview (+ offset 0) 4))
            (recur (inc index)))))
      buffer))

#_(defn buffer->content
    "Converts the a buffer to a path content vector"
    [buffer]
    (assert (instance? js/ArrayBuffer buffer) "expected ArrayBuffer instance")
    (let [total  (/ (.-byteLength buffer) command-size)
          dview  (new js/DataView buffer)]
      (loop [index  0
             result []]
        (if (< index total)
          (let [offset  (* index command-size)
                type    (.getUint16 dview (+ offset 0))
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
          result))))
