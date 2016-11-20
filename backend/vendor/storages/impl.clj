;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) 2016 Andrey Antukh <niwi@niwi.nz>

(ns storages.impl
  "Implementation details and helpers."
  (:require [storages.proto :as pt]
            [storages.util :as util]
            [buddy.core.codecs :as codecs]
            [clojure.java.io :as io])
  (:import java.io.File
           java.io.ByteArrayInputStream
           java.io.InputStream
           java.net.URL
           java.net.URI
           java.nio.file.Path
           java.nio.file.Paths
           java.nio.file.Files))

(extend-protocol pt/IContent
  String
  (-input-stream [v]
    (ByteArrayInputStream. (codecs/str->bytes v)))

  Path
  (-input-stream [v]
    (io/input-stream v))

  File
  (-input-stream [v]
    (io/input-stream v))

  URI
  (-input-stream [v]
    (io/input-stream v))

  URL
  (-input-stream [v]
    (io/input-stream v))

  InputStream
  (-input-stream [v]
    v)

  ratpack.http.TypedData
  (-input-stream [this]
    (.getInputStream this)))

(extend-protocol pt/IUri
  URI
  (-uri [v] v)

  String
  (-uri [v] (URI. v)))

(def ^:private empty-string-array
  (make-array String 0))

(extend-protocol pt/IPath
  Path
  (-path [v] v)

  URI
  (-path [v] (Paths/get v))

  URL
  (-path [v] (Paths/get (.toURI v)))

  String
  (-path [v] (Paths/get v empty-string-array))

  clojure.lang.Sequential
  (-path [v]
    (reduce #(.resolve %1 %2)
            (pt/-path (first v))
            (map pt/-path (rest v)))))

(defn- path->input-stream
  [^Path path]
  (Files/newInputStream path util/read-open-opts))

(defn- path->output-stream
  [^Path path]
  (Files/newOutputStream path util/write-open-opts))

(extend-type Path
  io/IOFactory
  (make-reader [path opts]
    (let [^InputStream is (path->input-stream path)]
      (io/make-reader is opts)))
  (make-writer [path opts]
    (let [^OutputStream os (path->output-stream path)]
      (io/make-writer os opts)))
  (make-input-stream [path opts]
    (let [^InputStream is (path->input-stream path)]
      (io/make-input-stream is opts)))
  (make-output-stream [path opts]
    (let [^OutputStream os (path->output-stream path)]
      (io/make-output-stream os opts))))

(extend-type ratpack.http.TypedData
  io/IOFactory
  (make-reader [td opts]
    (let [^InputStream is (.getInputStream td)]
      (io/make-reader is opts)))
  (make-writer [path opts]
    (throw (UnsupportedOperationException. "read only object")))
  (make-input-stream [td opts]
    (let [^InputStream is (.getInputStream td)]
      (io/make-input-stream is opts)))
  (make-output-stream [path opts]
    (throw (UnsupportedOperationException. "read only object"))))

