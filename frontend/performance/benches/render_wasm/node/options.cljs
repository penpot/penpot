;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS SUBSIDIARY SL

(ns benches.render-wasm.node.options
  "Command defaults and feature validation."
  (:require
   [clojure.string :as str]))

(def default-build-timeout-ms 300000)
(def default-startup-timeout-ms 30000)

(def ^:private diagnostic-features #{"stats" "profile" "profile-macros" "profile-raf"})

(defn feature-selection
  "Validates measurement feature selection before allocating an attempt.
  Cargo validates functional feature names during the retained build."
  [{:keys [default-features? features]
    :or {default-features? true features []}}]
  (when-not (and (boolean? default-features?) (vector? features)
                 (every? #(and (string? %) (re-matches #"[A-Za-z0-9_-]+" %)) features))
    (throw (ex-info "invalid Cargo feature selection" {:type ::invalid-features})))
  (when-let [selected (seq (filter diagnostic-features features))]
    (throw (ex-info "measurement builds cannot enable diagnostic Cargo features"
                    {:type ::diagnostic-features :features (vec selected)})))
  {:default-features? default-features? :features (vec (sort (distinct features)))})

(defn parse-feature-list
  [text]
  (:features (feature-selection {:features (mapv str/trim (str/split text #"," -1))})))

(defn console-level
  "Returns the console level selected by quiet or verbose options."
  [opts]
  (cond (:quiet opts) :warn (:verbose opts) :debug :else :info))

(defn color-mode
  "Returns automatic, forced or disabled console coloring."
  [opts]
  (case (:color opts) true :always false :never :auto))
