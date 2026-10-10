;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS SUBSIDIARY SL

(ns app.common.test-helpers.normalize
  "Normalized file values, to compare two states of a file in tests (the
   undo/redo round trip of the composable runners, the golden master).

   Normalizing drops only what legitimately differs between two states that
   are the same for the user:
     - `:modified-at` of the file and of each component, bumped by every
       change, undo and redo included;
     - with `{:position-data? false}`, the shapes' `:position-data`, the text
       layout that WASM measurement regenerates.
   Shapes and the maps that hold them become plain maps, so a shape record
   compares equal to a map with the same attributes, and an objects map (see
   `app.common.types.objects-map`) to a plain map with the same entries.
   Everything else is kept, `:touched` and `:remote-synced` included."
  (:require
   [app.common.data :as d]
   [clojure.data :as data]))

(defn- map-vals
  "A plain map with `f` applied to each value of the map `m`, whatever its
   type."
  [f m]
  (into {} (map (fn [[k v]] [k (f v)])) m))

(defn- normalize-shape
  [shape {:keys [position-data?]}]
  (cond-> (into {} shape)
    (not position-data?) (dissoc :position-data)))

(defn- normalize-objects
  [objects opts]
  (map-vals #(normalize-shape % opts) objects))

(defn- normalize-page
  [page opts]
  (d/update-when page :objects normalize-objects opts))

(defn- normalize-component
  [component opts]
  (-> component
      (dissoc :modified-at)
      (d/update-when :objects normalize-objects opts)))

(defn normalize-file
  "The normalized value of `file` (see the namespace doc). Options:
   `:position-data?` (default true) keeps the shapes' `:position-data`."
  ([file] (normalize-file file {}))
  ([file opts]
   (let [opts (merge {:position-data? true} opts)]
     (-> file
         (dissoc :modified-at)
         (update :data
                 (fn [data]
                   (-> data
                       (d/update-when :pages-index
                                      (partial map-vals #(normalize-page % opts)))
                       (d/update-when :components
                                      (partial map-vals #(normalize-component % opts))))))))))

(defn diff
  "What differs between the values `a` and `b`, as `[only-in-a only-in-b]`
   (see `clojure.data/diff`)."
  [a b]
  (vec (take 2 (data/diff a b))))
