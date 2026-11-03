;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS SUBSIDIARY SL

(ns benches.render-wasm.node.cli.compare
  "Options for comparing saved benchmark records."
  (:require
   [app.common.schema :as sm]
   [benches.render-wasm.node.cli.shared :as shared]
   [clojure.string :as str]))

(def schema:case-pair
  [:tuple ::sm/text ::sm/text])

(def schema:options
  [:merge shared/schema:comparison-permissions
   [:map {:closed true}
    [:baseline-path ::sm/text]
    [:candidate-path ::sm/text]
    [:case-pair {:default nil} [:maybe schema:case-pair]]]])

(def ^:const usage "runner compare BASELINE CANDIDATE [options]")

(def ^:const description
  "Compare two saved records. Paths resolve from the caller's working directory.
--cases selects one exact baseline,candidate pair by saved case ID or name.")

(def ^:private valid-case-pair?
  (sm/validator schema:case-pair))

(defn- parse-case-pair
  [text]
  (let [pair (str/split text #"," -1)]
    (when-not (valid-case-pair? pair)
      (throw (ex-info "--cases requires one exact nonblank A,B pair"
                      {:type ::shared/invalid-args})))
    pair))

(def cli-options
  (into [[nil "--cases A,B" "Select one baseline,candidate pair" :id :case-pair
          :parse-fn parse-case-pair]]
        shared/comparison-options))

(defn resolve-options
  "Resolves two literal paths and supplied comparison flags."
  [options arguments]
  (shared/check-arguments arguments 2)
  (assoc options :baseline-path (first arguments) :candidate-path (second arguments)))
