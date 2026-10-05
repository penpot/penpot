;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS SUBSIDIARY SL

(ns benches.render-wasm.report.analysis
  "Calculates statistics and median changes for benchmark measurements.

  summarize/summarize-metric passes one vector to `statistics`. An attempt is
  one execution of a benchmark case. Each value represents one attempt, including
  a batch average: the attempt's total divided by its number of inner calls.
  compare/compare-runs passes baseline and candidate vectors to changes.
  Those callers exclude warmups, failed attempts and invalid measurements,
  and supply already validated finite values and resolved settings.

  Report fields use maps: {:value number} contains a calculated number;
  {:lower number :upper number} contains interval bounds; {:reason keyword}
  explains why a valid input cannot supply that result. For example,
  :insufficient-observations can include :minimum, the required sample size.
  This contract preserves unavailable fields through Transit and lets
  report.format display the reason. quantile and resolve-settings reject invalid
  arguments at their boundaries.

  Resampling draws a sample of the original size, allowing repeated values.
  Repeating this process gives medians whose central :confidence fraction
  defines a percentile confidence interval. These intervals describe the
  supplied measurements; evolving caches limit their interpretation.

  :method-version records the calculation version for saved reports.
  Version 1 is the only supported version."
  (:require
   [app.common.schema :as sm]
   [benches.render-wasm.random :as random]))

(def defaults
  "Default settings for report calculations.
  :draws is the number of replacement samples; :seed controls their generator.
  :confidence is the fraction between interval bounds. :min-ci, :min-p95 and
  :min-p99 set the required observation counts for those results.
  :method-version records the calculation rules, currently version 1."
  {:method-version 1
   :draws 2000
   :seed 0x70656e70
   :confidence 0.95
   :min-ci 10
   :min-p95 100
   :min-p99 500})

