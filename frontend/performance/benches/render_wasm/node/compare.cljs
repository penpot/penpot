;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS SUBSIDIARY SL

(ns benches.render-wasm.node.compare
  "Offline comparison of two saved schema:run records.

  Inputs are user file paths resolved from the caller's cwd."
  (:require
   [benches.render-wasm.node.runs :as runs]
   [benches.render-wasm.report.compare :as compare]
   [benches.render-wasm.report.format :as format]
   [benches.render-wasm.result :as result]))

(defn execute
  "Reads and compares two saved run files.

  Runs are only compared if the build options and scene configuration match.
  These can be overriden via flags.

  It is also possible to restrict comparison to specific scene ids.

  Options:
   - `:cases` is one A,B selector, where A is a baseline an B the candidate.
   - The three `:allow-*-differences` options select build, scene or all
     compatibility permissions.

  Returns the validated `comparison` with its selected permissions, retained
  differences and formatted text."
  [baseline-path candidate-path {:keys [cases allow-build-differences
                                        allow-scene-differences allow-all-differences]}]
  (let [baseline   (runs/get-record baseline-path)
        candidate  (runs/get-record candidate-path)
        comparison (result/check-record
                    (compare/compare-runs
                     baseline candidate
                     (cond-> {:allow-build-differences (boolean allow-build-differences)
                              :allow-scene-differences (boolean allow-scene-differences)
                              :allow-all-differences (boolean allow-all-differences)}
                       cases
                       (assoc :pairs (compare/selector-pair baseline candidate
                                                            cases)))))]
    {:comparison comparison
     :text (format/format-comparison comparison)}))
