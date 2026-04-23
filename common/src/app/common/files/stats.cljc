;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC

(ns app.common.files.stats
  "Pure helpers that compute aggregate statistics for a file data map.

  Given a decoded file data structure (the value stored under `:data`
  on a file row), produces a small map with page/shape/library counts.
  Intended to be cheap — a single pass over each page's `:objects`
  map, no database access, no side effects."
  (:require
   [app.common.uuid :as uuid]))

(def empty-shape-counts
  {:total 0 :by-type {}})

(defn- inc-type
  [by-type shape-type]
  (if (nil? shape-type)
    by-type
    (update by-type shape-type (fnil inc 0))))

(defn count-shapes-by-type
  "Walk an `:objects` map of a single page and return
   `{:total N :by-type {:rect N :frame N ...}}`. The synthetic root
   shape at `uuid/zero` is skipped so it never contributes to totals."
  [objects]
  (if (empty? objects)
    empty-shape-counts
    (reduce-kv
     (fn [acc id shape]
       (if (= id uuid/zero)
         acc
         (-> acc
             (update :total inc)
             (update :by-type inc-type (:type shape)))))
     empty-shape-counts
     objects)))

(defn- merge-shape-counts
  [a b]
  {:total   (+ (:total a) (:total b))
   :by-type (merge-with + (:by-type a) (:by-type b))})

(defn- aggregate-shape-counts
  [pages-index]
  (transduce
   (map (comp count-shapes-by-type :objects))
   (completing merge-shape-counts)
   empty-shape-counts
   (vals pages-index)))

(defn calc-file-stats
  "Given a decoded file data map with the standard keys
   `:pages-index`, `:components`, `:deleted-components`, `:colors`
   and `:typographies`, return per-file aggregates.

   The result is a plain map suitable for serialization; it never
   contains any pointer-map or objects-map instances."
  [fdata]
  (let [pages-index        (get fdata :pages-index)
        components         (get fdata :components)
        deleted-components (get fdata :deleted-components)
        colors             (get fdata :colors)
        typographies       (get fdata :typographies)]
    {:page-count              (count pages-index)
     :shape-counts            (aggregate-shape-counts pages-index)
     :component-count         (count components)
     :deleted-component-count (count deleted-components)
     :color-count             (count colors)
     :typography-count        (count typographies)}))
