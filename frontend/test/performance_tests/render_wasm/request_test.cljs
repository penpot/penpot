;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS SUBSIDIARY SL

(ns performance-tests.render-wasm.request-test
  (:require
   [app.common.schema :as sm]
   [benches.render-wasm.node.cli.request :as request]
   [cljs.test :as t :include-macros true]
   [performance-tests.render-wasm.test-helpers :refer [failure-data]]))

(def ^:private console-defaults
  {:level :info :color :auto})

(def ^:private measurement-defaults
  {:seed 42 :filter nil :warmups 3 :repetitions 10
   :timeout-ms 30000 :startup-timeout-ms 30000 :output-dir nil
   :screenshots false :require-gpu false})

(def ^:private comparison-defaults
  {:allow-build-differences false :allow-scene-differences false
   :allow-all-differences false})

(def ^:private existing-run
  {:command "run" :console console-defaults
   :options (assoc measurement-defaults
                   :build {:kind :existing :id "ready-build"}
                   :remove false)})

(def ^:private default-cases
  [[{:command "build"}
    {:command "build" :console console-defaults
     :options {:default-features true :features [] :build-timeout-ms 300000}}]
   [{:command "run" :console {} :options {:build {:kind :prepare}}}
    {:command "run" :console console-defaults
     :options (assoc measurement-defaults
                     :build {:kind :prepare :default-features true
                             :features [] :build-timeout-ms 300000}
                     :remove false)}]
   [{:command "run" :options {:build {:kind :existing :id "ready-build"}}}
    existing-run]
   [{:command "ab" :options {:features ["render-cache"]}}
    {:command "ab" :console console-defaults
     :options (merge measurement-defaults comparison-defaults
                     {:features ["render-cache"] :max-features 3
                      :confirm false :dry-run false :build-timeout-ms 300000})}]
   [{:command "compare"
     :options {:baseline-path "base.transit" :candidate-path "candidate.transit"}}
    {:command "compare" :console console-defaults
     :options (assoc comparison-defaults
                     :baseline-path "base.transit"
                     :candidate-path "candidate.transit" :case-pair nil)}]
   [{:command "merge"
     :options {:base-path "base.transit" :additional-path "extra.transit"}}
    {:command "merge" :console console-defaults
     :options {:base-path "base.transit" :additional-path "extra.transit"
               :output-dir nil}}]
   [{:command "purge" :options {:target :runs}}
    {:command "purge" :console console-defaults :options {:target :runs}}]
   [{:command "help"}
    {:command "help" :console console-defaults :options {:topic nil}}]])

(t/deftest defaults-produce-complete-command-requests
  (doseq [[partial expected] default-cases]
    (t/testing (pr-str partial)
      (t/is (= expected (request/check (request/apply-defaults partial)))))))

(t/deftest explicit-values-and-literal-text-survive-resolution
  (let [partial  {:command "run" :console {:level :warn :color :never}
                  :options {:seed 0 :warmups 0 :filter "  " :output-dir " ./runs "
                            :build {:kind :prepare :default-features false
                                    :features []}}}
        expected {:command "run" :console {:level :warn :color :never}
                  :options (assoc measurement-defaults
                                  :seed 0 :warmups 0 :filter "  " :output-dir " ./runs "
                                  :build {:kind :prepare :default-features false
                                          :features [] :build-timeout-ms 300000}
                                  :remove false)}]
    (t/is (= expected (request/check (request/apply-defaults partial)))))
  (let [partial {:command "compare"
                 :options {:baseline-path " ./base " :candidate-path " ./candidate "
                           :case-pair [" rects/load " "other/load"]}}]
    (t/is (= (assoc partial :console console-defaults
                    :options (merge comparison-defaults (:options partial)))
             (request/check (request/apply-defaults partial)))))
  (t/is (= existing-run
           (request/check
            (request/apply-defaults (assoc-in existing-run [:options :warmups] nil))))))

