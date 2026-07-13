;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC Sucursal en España SL

(ns app.graph.report
  (:require
   [clojure.core :as c]))

(defn- println!
  [& lines]
  (doseq [line lines]
    (println line)))

(defn- section-title
  [title]
  (println! (str "\n" title)
            (str (apply str (repeat (count title) "─")))))

(defn- kv-line
  [k v]
  (format "  %-14s %s" (str k ":") v))

(defn- print-node-counts
  [nodes]
  (doseq [[table count] (sort-by first nodes)
          :when (pos? (long count))]
    (println! (kv-line table count))))

(defn print-ingest!
  "Pretty-print the result map returned by `app.graph.ingest/ingest-file!`."
  [{:keys [file-id revn name db-path schema-version projection transforms stats]}]
  (section-title "Graph ingest")
  (println! (kv-line "File" (str name " (" file-id ")"))
            (kv-line "Revision" revn)
            (kv-line "Schema" schema-version)
            (kv-line "Database" db-path))

  (when-let [pstats (:stats projection)]
    (section-title "Projection")
    (doseq [[k v] (sort-by key pstats)]
      (println! (kv-line (c/name k) v))))

  (section-title "Transforms")
  (println! (kv-line "Applied" (or (:transforms transforms) 0)))

  (when stats
    (section-title "Graph counts")
    (when-let [nodes (:nodes stats)]
      (println! "  Nodes")
      (print-node-counts nodes))
    (when-let [edges (:edges stats)]
      (println! "  Edges")
      (doseq [[rel count] (sort-by key edges)
              :when (pos? (long count))]
        (println! (kv-line (c/name rel) count)))))

  (println!)
  nil)
