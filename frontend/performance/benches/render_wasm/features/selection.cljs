;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS SUBSIDIARY SL

(ns benches.render-wasm.features.selection
  "Feature validation.")

(def ^:private diagnostic-features #{"stats" "profile" "profile-macros" "profile-raf"})

(defn feature-name?
  "True for a Cargo feature name made of letters, digits, underscores or hyphens."
  [value]
  (and (string? value)
       (boolean (re-matches #"[A-Za-z0-9_-]+" value))))

(defn feature-selection
  "Validates measurement feature selection before allocating an attempt.
  Cargo validates functional feature names during the retained build."
  [{:keys [default-features? features]
    :or {default-features? true features []}}]
  (when-not (and (boolean? default-features?) (vector? features)
                 (every? feature-name? features))
    (throw (ex-info "invalid Cargo feature selection" {:type ::invalid-features})))
  (when-let [selected (seq (filter diagnostic-features features))]
    (throw (ex-info "measurement builds cannot enable diagnostic Cargo features"
                    {:type ::diagnostic-features :features (vec selected)})))
  {:default-features? default-features? :features (vec (sort (distinct features)))})