(def schema:settings
  "Valid settings for calculation version, sample draws, seed and report limits.
  The seed is an integer in [0, 2^32); confidence lies strictly between 0 and 1.
  Draw and minimum observation counts are positive integers."
  [:map {:closed true}
   [:method-version [:= 1]]
   [:draws [:int {:min 1 :max 2147483647}]]
   [:seed [:fn random/seed?]]
   [:confidence [:fn #(and (number? %) (js/Number.isFinite %) (< 0 % 1))]]
   [:min-ci [:int {:min 1}]]
   [:min-p95 [:int {:min 1}]]
   [:min-p99 [:int {:min 1}]]])

(defn resolve-settings
  "Returns defaults merged with supplied overrides, or defaults for nil.
  Accepts a complete settings map too. Invalid values or unknown keys throw
  ::invalid-settings. Callers store this resolved map with their results."
  [overrides]
  (let [settings (merge defaults overrides)]
    (when-not (sm/validate schema:settings settings)
      (throw (ex-info "invalid analysis settings" {:type ::invalid-settings
                                                   :settings settings})))
    settings))

(defn- finite-number?
  "Returns true for numbers other than NaN and positive or negative infinity."
  [value]
  (and (number? value) (js/Number.isFinite value)))

(defn- observation-vector
  "Returns a vector of supplied finite measurements, or an empty vector for nil.
  A non-sequence or any non-finite/non-numeric entry throws ::invalid-observations.
  Report callers exclude unusable raw entries before requesting calculations."
  [values]
  (when-not (and (or (nil? values) (sequential? values))
                 (every? finite-number? values))
    (throw (ex-info "observations must be a sequence of finite numbers"
                    {:type ::invalid-observations :values values})))
  (vec values))

(defn- sorted-quantile
  "Returns an interpolated value from a nonempty vector sorted in ascending order.
  Probability 0 selects the minimum, 1 the maximum and 0.5 the median.
  At position (count - 1) * probability, a fractional index gives the weighted
  average of its neighboring values. Callers validate the probability."
  [sorted probability]
  (let [position (* (dec (count sorted)) probability)
        lower    (js/Math.floor position)
        upper    (js/Math.ceil position)
        weight   (- position lower)]
    (+ (* (- 1 weight) (nth sorted lower))
       (* weight (nth sorted upper)))))

(defn quantile
  "Returns an interpolated quantile as a number, or nil for an empty sequence.
  Probability 0 selects the minimum, 1 the maximum and 0.5 the median.
  For example, [1 2 3 4] at probability 0.25 gives 1.75.
  Values must form a sequence of finite numbers. Probability must be a finite
  number in [0, 1]. Invalid arguments throw ::invalid-observations or
  ::invalid-probability; report callers handle unavailable results separately."
  [values probability]
  (when-not (and (finite-number? probability) (<= 0 probability 1))
    (throw (ex-info "quantile probability must be a finite number in [0, 1]"
                    {:type ::invalid-probability :probability probability})))
  (let [values (observation-vector values)]
    (when (seq values)
      (sorted-quantile (vec (sort values)) probability))))

(defn- number-result
  "Returns {:value value} for a finite calculated number.
  Non-finite arithmetic yields {:reason :undefined-estimate}, so a report can
  retain the field and explain why it has no number."
  [value]
  (if (finite-number? value)
    {:value value}
    {:reason :undefined-estimate}))

(defn- resampled-median
  "Returns the median of one replacement sample drawn from a nonempty vector.
  The sample has the same size as values; repeated indices are allowed.
  rng is a function that draws a number from [0, 1) on each call."
  [values rng]
  (let [n (count values)
        sample (vec (sort (repeatedly n #(nth values (js/Math.floor (* n (rng)))))))]
    (sorted-quantile sample 0.5)))

(defn- interval
  "Returns bounds containing the central confidence fraction of calculated draws.
  For confidence 0.95, the bounds are the 0.025 and 0.975 quantiles.
  Empty or non-finite draws yield {:reason :undefined-bootstrap-estimate};
  callers retain that reason rather than discarding an undefined draw."
  [values confidence]
  (if (and (seq values) (every? finite-number? values))
    (let [sorted (vec (sort values))
          tail (/ (- 1 confidence) 2)
          lower (sorted-quantile sorted tail)
          upper (sorted-quantile sorted (- 1 tail))]
      (if (and (finite-number? lower) (finite-number? upper))
        {:lower lower :upper upper}
        {:reason :undefined-bootstrap-estimate}))
    {:reason :undefined-bootstrap-estimate}))

(defn- quantile-result
  "Returns a quantile in {:value number} when the sorted sample reaches minimum.
  Otherwise returns {:reason :insufficient-observations :minimum minimum}.
  Probability selects the quantile, such as 0.95 for p95."
  [sorted probability minimum]
  (if (< (count sorted) minimum)
    {:reason :insufficient-observations :minimum minimum}
    (number-result (sorted-quantile sorted probability))))

(defn statistics
  "Returns statistics for one vector of finite measurement values.

  `values` must be an already validated vector of finite measurements, and
  `settings` must be the complete map returned by `resolve-settings`.
  Callers enforce these preconditions.
  `:count` is the number of original measurements, including each batch average
  once. Numeric fields contain {:value number}; unavailable fields contain
  {:reason keyword}, with :minimum for an observation requirement.

  :sample-sd is sqrt(sum((x - mean)^2) / (count - 1)).
  :mad is the median absolute distance from the median, without scaling.
  :p95 and :p99 are observed 0.95 and 0.99 quantiles. :median-ci contains
  bounds from repeatedly drawing replacement samples and taking their medians.
  Empty input retains every field with :no-valid-observations.
  Insufficient counts and undefined arithmetic retain reasons."
  [values settings]
  (let [{:keys [draws seed confidence min-ci min-p95 min-p99]} settings
        n                                                      (count values)]
    (if (zero? n)
      (let [absent {:reason :no-valid-observations}]
        {:count 0 :mean absent :median absent :min absent :max absent
         :sample-sd absent :mad absent :median-ci absent :p95 absent :p99 absent})
      (let [sorted (vec (sort values))
            mean   (reduce (fn [total x] (+ total (/ x n))) 0 values)
            median (sorted-quantile sorted 0.5)
            rng    (random/create seed)]
        {:count n
         :mean (number-result mean)
         :median (number-result median)
         :min (number-result (first sorted))
         :max (number-result (peek sorted))
         :sample-sd (if (= n 1)
                      {:reason :insufficient-observations :minimum 2}
                      (number-result (js/Math.sqrt
                                      (reduce (fn [total x]
                                                (+ total (/ (* (- x mean) (- x mean)) (dec n))))
                                              0 values))))
         :mad (number-result (sorted-quantile (vec (sort (map #(js/Math.abs (- % median)) values))) 0.5))
         :median-ci (if (< n min-ci)
                      {:reason :insufficient-observations :minimum min-ci}
                      (interval (vec (repeatedly draws #(resampled-median values rng)))
                                confidence))
         :p95 (quantile-result sorted 0.95 min-p95)
         :p99 (quantile-result sorted 0.99 min-p99)}))))

(defn changes
  "Returns candidate-minus-baseline changes between the two observed medians.
  Inputs must be already validated vectors of finite measurements; settings
  must be the complete map returned by resolve-settings. Callers enforce these
  preconditions. :absolute and :percentage contain
  {:value number}; :absolute-ci and :percentage-ci contain {:lower number :upper number}.
  Any unavailable result carries {:reason keyword} instead.

  Each interval draw samples both inputs independently, allowing repeated
  measurements, then calculates the change between those two sample medians.
  Both original counts must reach :min-ci. A zero observed baseline suppresses
  percentages; any zero resampled baseline suppresses the whole percentage
  interval. Empty inputs or undefined arithmetic retain reasons.
  compare/compare-runs calls this once per shared metric."
  [baseline candidate settings]
  (let [{:keys [draws seed confidence min-ci]} settings]
    (if (or (empty? baseline) (empty? candidate))
      {:absolute {:reason :no-valid-observations}
       :percentage {:reason :no-valid-observations}
       :absolute-ci {:reason :no-valid-observations}
       :percentage-ci {:reason :no-valid-observations}}
      (let [b         (sorted-quantile (vec (sort baseline)) 0.5)
            c         (sorted-quantile (vec (sort candidate)) 0.5)
            supported (and (>= (count baseline) min-ci) (>= (count candidate) min-ci))
            rng       (random/create seed)
            samples   (when supported
                        (vec (repeatedly draws
                                         (fn [] [(resampled-median baseline rng)
                                                 (resampled-median candidate rng)]))))
            low-count {:reason :insufficient-observations :minimum min-ci}]
        {:absolute (number-result (- c b))
         :percentage (if (zero? b) {:reason :zero-baseline} (number-result (* 100 (/ (- c b) b))))
         :absolute-ci (if supported
                        (interval (mapv (fn [[b c]] (- c b)) samples) confidence)
                        low-count)
         :percentage-ci (cond
                          (zero? b) {:reason :zero-baseline}
                          (not supported) low-count
                          (some (fn [[b _]] (zero? b)) samples) {:reason :zero-bootstrap-baseline}
                          :else (interval (mapv (fn [[b c]] (* 100 (/ (- c b) b))) samples)
                                          confidence))}))))
