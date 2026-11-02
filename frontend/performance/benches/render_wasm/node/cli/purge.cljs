;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC

(ns benches.render-wasm.node.cli.purge
  "Options for purging managed benchmark artifacts."
  (:require
   [benches.render-wasm.node.cli.shared :as shared]))

(def targets [:builds :runs :comparisons :all])

(def schema:options
  [:map {:closed true}
   [:target (into [:enum] targets)]])

(def ^:const usage "runner purge builds|runs|comparisons|all [options]")

(def ^:const description "Purge files in one explicit category of managed benchmark artifacts.")

(def cli-options [])

(defn resolve-options
  "Resolves the required artifact category."
  [options arguments]
  (shared/check-arguments arguments 1)
  (let [target (some #(when (= (name %) (first arguments)) %) targets)]
    (when-not target
      (throw (ex-info "purge requires builds, runs, comparisons or all"
                      {:type ::shared/invalid-args})))
    (assoc options :target target)))
