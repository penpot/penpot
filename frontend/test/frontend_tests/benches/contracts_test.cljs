;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS SUBSIDIARY SL

(ns frontend-tests.benches.contracts-test
  (:require
   [app.common.schema :as sm]
   [app.common.transit :as transit]
   [benches.render-wasm.cases :as cases]
   [benches.render-wasm.scenes.core :as core :include-macros true]
   [cljs.test :as t :include-macros true]))

(defrecord Point [x y])

(def ^:private body-calls
  (atom []))

(defn- touch!
  [rtx]
  (swap! body-calls conj :touched)
  rtx)

(core/defscene :contracts-scene
  {:version 1
   :description "Synthetic scene for contract tests"
   :params-schema [:map {:closed true}]}
  (fn [params] params))

(core/defcase :contracts-scene/case :contracts-scene
  {:params {}
   :view {:scale 1 :x 0 :y 0}
   :context :fresh}
  (fn [ctx] (touch! ctx)))

(defn- failure-data
  [f]
  (try
    (f)
    nil
    (catch :default cause
      (ex-data cause))))

(defn- rect-cases
  ([] (rect-cases 42 "rects/"))
  ([master-seed filter]
   (cases/collect-cases {:master-seed master-seed :filter filter})))

(defn- with-synthetic-scene
  "Registers a synthetic scene and case under `scene-id`, runs `f`, then
  unregisters both so the live registry survives."
  [scene-id case-params f]
  (core/register-scene! {:id scene-id
                         :ns "contracts-test"
                         :version 1
                         :description "synthetic"
                         :params-schema [:map {:closed true} [:count [:int {:min 1 :max 100000}]]]
                         :build (fn [params] params)})
  (core/register-case! {:id (keyword (name scene-id) "case")
                        :scene scene-id
                        :ns "contracts-test"
                        :params case-params
                        :view {:scale 1 :x 0 :y 0}
                        :context :fresh})
  (try
    (f)
    (finally
      (core/unregister-case! (keyword (name scene-id) "case"))
      (core/unregister-scene! scene-id))))

(t/deftest defscene-registers-metadata-and-build
  (let [scene (core/registered-scene :rects)]
    (t/is (= :rects (:id scene)))
    (t/is (= "benches.render-wasm.scenes.rects" (:ns scene)))
    (t/is (= 1 (:version scene)))
    (t/is (= "Seeded rectangles with translucent fills and centered strokes"
             (:description scene)))
    (t/is (vector? (:params-schema scene)))
    (t/is (fn? (:build scene)))))

(t/deftest rects-cases-are-collected-in-declaration-order
  (let [collected (rect-cases)]
    (t/is (= [:rects/load :rects/pan :rects/zoom] (mapv :id collected)))))

(t/deftest collected-cases-survive-the-wire
  (doseq [case-desc (rect-cases)]
    (t/is (not (contains? case-desc :run!)) (str (:id case-desc)))
    (t/is (not (contains? case-desc :ns)) (str (:id case-desc)))
    (t/is (core/transit-round-trips? case-desc) (str (:id case-desc)))))

(t/deftest rects-cases-carry-declared-view-context-and-completion
  (let [collected (rect-cases)]
    (t/is (= [[:rects/load :fresh] [:rects/pan :reuse] [:rects/zoom :reuse]]
             (mapv (juxt :id :context) collected)))
    (doseq [case-desc collected]
      (t/is (= {:count 1000 :width 1920 :height 1080 :min-size 20 :max-size 100}
               (:params case-desc))
            (str (:id case-desc)))
      (t/is (= {:scale 1 :x 0 :y 0} (:view case-desc)) (str (:id case-desc)))
      (t/is (= :render-full (:completion case-desc)) (str (:id case-desc)))
      (t/is (= 1 (:batch-size case-desc)) (str (:id case-desc)))
      (t/is (= {:version 1} (:preparation case-desc)) (str (:id case-desc))))))

