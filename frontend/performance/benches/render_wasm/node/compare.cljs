;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS SUBSIDIARY SL

(ns benches.render-wasm.node.compare
  "Offline comparison of two saved RunRecords.

  Inputs are user file paths resolved from the caller's cwd. The namespace
  depends only on record decoding and the shared report pipeline; it starts no
  build, host, browser or renderer."
  (:require
   ["fs" :as fs]
   ["path" :as path]
   [benches.render-wasm.report.compare :as compare]
   [benches.render-wasm.report.format :as format]
   [benches.render-wasm.result :as result]))

(defn- read-run
  [file]
  (result/decode (fs/readFileSync (path/resolve file) "utf8")))

(defn execute
  "Reads and compares two saved run files.

  options: :compare-cases is one exact A,B selector; A belongs to baseline and
  B to the candidate. :force bypasses compatibility checks. Returns the
  validated `comparison` and its formatted text."
  [baseline-path candidate-path {:keys [compare-cases force]}]
  (let [baseline   (read-run baseline-path)
        candidate  (read-run candidate-path)
        comparison (result/check-record
                    (compare/compare-runs
                     baseline candidate
                     (cond-> {:force (boolean force)}
                       compare-cases
                       (assoc :pairs (compare/selector-pair baseline candidate
                                                            compare-cases)))))]
    {:comparison comparison
     :text (format/format-comparison comparison)}))