(t/deftest required-selections-and-paths-have-no-defaults
  (doseq [partial [{}
                   {:command "unknown"}
                   {:command "run"}
                   {:command "run" :options {:build {}}}
                   {:command "run" :options {:build {:kind :unknown}}}
                   {:command "run" :options {:build {:kind :existing}}}
                   {:command "ab"}
                   {:command "compare"}
                   {:command "compare" :options {:baseline-path "base"}}
                   {:command "compare" :options {:candidate-path "candidate"}}
                   {:command "merge" :options {:base-path "base"}}
                   {:command "merge" :options {:additional-path "additional"}}
                   {:command "purge"}]]
    (t/testing (pr-str partial)
      (t/is (= ::request/invalid-request
               (:type (failure-data #(request/check (request/apply-defaults partial)))))))))

(t/deftest receiver-requires-complete-data-and-reports-the-field
  (doseq [[_ complete] default-cases]
    (t/is (= ::request/invalid-request
             (:type (failure-data #(request/check (dissoc complete :console)))))))
  (doseq [incomplete [(dissoc existing-run :options)
                      (update existing-run :console dissoc :level)
                      (update existing-run :options dissoc :warmups)
                      (assoc-in existing-run [:options :warmups] nil)
                      (assoc-in existing-run [:options :remove] nil)
                      (assoc-in existing-run [:console :level] nil)]]
    (let [data (failure-data #(request/check incomplete))]
      (t/is (= ::request/invalid-request (:type data)))
      (t/is (seq (:errors (::sm/explain data))))))
  (let [data (failure-data #(request/check (assoc-in existing-run [:options :warmups] -1)))]
    (t/is (some #(= [:options :warmups] (:in %)) (:errors (::sm/explain data))))))

(t/deftest receiver-rejects-extra-fields-at-every-map-boundary
  (doseq [[path value] [[[:extra] true]
                        [[:console :stream] "stdout"]
                        [[:options :browser] {}]
                        [[:options :build :features] []]
                        [[:options :build :default-features] false]
                        [[:options :build :build-timeout-ms] 1000]]]
    (let [input    (assoc-in existing-run path value)
          resolved (request/apply-defaults input)]
      (t/is (= input resolved))
      (t/is (= ::request/invalid-request
               (:type (failure-data #(request/check resolved)))))))
  (doseq [partial [{:command "run" :options {:build {:kind :prepare :id "ready-build"}}}
                   {:command "ab" :options {:features ["cache"] :default-features false}}
                   {:command "ab" :options {:features ["cache"] :remove false}}
                   {:command "ab" :options {:features ["cache"] :build {:kind :prepare}}}]]
    (t/is (= ::request/invalid-request
             (:type (failure-data #(request/check (request/apply-defaults partial))))))))

(t/deftest numeric-constraints-match-seeds-counts-and-node-timers
  (doseq [[field values] [[:seed [0 4294967295]]
                          [:warmups [0 js/Number.MAX_SAFE_INTEGER]]
                          [:repetitions [1 js/Number.MAX_SAFE_INTEGER]]
                          [:timeout-ms [1 2147483647]]
                          [:startup-timeout-ms [1 2147483647]]]]
    (doseq [value values]
      (let [input (assoc-in existing-run [:options field] value)]
        (t/is (= input (request/check input))))))
  (doseq [[field values] [[:seed [-1 4294967296 1.5 "42" js/NaN js/Infinity]]
                          [:warmups [-1 (inc js/Number.MAX_SAFE_INTEGER) 0.5 "3"]]
                          [:repetitions [0 (inc js/Number.MAX_SAFE_INTEGER) 1.5]]
                          [:timeout-ms [0 2147483648 1.5 "30000"]]
                          [:startup-timeout-ms [0 2147483648]]]]
    (doseq [value values]
      (t/testing (str field " " value)
        (let [input    (assoc-in existing-run [:options field] value)
              resolved (request/apply-defaults input)]
          (when (string? value)
            (t/is (= value (get-in resolved [:options field]))))
          (t/is (= ::request/invalid-request
                   (:type (failure-data #(request/check resolved)))))))))
  (doseq [command ["build" "ab"]
          value [0 2147483648 1.5]]
    (let [partial {:command command :options {:features ["cache"] :build-timeout-ms value}}]
      (t/is (= ::request/invalid-request
               (:type (failure-data #(request/check (request/apply-defaults partial)))))))))

(t/deftest command-values-use-their-domain-constraints
  (doseq [partial [{:command "run" :options {:build {:kind :existing :id "../build"}}}
                   {:command "run" :options {:build {:kind :existing :id ""}}}
                   {:command "build" :options {:features ["bad feature"]}}
                   {:command "build" :options {:features [nil]}}
                   {:command "ab" :options {:features []}}
                   {:command "ab" :options {:features ["cache"] :max-features 0}}
                   {:command "ab" :options {:features ["cache"] :max-features 7}}
                   {:command "ab" :options {:features ["cache"] :max-features 1.5}}
                   {:command "compare" :options {:baseline-path " " :candidate-path "c"}}
                   {:command "merge" :options {:base-path "b" :additional-path ""}}
                   {:command "merge" :options {:base-path "b" :additional-path "a"
                                               :output-dir " "}}
                   {:command "purge" :options {:target :unknown}}
                   {:command "help" :options {:topic "unknown"}}
                   {:command "help" :console {:level :error}}
                   {:command "help" :console {:color true}}]]
    (t/testing (pr-str partial)
      (let [resolved (request/apply-defaults partial)]
        (t/is (= ::request/invalid-request
                 (:type (failure-data #(request/check resolved))))))))
  (doseq [pair [["only"] ["one" "two" "three"] ["" "two"] ["one" " "]]]
    (t/is (= ::request/invalid-request
             (:type (failure-data
                     #(request/check
                       (request/apply-defaults
                        {:command "compare" :options {:baseline-path "b" :candidate-path "c"
                                                      :case-pair pair}})))))))
  (doseq [filter-text [nil "" " " " rects "]]
    (let [input (assoc-in existing-run [:options :filter] filter-text)]
      (t/is (= input (request/check (request/apply-defaults input))))))
  (doseq [target [:builds :runs :comparisons :all]]
    (t/is (= target
             (get-in (request/check
                      (request/apply-defaults {:command "purge" :options {:target target}}))
                     [:options :target])))))
