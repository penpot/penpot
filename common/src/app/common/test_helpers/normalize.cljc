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
   Shapes become plain maps, so a shape record and a map with the same
   attributes compare equal. Everything else is kept, `:touched` and
   `:remote-synced` included."
  (:require
   [app.common.data :as d]
   [clojure.data :as data]))

(defn- normalize-shape
  [shape {:keys [position-data?]}]
  (cond-> (into {} shape)
    (not position-data?) (dissoc :position-data)))

(defn- normalize-objects
  [objects opts]
  (update-vals objects #(normalize-shape % opts)))

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
                       (d/update-when :pages-index update-vals #(normalize-page % opts))
                       (d/update-when :components update-vals #(normalize-component % opts)))))))))

(defn diff
  "What differs between the values `a` and `b`, as `[only-in-a only-in-b]`
   (see `clojure.data/diff`)."
  [a b]
  (vec (take 2 (data/diff a b))))