(t/deftest rects-cases-carry-scene-metadata-and-seed
  (let [collected (rect-cases)]
    (doseq [case-desc collected]
      (t/is (= :rects (:scene case-desc)))
      (t/is (= 1 (:scene-version case-desc)))
      (t/is (= "Seeded rectangles with translucent fills and centered strokes"
               (:scene-description case-desc)))
      (t/is (= 2602546180 (:scene-seed case-desc)) (str (:id case-desc)))))
  (let [collected (rect-cases 43 "rects/")]
    (t/is (every? #(= 2993218129 (:scene-seed %)) collected))))

(t/deftest derive-seed-depends-on-scene-and-params-only
  (let [params {:count 1000 :width 1920 :height 1080 :min-size 20 :max-size 100}
        base   (core/derive-seed 42 :rects params)]
    (t/is (= 2602546180 base))
    (t/is (= base (core/derive-seed 42 :rects (into {} (reverse (seq params))))))
    (t/is (= 2993218129 (core/derive-seed 43 :rects params)))
    (t/is (not= base (core/derive-seed 42 :rects (assoc params :count 10))))
    (t/is (not= base (core/derive-seed 42 :paths params)))))

(t/deftest derive-seed-rejects-keys-it-cannot-tell-apart
  (t/is (= ::core/invalid-params
           (:type (failure-data #(core/derive-seed 42 :rects {"count" 1})))))
  (t/is (= ::core/invalid-params
           (:type (failure-data #(core/derive-seed 42 :rects {:rects/count 1})))))
  (t/is (= :rects (:scene (failure-data #(core/derive-seed 42 :rects {"count" 1}))))))

(t/deftest unknown-params-are-rejected
  (with-synthetic-scene :contracts-unknown-params {:count 1 :bogus 2}
    (fn []
      (let [data (failure-data
                  #(cases/collect-cases {:master-seed 42
                                         :filter "contracts-unknown-params/"}))]
        (t/is (= ::cases/invalid-case (:type data)))
        (t/is (some? (::sm/explain data)))))))

(t/deftest out-of-range-count-is-rejected
  (with-synthetic-scene :contracts-bad-count {:count 0}
    (fn []
      (let [data (failure-data
                  #(cases/collect-cases {:master-seed 42
                                         :filter "contracts-bad-count/"}))]
        (t/is (= ::cases/invalid-case (:type data)))
        (t/is (some? (::sm/explain data)))))))

(t/deftest rects-workload-ranges-are-rejected
  (let [schema (:params-schema (core/registered-scene :rects))
        good   {:count 1000 :width 1920 :height 1080 :min-size 20 :max-size 100}]
    (t/is (true? (sm/validate schema good)))
    (t/testing "reversed size range"
      (t/is (false? (sm/validate schema (assoc good :min-size 100 :max-size 20)))))
    (t/testing "non-finite sizes"
      (doseq [bad [(assoc good :width js/Infinity)
                   (assoc good :height js/NaN)
                   (assoc good :min-size js/Infinity)
                   (assoc good :max-size js/NaN)]]
        (t/is (false? (sm/validate schema bad)) (pr-str bad))))))

(t/deftest view-coordinates-are-finite
  (let [good {:scale 1 :x 0 :y 0}]
    (t/is (true? (sm/validate core/schema:view good)))
    (doseq [bad [(assoc good :scale js/Infinity)
                 (assoc good :x js/NaN)
                 (assoc good :y js/Infinity)
                 (assoc good :viewport {:width js/NaN})]]
      (t/is (false? (sm/validate core/schema:view bad)) (pr-str bad)))))

(t/deftest transit-rejects-unencodable-values
  (t/is (false? (core/transit-round-trips? {:v (fn [] 1)})))
  (t/is (false? (core/transit-round-trips? {:point (->Point 1 2)}))))

(t/deftest transit-wire-preserves-json-lossy-shapes
  (doseq [value [{:foo 1 "foo" 2}
                 {:a/b 1 :c/b 2}
                 {:id :rects/load :scene :rects}
                 {:tags [:fresh :rects/load]}
                 {:v js/Infinity}
                 {:v js/-Infinity}]]
    (t/is (= value (-> value transit/encode-str transit/decode-str))
          (pr-str value))))

(t/deftest transit-wire-preserves-non-finite-numbers
  (let [value   {:nan js/NaN :positive js/Infinity :negative js/-Infinity}
        decoded (-> value transit/encode-str transit/decode-str)
        case-desc (assoc (first (rect-cases)) :operation value)]
    (t/is (number? (:nan decoded)))
    (t/is (js/Number.isNaN (:nan decoded)))
    (t/is (= js/Infinity (:positive decoded)))
    (t/is (= js/-Infinity (:negative decoded)))
    (t/is (core/transit-round-trips? value))
    (t/is (nil? (failure-data #(core/check-collected-case case-desc))))))

(t/deftest unencodable-collected-values-are-rejected
  (let [good (first (rect-cases))]
    (doseq [bad [(assoc good :operation {:run (fn [] 1)})
                 (assoc good :operation {:point (->Point 1 2)})]]
      (t/is (= ::core/non-serializable-case
               (:type (failure-data #(core/check-collected-case bad))))
            (pr-str bad)))))

(t/deftest duplicate-scene-id-from-another-namespace-is-rejected
  (t/is (= ::core/duplicate-scene
           (:type (failure-data
                   #(core/register-scene! {:id :rects
                                           :ns "another-namespace"
                                           :version 1
                                           :description "x"
                                           :params-schema [:map]
                                           :build (fn [params] params)}))))))

(t/deftest same-namespace-registration-replaces
  (try
    (core/register-scene! {:id :contracts-replaced
                           :ns "contracts-test"
                           :version 1
                           :description "first"
                           :params-schema [:map]
                           :build (fn [params] params)})
    (core/register-scene! {:id :contracts-replaced
                           :ns "contracts-test"
                           :version 2
                           :description "second"
                           :params-schema [:map]
                           :build (fn [params] params)})
    (t/is (= "second" (:description (core/registered-scene :contracts-replaced))))
    (finally
      (core/unregister-scene! :contracts-replaced))))

(t/deftest duplicate-case-id-from-another-namespace-is-rejected
  (try
    (core/register-scene! {:id :contracts-dup
                           :ns "contracts-test"
                           :version 1
                           :description "x"
                           :params-schema [:map]
                           :build (fn [params] params)})
    (core/register-case! {:id :contracts-dup/case
                          :scene :contracts-dup
                          :ns "contracts-test"
                          :params {}
                          :view {:scale 1 :x 0 :y 0}
                          :context :fresh})
    (t/is (= ::core/duplicate-case
             (:type (failure-data
                     #(core/register-case! {:id :contracts-dup/case
                                            :scene :contracts-dup
                                            :ns "another-namespace"
                                            :params {}
                                            :view {:scale 1 :x 0 :y 0}
                                            :context :fresh})))))
    (finally
      (core/unregister-case! :contracts-dup/case)
      (core/unregister-scene! :contracts-dup))))

(t/deftest unknown-scene-reference-is-rejected
  (try
    (core/register-case! {:id :contracts-missing/case
                          :scene :contracts-missing
                          :ns "contracts-test"
                          :params {}
                          :view {:scale 1 :x 0 :y 0}
                          :context :fresh})
    (t/is (= ::cases/unknown-scene
             (:type (failure-data
                     #(cases/collect-cases {:master-seed 42
                                            :filter "contracts-missing/"})))))
    (finally
      (core/unregister-case! :contracts-missing/case))))

(t/deftest case-id-must-be-namespaced-by-its-scene
  (try
    (core/register-scene! {:id :contracts-other
                           :ns "contracts-test"
                           :version 1
                           :description "x"
                           :params-schema [:map]
                           :build (fn [params] params)})
    (core/register-case! {:id :contracts-other/case
                          :scene :rects
                          :ns "contracts-test"
                          :params {}
                          :view {:scale 1 :x 0 :y 0}
                          :context :fresh})
    (t/is (= ::cases/invalid-case
             (:type (failure-data
                     #(cases/collect-cases {:master-seed 42
                                            :filter "contracts-other/"})))))
    (finally
      (core/unregister-case! :contracts-other/case)
      (core/unregister-scene! :contracts-other))))

(t/deftest scene-without-params-schema-is-rejected
  (try
    (core/register-scene! {:id :contracts-noschema
                           :ns "contracts-test"
                           :version 1
                           :description "x"
                           :build (fn [params] params)})
    (t/is (= ::core/invalid-scene
             (:type (failure-data #(cases/collect-cases {:master-seed 42})))))
    (finally
      (core/unregister-scene! :contracts-noschema))))

(t/deftest malformed-params-schema-is-rejected
  (try
    (core/register-scene! {:id :contracts-badschema
                           :ns "contracts-test"
                           :version 1
                           :description "x"
                           :params-schema [:map [:count]]
                           :build (fn [params] params)})
    (let [data (failure-data #(cases/collect-cases {:master-seed 42}))]
      (t/is (= ::core/invalid-scene (:type data)))
      (t/is (= :contracts-badschema (:id data))))
    (finally
      (core/unregister-scene! :contracts-badschema))))

(t/deftest qualified-scene-id-is-rejected
  (try
    (core/register-scene! {:id :contracts-ns/scene
                           :ns "contracts-test"
                           :version 1
                           :description "x"
                           :params-schema [:map]
                           :build (fn [params] params)})
    (t/is (= ::core/invalid-scene
             (:type (failure-data #(cases/collect-cases {:master-seed 42})))))
    (finally
      (core/unregister-scene! :contracts-ns/scene))))

(t/deftest invalid-declarations-fail-outside-the-filter
  (with-synthetic-scene :contracts-global {:count 1 :bogus 2}
    (fn []
      (t/is (= ::cases/invalid-case
               (:type (failure-data #(cases/collect-cases {:master-seed 42
                                                           :filter "rects/"}))))))))

(t/deftest incomplete-case-declaration-is-rejected
  (try
    (core/register-scene! {:id :contracts-incomplete
                           :ns "contracts-test"
                           :version 1
                           :description "x"
                           :params-schema [:map]
                           :build (fn [params] params)})
    (core/register-case! {:id :contracts-incomplete/case
                          :scene :contracts-incomplete
                          :ns "contracts-test"
                          :params {}
                          :context :fresh})
    (t/is (= ::cases/invalid-case
             (:type (failure-data #(cases/collect-cases {:master-seed 42
                                                         :filter "contracts-incomplete/"})))))
    (finally
      (core/unregister-case! :contracts-incomplete/case)
      (core/unregister-scene! :contracts-incomplete))))

(t/deftest records-do-not-survive-the-wire
  (let [point (->Point 1 2)]
    (t/is (false? (core/transit-round-trips? point)))
    (t/is (false? (core/transit-round-trips? {:point point})))))

(t/deftest case-replacement-preserves-order-and-unregister-removes
  (try
    (core/register-scene! {:id :contracts-replacecase
                           :ns "contracts-test"
                           :version 1
                           :description "x"
                           :params-schema [:map]
                           :build (fn [params] params)})
    (core/register-case! {:id :contracts-replacecase/case
                          :scene :contracts-replacecase
                          :ns "contracts-test"
                          :params {}
                          :view {:scale 1 :x 0 :y 0}
                          :context :fresh})
    (core/register-case! {:id :contracts-replacecase/case
                          :scene :contracts-replacecase
                          :ns "contracts-test"
                          :params {}
                          :view {:scale 2 :x 0 :y 0}
                          :context :fresh})
    (t/is (= 1 (count (filter #(= :contracts-replacecase/case (:id %)) (core/cases)))))
    (t/is (= {:scale 2 :x 0 :y 0} (:view (core/registered-case :contracts-replacecase/case))))
    (core/unregister-case! :contracts-replacecase/case)
    (t/is (nil? (core/registered-case :contracts-replacecase/case)))
    (finally
      (core/unregister-case! :contracts-replacecase/case)
      (core/unregister-scene! :contracts-replacecase))))

(t/deftest invalid-master-seed-is-rejected
  (doseq [seed [-1 4294967296 "42"]]
    (t/is (= ::cases/invalid-master-seed
             (:type (failure-data #(cases/collect-cases {:master-seed seed}))))
          (str seed))))

(t/deftest filter-matches-case-ids
  (t/is (= [:rects/pan]
           (mapv :id (cases/collect-cases {:master-seed 42 :filter "rects/pan"}))))
  (t/is (= 3 (count (cases/collect-cases {:master-seed 42 :filter "rects/"})))))

(t/deftest empty-selection-is-rejected
  (t/is (= ::cases/no-cases
           (:type (failure-data #(cases/collect-cases {:master-seed 42
                                                       :filter "missing"}))))))

(t/deftest defcase-registers-function-without-sending-it
  (reset! body-calls [])
  (let [collected (cases/collect-cases {:master-seed 42 :filter "contracts-scene/"})]
    (t/is (= [:contracts-scene/case] (mapv :id collected)))
    (t/is (not (contains? (first collected) :run!)))
    (t/is (core/transit-round-trips? (first collected))))
  (t/is (empty? @body-calls))
  (let [entry (core/registered-case :contracts-scene/case)]
    (t/is (= "frontend-tests.benches.contracts-test" (:ns entry)))
    (t/is (fn? (:run! entry)))
    (t/is (= :runtime ((:run! entry) :runtime)))
    (t/is (= [:touched] @body-calls))))

(t/deftest defcase-rejects-run-function-in-options
  (t/is (= :benches.render-wasm.scenes.core/invalid-run-function
           (:type (failure-data
                   #(core/defcase :contracts-scene/unchecked :contracts-scene
                      {:run! (fn [x y] [x y])})))))
  (t/is (nil? (core/registered-case :contracts-scene/unchecked))))
