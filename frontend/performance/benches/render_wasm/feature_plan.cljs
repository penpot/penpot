;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS SUBSIDIARY SL

(ns benches.render-wasm.feature-plan
  "Requested-feature configurations and comparison edges."
  (:require
   [benches.render-wasm.node.options :as options]
   [clojure.string :as str]))

(def ^:const default-max-features 3)

(defn check-manifest
  "Checks names and empty declared defaults against Cargo metadata's feature
  definitions. Cargo owns activation; these definitions are not resolved here."
  [features definitions]
  (let [features (:features (options/feature-selection {:features features}))]
    (when-not (and (map? definitions)
                   (every? string? (keys definitions))
                   (every? #(and (vector? %) (every? string? %)) (vals definitions)))
      (throw (ex-info "invalid Cargo feature definitions" {:type ::invalid-manifest})))
    (when (seq (get definitions "default"))
      (throw (ex-info "ab requires empty declared Cargo defaults" {:type ::nonempty-defaults})))
    (when-let [unknown (seq (remove #(contains? definitions %) features))]
      (throw (ex-info "unknown requested Cargo features"
                      {:type ::unknown-features :features (vec unknown)})))
    features))

(defn plan
  "Plans sorted, MSB-first reflected Gray configurations and all off-to-on
  edges. Each selection disables Cargo defaults. Execution needs confirmation
  above eight configurations or with an empty filter; dry-run needs none."
  [features {:keys [max-features confirm dry-run filter]
             :or {max-features default-max-features confirm false dry-run false}}]
  (let [features (:features (options/feature-selection {:features features}))
        n        (count features)]
    (when-not (and (int? max-features) (<= 1 max-features 6))
      (throw (ex-info "max-features must be an integer from 1 to 6" {:type ::invalid-limit})))
    (when-not (and (boolean? confirm) (boolean? dry-run)
                   (or (nil? filter) (string? filter)))
      (throw (ex-info "invalid feature planning options" {:type ::invalid-options})))
    (when (zero? n)
      (throw (ex-info "ab requires at least one requested feature" {:type ::missing-features})))
    (when (> n max-features)
      (throw (ex-info "requested features exceed max-features"
                      {:type ::too-many-features :count n :max-features max-features})))
    (when (and (not dry-run) (not confirm)
               (or (> (bit-shift-left 1 n) 8) (str/blank? filter)))
      (throw (ex-info "ab requires --confirm above eight configurations or with an empty filter"
                      {:type ::confirmation-required})))
    (let [slug           (fn [bits]
                           (str/join (map #(if (bit-test bits %) "1" "0")
                                          (reverse (range n)))))
          gray-values    (mapv #(bit-xor % (bit-shift-right % 1))
                               (range (bit-shift-left 1 n)))
          configurations (mapv (fn [bits]
                                 {:slug (slug bits)
                                  :selection {:default-features? false
                                              :features (into []
                                                              (keep-indexed
                                                               (fn [i feature]
                                                                 (when (bit-test bits (- n i 1))
                                                                   feature)))
                                                              features)}})
                               gray-values)
          edges (vec
                 (for [bits gray-values
                       [i feature] (map-indexed vector features)
                       :let [bit (- n i 1)]
                       :when (not (bit-test bits bit))]
                   {:from (slug bits)
                    :to (slug (bit-set bits bit))
                    :feature feature}))]
      {:features features :configurations configurations :edges edges})))
