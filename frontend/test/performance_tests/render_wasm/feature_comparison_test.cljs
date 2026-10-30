;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS SUBSIDIARY SL

(ns performance-tests.render-wasm.feature-comparison-test
  (:require
   ["fs" :as fs]
   ["node:timers/promises" :as timers]
   ["os" :as os]
   ["path" :as path]
   [benches.render-wasm.cases :as cases]
   [benches.render-wasm.declarations :as decl]
   [benches.render-wasm.errors :as errors]
   [benches.render-wasm.feature-plan :as feature-plan]
   [benches.render-wasm.node.build :as build]
   [benches.render-wasm.node.feature-comparison :as feature-comparison]
   [benches.render-wasm.node.feature-plan :as node-plan]
   [benches.render-wasm.node.files :as files]
   [benches.render-wasm.node.log :as log]
   [benches.render-wasm.node.process :as process]
   [benches.render-wasm.result :as result]
   [cljs.test :as t :include-macros true]
   [clojure.string :as str]
   [frontend-tests.helpers.mock :as mock]))

(def ^:private temporary-root (atom nil))

(t/use-fixtures :each
  {:before #(reset! temporary-root (fs/mkdtempSync (path/join (os/tmpdir) "penpot-feature-plan-")))
   :after (fn []
            (log/configure {})
            (fs/rmSync @temporary-root #js {:recursive true :force true}))})

(defn- frontend-root [] (path/join @temporary-root "frontend"))
(defn- renderer-root [] (path/join @temporary-root "render-wasm"))

(defn- write-cargo-fixture
  []
  (fs/mkdirSync (frontend-root))
  (fs/mkdirSync (renderer-root))
  (fs/writeFileSync (path/join (renderer-root) "Cargo.toml")
                    (str "[package]\nname = 'feature-plan-fixture'\nversion = '0.1.0'\nedition = '2021'\n"
                         "[lib]\npath = 'lib.rs'\n[features]\ndefault = []\na = []\nb = ['a']\n"))
  (fs/writeFileSync (path/join (renderer-root) "lib.rs") "")
  (fs/writeFileSync (path/join (renderer-root) "Cargo.lock")
                    "version = 4\n[[package]]\nname = 'feature-plan-fixture'\nversion = '0.1.0'\n"))

(defn- planning-options
  []
  {:features ["b" "a"] :filter "rects/load" :seed 42 :dry-run true
   :warmups 0 :repetitions 1 :timeout-ms 2000
   :build-timeout-ms 10000 :startup-timeout-ms 3000})

(defn- metadata-output
  [definitions]
  {:stdout (js/JSON.stringify
            (clj->js {"packages" [{"manifest_path" (path/join (frontend-root) "Cargo.toml")
                                   "features" {"default" ["wrong-package"]}}
                                  {"manifest_path" (path/join (renderer-root) "Cargo.toml")
                                   "features" definitions}]}))})

(defn- error-message
  [promise]
  (.then promise (fn [_] nil) (fn [cause] (ex-message cause))))

(t/deftest one-feature-has-an-explicit-off-and-on-selection
  (t/is (= {:features ["a"]
            :configurations [{:slug "0" :selection {:default-features? false :features []}}
                             {:slug "1" :selection {:default-features? false :features ["a"]}}]
            :edges [{:from "0" :to "1" :feature "a"}]}
           (feature-plan/plan ["a"] {:filter "rects/load"}))))

(t/deftest two-feature-order-and-all-edges-are-msb-first
  (t/is (= {:features ["a" "b"]
            :configurations [{:slug "00" :selection {:default-features? false :features []}}
                             {:slug "01" :selection {:default-features? false :features ["b"]}}
                             {:slug "11" :selection {:default-features? false :features ["a" "b"]}}
                             {:slug "10" :selection {:default-features? false :features ["a"]}}]
            :edges [{:from "00" :to "10" :feature "a"}
                    {:from "00" :to "01" :feature "b"}
                    {:from "01" :to "11" :feature "a"}
                    {:from "10" :to "11" :feature "b"}]}
           (feature-plan/plan ["b" "a" "a"] {:filter "rects/load"}))))

(t/deftest three-feature-order-and-complete-graph
  (let [{:keys [configurations edges]} (feature-plan/plan ["c" "a" "b"] {:filter "rects/load"})
        by-slug (into {} (map (juxt :slug #(set (get-in % [:selection :features])))) configurations)]
    (t/is (= ["000" "001" "011" "010" "110" "111" "101" "100"] (mapv :slug configurations)))
    (t/is (= 12 (count (set edges))))
    (doseq [{:keys [from to feature]} edges]
      (t/is (not (contains? (by-slug from) feature)))
      (t/is (= (conj (by-slug from) feature) (by-slug to))))))

(t/deftest upper-limit-still-emits-every-edge-once
  (let [{:keys [configurations edges]}
        (feature-plan/plan ["a" "b" "c" "d" "e" "f"] {:max-features 6 :confirm true})]
    (t/is (= 64 (count (set configurations))))
    (t/is (= 192 (count (set edges))))
    (t/is (every? false? (map #(get-in % [:selection :default-features?]) configurations)))))

(t/deftest invalid-inputs-fail-before-planning
  (doseq [features [nil '("a") "a" [nil] [1] [""] ["a b"] ["a/b"] ["a.b"] [" a"]]]
    (t/is (thrown? js/Error (feature-plan/plan features {:dry-run true})) (pr-str features)))
  (t/is (thrown-with-msg? js/Error #"at least one" (feature-plan/plan [] {:dry-run true})))
  (doseq [limit [nil 0 7 -1 1.5 "3" js/NaN js/Infinity]]
    (t/is (thrown-with-msg? js/Error #"integer from 1 to 6"
                            (feature-plan/plan ["a"] {:max-features limit :dry-run true}))))
  (doseq [opts [{:confirm nil} {:confirm 1} {:dry-run nil} {:dry-run "true"} {:filter 1}]]
    (t/is (thrown-with-msg? js/Error #"invalid feature planning options"
                            (feature-plan/plan ["a"] opts))))
  (t/is (thrown-with-msg? js/Error #"exceed max-features"
                          (feature-plan/plan ["a" "b" "c" "d"] {:dry-run true}))))

(t/deftest confirmation-applies-to-execution-only
  (doseq [filter [nil "" "  "]]
    (t/is (thrown-with-msg? js/Error #"requires --confirm" (feature-plan/plan ["a"] {:filter filter})))
    (t/is (= 2 (count (:configurations (feature-plan/plan ["a"] {:filter filter :dry-run true}))))))
  (t/is (= 8 (count (:configurations (feature-plan/plan ["a" "b" "c"] {:filter "rects"})))))
  (t/is (thrown-with-msg? js/Error #"requires --confirm"
                          (feature-plan/plan ["a" "b" "c" "d"] {:max-features 4 :filter "rects"})))
  (doseq [opts [{:confirm true} {:dry-run true}]]
    (t/is (= 16 (count (:configurations
                        (feature-plan/plan ["a" "b" "c" "d"] (assoc opts :max-features 4))))))))

(t/deftest manifest-validation-leaves-activation-to-cargo
  (t/is (= ["a" "b"] (feature-plan/check-manifest ["b" "a"]
                                                  {"default" [] "a" [] "b" ["a" "dep:library"]})))
  (t/is (= ["a"] (feature-plan/check-manifest ["a"] {"a" []})))
  (t/is (thrown-with-msg? js/Error #"unknown requested"
                          (feature-plan/check-manifest ["missing"] {"default" [] "a" []})))
  (t/is (thrown-with-msg? js/Error #"empty declared Cargo defaults"
                          (feature-plan/check-manifest ["a"] {"default" ["a"] "a" []})))
  (doseq [definitions [nil [] {"a" "b"} {:a []} {"a" [1]}]]
    (t/is (thrown-with-msg? js/Error #"invalid Cargo feature definitions"
                            (feature-plan/check-manifest ["a"] definitions))))
  (doseq [feature ["stats" "profile" "profile-macros" "profile-raf"]]
    (t/is (thrown-with-msg? js/Error #"diagnostic Cargo features"
                            (feature-plan/plan [feature] {:dry-run true})))
    (t/is (thrown-with-msg? js/Error #"diagnostic Cargo features"
                            (feature-plan/check-manifest [feature] {"default" [] feature []}))))
  ;; A declared alias is allowed here. Actual Cargo-reported diagnostics are
  ;; rejected later by build validation, not by a second activation resolver.
  (t/is (= ["alias"] (feature-plan/check-manifest ["alias"] {"default" [] "alias" ["stats"]}))))

(t/deftest ^:async real-offline-metadata-and-collection-create-no-output
  (write-cargo-fixture)
  (let [output (path/join @temporary-root "new" "comparison")
        opts   (assoc (planning-options) :output-dir output)
        before (vec (fs/readdirSync (renderer-root)))
        ready  (await (node-plan/prepare (frontend-root) opts nil))]
    (t/is (= ["a" "b"] (get-in ready [:plan :features])))
    (t/is (= (cases/collect-cases {:master-seed 42 :filter "rects/load"}) (:cases ready)))
    (t/is (= (assoc opts :features ["a" "b"] :default-features false :max-features 3 :confirm false
                    :allow-build-differences false :allow-scene-differences false :allow-all-differences false)
             (:options ready)))
    (t/is (= output (:output-dir ready)))
    (t/is (not (fs/existsSync (path/join @temporary-root "new"))))
    (t/is (= before (vec (fs/readdirSync (renderer-root)))))
    (t/is (empty? (fs/readdirSync (frontend-root))))
    (let [text (node-plan/format-plan ready)]
      (doseq [part ["a, b" "00" "01" "11" "10" "00 -> 10: a" "Cases: 1"
                    "0 warmups, 1 measured" "attempt 2000, build 10000, startup 3000"]]
        (t/is (str/includes? text part))))))

(t/deftest ^:async metadata-selects-the-package-by-manifest-not-array-position
  (write-cargo-fixture)
  (fs/writeFileSync (path/join (frontend-root) "Cargo.toml") "decoy")
  (await
   (mock/with-mocks*
     {process/run (fn [request]
                    (t/is (= "cargo" (:command request)))
                    (t/is (= ["metadata" "--offline" "--locked" "--no-deps" "--format-version" "1"]
                             (:args request)))
                    (t/is (= (renderer-root) (:cwd request)))
                    (t/is (not (contains? request :env)))
                    (js/Promise.resolve (metadata-output {"default" [] "a" [] "b" ["a"]})))}
     (let [ready (await (node-plan/prepare (frontend-root) (planning-options) nil))]
       (t/is (= ["a" "b"] (get-in ready [:plan :features])))))))

(t/deftest ^:async collected-custom-case-data-and-order-survive-planning
  (write-cargo-fixture)
  (decl/register-scene! {:id :feature-plan-fixture :ns "feature-comparison-test"
                         :description "Planning data fixture"
                         :params-schema [:map [:count :int]]
                         :build (fn [_] (throw (js/Error. "planning must not construct a scene")))})
  (doseq [id [:feature-plan-fixture/second :feature-plan-fixture/first]]
    (decl/register-case! {:id id :scene :feature-plan-fixture :ns "feature-comparison-test"
                          :params {:count 17} :view {:scale 2 :x 10 :y -5} :context :reuse
                          :completion :await :batch-size 7
                          :operation {:id :custom/work :args {:amount 3}}
                          :preparation {:reset :outside-timing :data [1 2]}
                          :run! (fn [_] (throw (js/Error. "planning must not run an operation")))}))
  (try
    (let [ready (await (node-plan/prepare (frontend-root)
                                          (assoc (planning-options) :filter "feature-plan-fixture/") nil))
          data  (:cases ready)]
      (t/is (= [:feature-plan-fixture/second :feature-plan-fixture/first] (mapv :id data)))
      (t/is (= (cases/collect-cases {:master-seed 42 :filter "feature-plan-fixture/"}) data))
      (t/is (= {:params {:count 17} :view {:scale 2 :x 10 :y -5} :context :reuse
                :completion :await :batch-size 7 :operation {:id :custom/work :args {:amount 3}}
                :preparation {:reset :outside-timing :data [1 2]}}
               (select-keys (first data) [:params :view :context :completion :batch-size :operation :preparation]))))
    (finally
      (decl/unregister-case! :feature-plan-fixture/second)
      (decl/unregister-case! :feature-plan-fixture/first)
      (decl/unregister-scene! :feature-plan-fixture))))

(t/deftest ^:async planning-propagates-manifest-and-collector-errors
  (write-cargo-fixture)
  (fs/writeFileSync (path/join (frontend-root) "Cargo.toml") "decoy")
  (doseq [[definitions message] [[{"default" [] "a" []} "unknown requested Cargo features"]
                                 [{"default" ["a"] "a" [] "b" []} "ab requires empty declared Cargo defaults"]]]
    (await (mock/with-mocks*
             {process/run (fn [_] (js/Promise.resolve (metadata-output definitions)))}
             (t/is (= message (await (error-message
                                      (node-plan/prepare (frontend-root) (planning-options) nil))))))))
  (t/is (= "no benchmark cases match the selection"
           (await (error-message (node-plan/prepare (frontend-root)
                                                    (assoc (planning-options) :filter "missing-case") nil))))))

(t/deftest ^:async invalid-input-and-existing-output-fail-before-cargo
  (write-cargo-fixture)
  (await
   (mock/with-mocks*
     {process/run (fn [_] (throw (js/Error. "unexpected Cargo invocation")))}
     (t/is (= "output directory already exists"
              (await (error-message (node-plan/prepare (frontend-root)
                                                       (assoc (planning-options) :output-dir (frontend-root)) nil)))))
     (t/is (= "ab requires at least one requested feature"
              (await (error-message (node-plan/prepare (frontend-root)
                                                       (assoc (planning-options) :features []) nil))))))))

(t/deftest ^:async cancellation-reaches-the-process-boundary
  (write-cargo-fixture)
  (let [controller (js/AbortController.)]
    (.abort controller "SIGINT")
    (t/is (str/includes? (await (error-message
                                 (node-plan/prepare (frontend-root) (planning-options) (.-signal controller))))
                         "cancelled"))))

(defn- planned-record
  []
  (let [plan (feature-plan/plan ["a" "b"] {:dry-run true})]
    {:type :feature-comparison :comparison-id "ab-id"
     :started-at "start" :updated-at "start" :status :planned
     :options (planning-options)
     :permissions {:allow-build-differences false :allow-scene-differences true :allow-all-differences false}
     :cases (cases/collect-cases {:master-seed 42 :filter "rects/load"})
     :features (:features plan)
     :configurations (mapv #(assoc % :status :planned) (:configurations plan))
     :edges (mapv #(assoc % :status :planned) (:edges plan))
     :warnings ["Timing values do not determine success."]}))

(defn- completed-configuration
  [configuration]
  (assoc configuration
         :status :completed
         :effective-features ["transitive"]
         :build {:id "build-id" :manifest-path "/build.transit" :log-path "/build.log"}
         :run {:run-id "run-id" :record-path "/run.transit" :log-path "/run.log"
               :exit-code 0}))

(defn- completed-record
  []
  (let [record (planned-record)]
    (assoc record
           :status :completed :updated-at "end" :ended-at "end"
           :configurations (mapv completed-configuration (:configurations record))
           :edges (mapv #(assoc % :status :completed :path "/comparison.transit")
                        (:edges record)))))

(defn- terminal-record
  [status]
  (let [record (planned-record)]
    (assoc record
           :status status :updated-at "end" :ended-at "end"
           :configurations
           (mapv (fn [[index configuration]]
                   (if (zero? index)
                     (assoc (completed-configuration configuration)
                            :status :failed :failure {:phase :run}
                            :reason "SIGINT")
                     (assoc configuration :status :unattempted
                            :reason "stopped after failure")))
                 (map-indexed vector (:configurations record)))
           :edges
           (mapv (fn [[index edge]]
                   (if (zero? index)
                     (assoc edge :status :failed :path "/comparison.transit"
                            :failure {:message "partial"})
                     (assoc edge :status :unattempted
                            :reason "stopped after failure")))
                 (map-indexed vector (:edges record))))))

(defn- running-record
  []
  (-> (planned-record)
      (assoc :status :running)
      (assoc-in [:configurations 0 :status] :building)
      (assoc-in [:configurations 0 :build]
                {:id "build-id" :manifest-path "/build.transit" :log-path "/build.log"})))

(t/deftest feature-comparison-record-round-trips-plan-and-partial-evidence
  (let [record (terminal-record :interrupted)]
    (t/is (= (planned-record) (result/decode (result/encode (planned-record)))))
    (t/is (= record (result/decode (result/encode record))))))

(t/deftest feature-comparison-progress-must-match-saved-evidence
  (doseq [record [(planned-record) (running-record) (completed-record)
                  (terminal-record :failed) (terminal-record :interrupted)]]
    (t/is (= record (result/check-record record))))
  (let [completed (completed-record)
        failed    (terminal-record :failed)
        running   (running-record)]
    (doseq [[record message]
            [[(assoc (planned-record) :status :completed)
              #"terminal feature comparison needs an end time"]
             [(assoc-in completed [:configurations 0 :status] :planned)
              #"still has active configurations"]
             [(assoc-in completed [:edges 0 :status] :planned) #"still has active edges"]
             [(update completed :configurations
                      (fn [configurations]
                        (mapv #(dissoc % :build) configurations)))
              #"configuration needs a build"]
             [(update completed :configurations
                      (fn [configurations]
                        (mapv #(dissoc % :effective-features) configurations)))
              #"configuration needs effective features"]
             [(update completed :configurations
                      (fn [configurations]
                        (mapv #(dissoc % :run) configurations)))
              #"configuration needs a run"]
             [(update completed :configurations
                      (fn [configurations]
                        (mapv #(update % :run dissoc :exit-code) configurations)))
              #"run exit code"]
             [(assoc-in completed [:configurations 0 :run :exit-code] 1)
              #"successful run"]
             [(update-in completed [:edges 0] dissoc :path)
              #"edge needs a report path"]
             [(update-in failed [:configurations 0] dissoc :failure :reason)
              #"configuration needs a failure or reason"]
             [(update-in failed [:configurations 1] dissoc :reason)
              #"configuration needs a reason"]
             [(update-in failed [:edges 0] dissoc :failure :reason)
              #"edge needs a failure"]
             [(update-in failed [:edges 1] dissoc :reason)
              #"edge needs a reason"]
             [(assoc running :ended-at "end") #"unfinished feature comparison has an end time"]
             [(assoc-in (planned-record) [:configurations 0 :status] :building)
              #"started configurations"]
             [(assoc-in (planned-record) [:edges 0 :status] :completed)
              #"started edges"]
             [(assoc (planned-record) :features ["b" "a"])
              #"features disagree with normalized selection"]
             [(update (planned-record) :configurations
                      #(assoc % 1 (first %)))
              #"configurations disagree with planned features"]
             [(update (planned-record) :configurations pop)
              #"configurations disagree with planned features"]
             [(assoc-in (planned-record) [:edges 0 :to] "11")
              #"edges disagree with planned configurations"]
             [(update (planned-record) :cases conj (first (:cases (planned-record))))
              #"duplicate feature comparison case"]]]
      (t/is (thrown-with-msg? js/Error message (result/check-record record))
            (pr-str message)))))

(t/deftest feature-comparison-current-state-shapes-are-checked
  (doseq [[location value] [[[:comparison-id] 1] [[:updated-at] nil] [[:status] :ready]
                            [[:ended-at] nil] [[:features] [:a]] [[:cases] [{}]]
                            [[:permissions :allow-scene-differences] nil]
                            [[:configurations 0 :status] :interrupted]
                            [[:configurations 0 :selection :features] "a"]
                            [[:configurations 0 :selection :default-features?] nil]
                            [[:configurations 0 :build] {:id "id"}]
                            [[:configurations 0 :run] {:run-id "id" :record-path "p" :log-path "l" :exit-code "0"}]
                            [[:configurations 0 :failure] "error"]
                            [[:configurations 0 :effective-features] [:a]]
                            [[:edges 0 :status] :running] [[:edges 0 :path] 1] [[:warnings] [nil]]]]
    (t/is (thrown-with-msg? js/Error #"invalid schema:feature-comparison"
                            (result/check-record (assoc-in (planned-record) location value)))
          (pr-str location))))

(defn- create-frontend-root
  []
  (fs/mkdirSync (frontend-root) #js {:recursive true}))

(defn- comparison-output-dir
  []
  (path/join @temporary-root "output" "comparison"))

(defn- fixture-case
  []
  {:id :fixture/comparison-case})

(defn- comparison-prepared
  ([features] (comparison-prepared features {}))
  ([features overrides]
   (let [plan   (feature-plan/plan features {:dry-run true})
         output (or (:output-dir overrides) (comparison-output-dir))
         opts   (merge {:default-features false
                        :filter "fixture/"
                        :seed 42
                        :warmups 0
                        :repetitions 1
                        :timeout-ms 1000
                        :build-timeout-ms 5000
                        :startup-timeout-ms 1000
                        :allow-build-differences false
                        :allow-scene-differences false
                        :allow-all-differences false}
                       overrides
                       {:features (:features plan)
                        :output-dir output})]
     {:plan plan
      :cases [(fixture-case)]
      :output-dir output
      :options opts})))

(defn- fake-build
  [selection extra-features]
  {:id (str (random-uuid))
   :manifest-path (path/join @temporary-root "builds" "build.transit")
   :log-path (path/join @temporary-root "builds" "build.log")
   :manifest {:functional {:mode :release
                           :features (vec (concat (:features selection) extra-features))}
              :provenance {:feature-selection selection}}})

(defn- build-service
  [{:keys [extra-features fail-on inspect build-fn]
    :or {extra-features (constantly [])
         fail-on (constantly false)
         build-fn fake-build}}]
  (fn [request]
    (when inspect (inspect request))
    (let [selection (get request :selection)
          build     (build-fn selection (extra-features selection))]
      (when-let [on-created (:on-created request)]
        (on-created build))
      (if (fail-on selection)
        (js/Promise.reject (js/Error. "fixture build failure"))
        (js/Promise.resolve build)))))

(defn- metric-entry
  [value]
  (result/metric {:value value
                  :unit :ms
                  :semantics :duration
                  :basis {:kind :attempt-total}
                  :zero-policy :positive}))

(defn- fingerprint
  [character]
  {:algorithm :sha-256
   :digest (apply str (repeat 64 character))})

(defn- preparation
  [case-id digest]
  {:preparation-id (str (random-uuid))
   :case-id case-id
   :fingerprint (fingerprint digest)
   :effective-graphics {:backend :fixture}
   :diagnostics {}})

(defn- fake-run-record
  [selected-build run-home cases options {:keys [kind digest metric-value]
                                          :or {kind :completed digest "a" metric-value 10}}]
  (let [run (result/create-run
             {:run-id (:run-id run-home)
              :started-at (.toISOString (js/Date.))
              :plan {:cases cases
                     :warmups 0
                     :repetitions 1
                     :timeout-policy {:attempt-ms (:timeout-ms options)}}
              :metadata {:git {:sha nil :dirty nil}
                         :build {:functional (get-in selected-build [:manifest :functional])
                                 :provenance (get-in selected-build [:manifest :provenance])}
                         :environment {}
                         :scored? true}})
        failed? (= kind :failed)
        run     (if (= kind :zero-attempts)
                  run
                  (reduce
                   (fn [run case]
                     (let [case-id (:id case)]
                       (if failed?
                         (result/record-attempt
                          run
                          {:case-id case-id
                           :preparation-id nil
                           :warmup? false
                           :outcome :failed
                           :metrics {}
                           :trace {}
                           :failure {:message "fixture failure"}})
                         (let [prep (preparation case-id digest)]
                           (-> run
                               (result/record-preparation prep)
                               (result/record-attempt
                                {:case-id case-id
                                 :preparation-id (:preparation-id prep)
                                 :warmup? false
                                 :outcome :completed
                                 :metrics {:fixture/total-ms (metric-entry metric-value)}
                                 :trace {}}))))))
                   run
                   cases))
        termination (case kind
                      :failed {:reason :failed :failure {:message "fixture failure"}}
                      :zero-attempts {:reason :incomplete}
                      :interrupted {:reason :interrupted :signal "SIGINT"}
                      {:reason :completed})]
    (result/finish-run run (.toISOString (js/Date.)) termination)))

(defn- execute-service
  [spec-or-fn]
  (fn [_ run-home selected-build cases options _signal]
    (let [spec      (if (fn? spec-or-fn) (spec-or-fn selected-build) spec-or-fn)
          run       (fake-run-record selected-build run-home cases options spec)
          exit-code (case (:kind spec)
                      :failed 1
                      :zero-attempts 1
                      :interrupted 130
                      0)]
      (files/atomic-write (:record-path run-home)
                          (result/encode (result/check-record run)))
      (js/Promise.resolve {:run run :home run-home :exit-code exit-code :errors []}))))

(defn- comparison-services
  [{:keys [prepare-build collect-catalog execute-run]}]
  {:prepare-build prepare-build
   :collect-catalog (or collect-catalog (fn [_ _] [(fixture-case)]))
   :execute-run execute-run})

(defn- saved-comparison
  [outcome]
  (result/decode (fs/readFileSync (get-in outcome [:home :record-path]) "utf8")))

(t/deftest ^:async invalid-build-selection-or-features-stops-later-configurations
  (create-frontend-root)
  (doseq [[field value] [[[:provenance :feature-selection] {:default-features? false :features ["other"]}]
                         [[:functional :features] #{"a"}]
                         [[:functional :features] [:invalid]]]]
    (let [prepared (comparison-prepared ["a"] {:output-dir (path/join @temporary-root (str (random-uuid)))})
          outcome  (await (feature-comparison/execute
                           (frontend-root) prepared nil
                           {:services (comparison-services
                                       {:prepare-build (build-service
                                                        {:build-fn (fn [selection extra]
                                                                     (assoc-in (fake-build selection extra)
                                                                               (into [:manifest] field) value))})
                                        :execute-run (execute-service {})})}))
          record   (saved-comparison outcome)]
      (t/is (= 1 (:exit-code outcome)))
      (t/is (= :failed (:status record)))
      (t/is (= [:failed :unattempted] (mapv :status (:configurations record))))
      (t/is (= "build" (get-in record [:configurations 0 :failure :phase])))
      (t/is (str/includes? (get-in record [:configurations 0 :failure :message])
                           "disagrees with the planned selection")))))

(t/deftest ^:async operational-failures-save-the-stage-and-stop-later-configurations
  (create-frontend-root)
  (doseq [[boundary phase message] [[:catalog "catalog" "catalog rejected"]
                                    [:run-home "run-home" "already exists"]
                                    [:run "run" "run rejected"]
                                    [:missing-run "run" "run executor returned no record"]]]
    (let [prepared (comparison-prepared ["a"] {:output-dir (path/join @temporary-root (str (random-uuid)))})
          base     (comparison-services {:prepare-build (build-service {}) :execute-run (execute-service {})})
          services (case boundary
                     :catalog (assoc base :collect-catalog (fn [_ _] (throw (js/Error. "catalog rejected"))))
                     :run-home (assoc base :prepare-build
                                      (build-service {:inspect (fn [_]
                                                                 (fs/mkdirSync (path/join (:output-dir prepared) "configs" "0")
                                                                               #js {:recursive true}))}))
                     :run (assoc base :execute-run (fn [& _] (js/Promise.reject (js/Error. "run rejected"))))
                     :missing-run (assoc base :execute-run (fn [& _] (js/Promise.resolve {:exit-code 1}))))
          outcome  (await (feature-comparison/execute (frontend-root) prepared nil {:services services}))
          record   (saved-comparison outcome)]
      (t/is (= 1 (:exit-code outcome)))
      (t/is (= :failed (:status record)))
      (t/is (= [:failed :unattempted] (mapv :status (:configurations record))))
      (t/is (= phase (get-in record [:configurations 0 :failure :phase])))
      (t/is (str/includes? (get-in record [:configurations 0 :failure :message]) message)))))

(t/deftest ^:async run-failure-saves-partial-measurements-and-nested-causes
  (create-frontend-root)
  (let [partial  {:slices [{:duration-ms 3}]}
        cause    (ex-info "run rejected" {:partial partial}
                          (ex-info "render failed" {:fn "_render" :code 17}))
        prepared (comparison-prepared ["a"] {})
        outcome  (await (feature-comparison/execute
                         (frontend-root) prepared nil
                         {:services (comparison-services
                                     {:prepare-build (build-service {})
                                      :execute-run (fn [& _] (js/Promise.reject cause))})}))
        failure  (get-in (saved-comparison outcome) [:configurations 0 :failure])]
    (t/is (= {:phase "run"
              :message "run rejected"
              :partial partial}
             (select-keys failure [:phase :message :partial])))
    (t/is (some #(= {:message "render failed" :fn "_render" :code 17} %)
                (:cause failure)))))

(t/deftest ^:async unavailable-or-mismatched-run-evidence-stops-later-configurations
  (create-frontend-root)
  (doseq [[kind message] [[:missing "run record is missing"]
                          [:mismatch "saved run identity disagrees with execution result"]]]
    (let [prepared (comparison-prepared ["a"] {:output-dir (path/join @temporary-root (str (random-uuid)))})
          execute  (execute-service {})
          outcome  (await (feature-comparison/execute
                           (frontend-root) prepared nil
                           {:services (comparison-services
                                       {:prepare-build (build-service {})
                                        :execute-run
                                        (^:async fn [root home build cases options signal]
                                          (let [response (await (execute root home build cases options signal))]
                                            (if (= kind :missing)
                                              (fs/unlinkSync (:record-path home))
                                              (files/atomic-write (:record-path home)
                                                                  (result/encode (assoc (:run response) :run-id (str (random-uuid))))))
                                            response))})}))
          record   (saved-comparison outcome)]
      (t/is (= 1 (:exit-code outcome)))
      (t/is (= [:failed :unattempted] (mapv :status (:configurations record))))
      (t/is (= "run-evidence" (get-in record [:configurations 0 :failure :phase])))
      (t/is (str/includes? (get-in record [:configurations 0 :failure :message]) message)))))

(t/deftest ^:async returned-storage-failure-wins-over-readable-evidence-and-executor-errors
  (create-frontend-root)
  (doseq [[signal code] [[nil 1] ["SIGINT" 130] ["SIGTERM" 143]]]
    (let [controller (js/AbortController.)
          prepared   (comparison-prepared ["a"] {:output-dir (path/join @temporary-root (str (random-uuid)))})
          execute    (execute-service {})
          outcome    (await (feature-comparison/execute
                             (frontend-root) prepared (.-signal controller)
                             {:services (comparison-services
                                         {:prepare-build (build-service {})
                                          :execute-run
                                          (^:async fn [root home build cases options signal-object]
                                            (let [response (await (execute root home build cases options signal-object))]
                                              (when signal (.abort controller signal))
                                              (assoc response :exit-code 1
                                                     :errors [(js/Error. "executor failure")
                                                              (errors/fatal-error "recovered write failure" {} nil)])))})}))
          record     (saved-comparison outcome)
          run        (result/decode (fs/readFileSync (get-in record [:configurations 0 :run :record-path]) "utf8"))]
      (t/is (= code (:exit-code outcome)))
      (t/is (= (if signal :interrupted :failed) (:status record)))
      (t/is (= :completed (get-in run [:termination :reason])))
      (t/is (= [:failed :unattempted] (mapv :status (:configurations record))))
      (t/is (= "run-evidence" (get-in record [:configurations 0 :failure :phase])))
      (t/is (= "recovered write failure" (get-in record [:configurations 0 :failure :message])))
      (t/is (= "recovered write failure" (ex-message (first (:errors outcome))))))))

(t/deftest ^:async pre-aborted-comparison-preserves-all-work-as-unattempted
  (create-frontend-root)
  (doseq [[reason signal code] [["SIGINT" "SIGINT" 130]
                                ["SIGTERM" "SIGTERM" 143]
                                [:SIGTERM "SIGTERM" 143]
                                [(ex-info "stopped" {:signal "SIGTERM"}) "SIGTERM" 143]
                                [(js/Error. "stopped") "SIGINT" 130]]]
    (let [controller (js/AbortController.)
          _          (.abort controller reason)
          prepared   (comparison-prepared ["a" "b"] {:output-dir (path/join @temporary-root (str (random-uuid)))})
          outcome    (await (feature-comparison/execute
                             (frontend-root) prepared (.-signal controller)
                             {:services (comparison-services {:prepare-build (build-service {})
                                                              :execute-run (execute-service {})})}))
          record     (saved-comparison outcome)]
      (t/is (= code (:exit-code outcome)))
      (t/is (= :interrupted (:status record)))
      (t/is (every? #(= :unattempted (:status %)) (concat (:configurations record) (:edges record))))
      (t/is (every? #(= (str "interrupted by " signal) (:reason %))
                    (concat (:configurations record) (:edges record)))))))

(t/deftest ^:async interrupted-build-and-catalog-preserve-stage-failures
  (create-frontend-root)
  (doseq [boundary [:build :catalog]
          rejected [false true]
          [signal code] [["SIGINT" 130] ["SIGTERM" 143]]]
    (let [controller (js/AbortController.)
          prepared   (comparison-prepared ["a"] {:output-dir (path/join @temporary-root (str (random-uuid)))})
          build      (build-service {})
          stop       (fn []
                       (.abort controller signal)
                       (when rejected (throw (js/Error. "interrupted work"))))
          services   (comparison-services
                      {:prepare-build (if (= boundary :build)
                                        (^:async fn [request]
                                          (let [selected (await (build request))]
                                            (stop)
                                            selected))
                                        build)
                       :collect-catalog (fn [_ _]
                                          (when (= boundary :catalog) (stop))
                                          (:cases prepared))
                       :execute-run (execute-service {})})
          outcome    (await (feature-comparison/execute (frontend-root) prepared (.-signal controller)
                                                        {:services services}))
          record     (saved-comparison outcome)]
      (t/is (= code (:exit-code outcome)))
      (t/is (= :interrupted (:status record)))
      (t/is (= [:failed :unattempted] (mapv :status (:configurations record))))
      (t/is (= signal (get-in record [:configurations 0 :reason])))
      (t/is (= (if rejected [(name boundary)] [])
               (mapv :phase (keep :failure (:configurations record)))))
      (t/is (every? #(= :unattempted (:status %)) (:edges record))))))

(t/deftest ^:async edge-report-storage-failure-preserves-failed-and-unattempted-edges
  (create-frontend-root)
  (let [prepared (comparison-prepared ["a" "b"])
        outcome  (await (feature-comparison/execute
                         (frontend-root) prepared nil
                         {:services (comparison-services
                                     {:prepare-build (build-service
                                                      {:inspect (fn [_]
                                                                  (fs/mkdirSync
                                                                   (path/join (:output-dir prepared) "comparisons" "00__10.transit")
                                                                   #js {:recursive true}))})
                                      :execute-run (execute-service {})})}))
        record   (saved-comparison outcome)]
    (t/is (= 1 (:exit-code outcome)))
    (t/is (= :failed (:status record)))
    (t/is (every? #(= :completed (:status %)) (:configurations record)))
    (t/is (= [:failed :unattempted :unattempted :unattempted] (mapv :status (:edges record))))
    (t/is (= "comparison-storage" (get-in record [:edges 0 :failure :phase])))
    (t/is (= (get-in outcome [:home :log-path])
             (:log-path (ex-data (first (:errors outcome))))))))

(t/deftest ^:async edge-analysis-failures-allow-independent-comparisons-to-complete
  (create-frontend-root)
  (let [prepared (comparison-prepared ["a" "b"])
        removed  (path/join (:output-dir prepared) "configs" "00" "run.transit")
        outcome  (await (feature-comparison/execute
                         (frontend-root) prepared nil
                         {:services (comparison-services
                                     {:prepare-build (build-service
                                                      {:inspect (fn [{:keys [selection]}]
                                                                  (when (= ["b"] (:features selection))
                                                                    (fs/unlinkSync removed)))})
                                      :execute-run (execute-service {})})}))
        record   (saved-comparison outcome)]
    (t/is (= 1 (:exit-code outcome)))
    (t/is (every? #(= :completed (:status %)) (:configurations record)))
    (t/is (= [:failed :failed :completed :completed] (mapv :status (:edges record))))
    (t/is (= ["comparison" "comparison"] (mapv #(get-in % [:failure :phase]) (take 2 (:edges record)))))
    (doseq [edge (drop 2 (:edges record))]
      (t/is (= :comparison (:type (result/decode (fs/readFileSync (:path edge) "utf8"))))))))

(t/deftest ^:async post-run-persistence-failure-retains-the-running-configuration
  (create-frontend-root)
  (let [prepared (comparison-prepared ["a"])
        path     (path/join (:output-dir prepared) "feature-comparison.transit")
        prior    (str path ".saved")
        execute  (execute-service {})
        outcome  (await (feature-comparison/execute
                         (frontend-root) prepared nil
                         {:services (comparison-services
                                     {:prepare-build (build-service {})
                                      :execute-run
                                      (^:async fn [root home build cases options signal]
                                        (let [response (await (execute root home build cases options signal))]
                                          (fs/renameSync path prior)
                                          (fs/mkdirSync path)
                                          response))})}))
        record   (result/decode (fs/readFileSync prior "utf8"))
        run      (result/decode (fs/readFileSync (get-in record [:configurations 0 :run :record-path]) "utf8"))]
    (t/is (= 1 (:exit-code outcome)))
    (t/is (= record (:comparison outcome)))
    (t/is (= :running (:status record)))
    (t/is (= [:running :planned] (mapv :status (:configurations record))))
    (t/is (= :completed (get-in run [:termination :reason])))
    (t/is (= (get-in outcome [:home :log-path])
             (:log-path (ex-data (first (:errors outcome))))))))

(t/deftest create-home-creates-only-a-fresh-home
  (create-frontend-root)
  (let [home (feature-comparison/create-home (frontend-root) "comparison-a" nil)]
    (t/is (= (path/join (frontend-root) "target" "performance" "render-wasm"
                        "feature-comparisons" "comparison-a")
             (:dir home)))
    (t/is (= "" (fs/readFileSync (:log-path home) "utf8"))))
  (doseq [id [nil "" "../escape" "a/b" "a\\b"]]
    (t/is (thrown-with-msg? js/Error #"invalid feature comparison id"
                            (feature-comparison/create-home (frontend-root) id nil))))
  (let [existing (path/join @temporary-root "existing")]
    (fs/mkdirSync existing)
    (t/is (thrown-with-msg? js/Error #"already exists"
                            (feature-comparison/create-home (frontend-root)
                                                            "comparison-b" existing))))
  (let [builds (path/join (frontend-root) "target" "performance" "render-wasm" "builds")
        dir    (path/join builds "build-a" "nested")]
    (fs/mkdirSync (path/join builds "build-a") #js {:recursive true})
    (t/is (thrown-with-msg? js/Error #"inside builds"
                            (feature-comparison/create-home (frontend-root)
                                                            "comparison-c" dir)))))

(t/deftest ^:async initial-record-is-saved-before-the-first-build
  (create-frontend-root)
  (let [prepared  (comparison-prepared ["a"] {})
        record    (path/join (:output-dir prepared) "feature-comparison.transit")
        observed  (atom nil)
        services  (comparison-services
                   {:prepare-build (build-service
                                    {:inspect (fn [_]
                                                (reset! observed
                                                        (result/decode (fs/readFileSync record "utf8"))))
                                     :fail-on (constantly true)})
                    :execute-run (execute-service {})})
        result    (await (feature-comparison/execute
                          (frontend-root) prepared nil
                          {:services services}))]
    (t/is (fs/existsSync (get-in result [:home :log-path])))
    (t/is (= :running (:status @observed)))
    (t/is (= :building (get-in @observed [:configurations 0 :status])))
    (t/is (nil? (get-in @observed [:configurations 0 :build])))
    (t/is (= :planned (get-in @observed [:configurations 1 :status])))
    (t/is (= 1 (:exit-code result)))))

(t/deftest ^:async completed-configurations-and-edge-return-zero
  (create-frontend-root)
  (let [prepared   (comparison-prepared ["a"] {})
        result     (await (feature-comparison/execute
                           (frontend-root) prepared nil
                           {:services (comparison-services
                                       {:prepare-build
                                        (^:async fn [request]
                                          (let [build (await ((build-service {}) request))]
                                            (fs/mkdirSync (path/dirname (:log-path build)) #js {:recursive true})
                                            (binding [log/*log-path* (:log-path build)]
                                              (await (timers/setImmediate))
                                              (log/debug "Build fixture"))
                                            build))
                                        :execute-run
                                        (^:async fn [root home build cases options signal]
                                          (binding [log/*log-path* (:log-path home)]
                                            (let [response (await ((execute-service {}) root home build cases options signal))]
                                              (log/debug "Run fixture")
                                              response)))})}))
        record     (:comparison result)
        edge       (first (:edges record))
        path       (:path edge)
        comparison (result/decode (fs/readFileSync path "utf8"))]
    (t/is (= 0 (:exit-code result)))
    (t/is (= :completed (:status record)))
    (let [text (fs/readFileSync (get-in result [:home :log-path]) "utf8")]
      (doseq [slug ["0" "1"]
              phase ["built" "running" "completed"]]
        (t/is (str/includes? text (str "Configuration " slug " " phase "\n")))))
    (t/is (= ["a"] (get-in record [:configurations 1 :effective-features])))
    (t/is (= :comparison (:type comparison)))
    (t/is (= {:from "0"
              :to "1"
              :feature "a"
              :baseline-features []
              :candidate-features ["a"]
              :added ["a"]
              :removed []}
             (:edge comparison)))
    (t/is (= record (result/decode (fs/readFileSync (get-in result [:home :record-path])
                                                    "utf8"))))))

(t/deftest ^:async attempted-failed-run-continues-and-failed-edge-returns-one
  (create-frontend-root)
  (let [prepared (comparison-prepared ["a"] {})
        result   (await (feature-comparison/execute
                         (frontend-root) prepared nil
                         {:services (comparison-services
                                     {:prepare-build (build-service {})
                                      :execute-run
                                      (execute-service
                                       (fn [build]
                                         (if (seq (get-in build [:manifest :functional :features]))
                                           {}
                                           {:kind :failed})))})}))
        record   (:comparison result)
        edge     (first (:edges record))]
    (t/is (= 1 (:exit-code result)))
    (t/is (= :failed (:status record)))
    (t/is (= :failed (get-in record [:configurations 0 :status])))
    (t/is (= :completed (get-in record [:configurations 1 :status])))
    (t/is (fs/existsSync (get-in record [:configurations 0 :run :record-path])))
    (t/is (= :failed (:status edge)))
    (t/is (not (contains? edge :path)))
    (t/is (str/includes? (get-in edge [:failure :message]) "no shared metric"))))

(t/deftest ^:async zero-attempts-stop-later-configurations
  (create-frontend-root)
  (let [prepared (comparison-prepared ["a"] {})
        base     (execute-service {:kind :zero-attempts})
        calls    (atom 0)
        execute  (fn [& args]
                   (swap! calls inc)
                   (apply base args))
        result   (await (feature-comparison/execute
                         (frontend-root) prepared nil
                         {:services (comparison-services
                                     {:prepare-build (build-service {})
                                      :execute-run execute})}))
        record   (:comparison result)]
    (t/is (= 1 @calls))
    (t/is (= 1 (:exit-code result)))
    (t/is (= :failed (get-in record [:configurations 0 :status])))
    (t/is (= :unattempted (get-in record [:configurations 1 :status])))
    (t/is (str/includes? (get-in record [:configurations 1 :reason]) "no attempts"))
    (t/is (= :failed (get-in record [:edges 0 :status])))))

(t/deftest ^:async build-failure-stops-and-missing-endpoint-fails
  (create-frontend-root)
  (let [prepared (comparison-prepared ["a" "b"] {})
        result   (await (feature-comparison/execute
                         (frontend-root) prepared nil
                         {:services (comparison-services
                                     {:prepare-build (build-service
                                                      {:fail-on #(= ["b"] (:features %))})
                                      :execute-run (execute-service {})})}))
        record   (:comparison result)
        edge     (first (:edges record))]
    (t/is (= 1 (:exit-code result)))
    (t/is (= :failed (:status record)))
    (t/is (= :completed (get-in record [:configurations 0 :status])))
    (t/is (= :failed (get-in record [:configurations 1 :status])))
    (t/is (= :unattempted (get-in record [:configurations 2 :status])))
    (t/is (= :unattempted (get-in record [:configurations 3 :status])))
    (t/is (= :failed (:status edge)))
    (t/is (not (contains? edge :path)))
    (t/is (str/includes? (get-in edge [:failure :message]) "run record is missing"))))

(t/deftest ^:async catalog-mismatch-stops-before-a-run-home
  (create-frontend-root)
  (let [prepared (comparison-prepared ["a"] {})
        calls    (atom 0)
        base     (execute-service {})
        execute  (fn [& args]
                   (swap! calls inc)
                   (apply base args))
        result   (await (feature-comparison/execute
                         (frontend-root) prepared nil
                         {:services (comparison-services
                                     {:prepare-build (build-service {})
                                      :collect-catalog (fn [_ _] [{:id :other/case}])
                                      :execute-run execute})}))
        record   (:comparison result)]
    (t/is (zero? @calls))
    (t/is (= 1 (:exit-code result)))
    (t/is (= :failed (get-in record [:configurations 0 :status])))
    (t/is (= "catalog" (get-in record [:configurations 0 :failure :phase])))
    (t/is (= :unattempted (get-in record [:configurations 1 :status])))))

(t/deftest ^:async unacknowledged-differences-save-a-failed-edge-report
  (create-frontend-root)
  (let [prepared (comparison-prepared ["a"] {})
        result   (await (feature-comparison/execute
                         (frontend-root) prepared nil
                         {:services (comparison-services
                                     {:prepare-build (build-service {})
                                      :execute-run
                                      (execute-service
                                       (fn [build]
                                         (if (seq (get-in build [:manifest :functional :features]))
                                           {:digest "b"}
                                           {:digest "a"})))})}))
        record   (:comparison result)
        edge     (first (:edges record))
        saved    (result/decode (fs/readFileSync (:path edge) "utf8"))]
    (t/is (= 1 (:exit-code result)))
    (t/is (= :failed (:status record)))
    (t/is (= :failed (:status edge)))
    (t/is (fs/existsSync (:path edge)))
    (t/is (= {:unacknowledged-differences 1} (:failure edge)))
    (t/is (= :comparison (:type saved)))
    (t/is (= ["a"] (get-in saved [:edge :added])))
    (t/is (some #(and (= :fingerprint (:field %)) (not (:waived? %)))
                (get-in saved [:pairs 0 :mismatches])))))

(t/deftest ^:async interruption-skips-comparisons-and-returns-the-signal-exit
  (create-frontend-root)
  (let [prepared   (comparison-prepared ["a"] {})
        controller (js/AbortController.)
        base       (execute-service
                    (fn [build]
                      (when (empty? (get-in build [:manifest :functional :features]))
                        (.abort controller "SIGINT"))
                      {:kind :interrupted}))
        calls      (atom 0)
        execute    (fn [& args]
                     (swap! calls inc)
                     (apply base args))
        result     (await (feature-comparison/execute
                           (frontend-root) prepared (.-signal controller)
                           {:services (comparison-services
                                       {:prepare-build (build-service {})
                                        :execute-run execute})}))
        record     (:comparison result)]
    (t/is (= 1 @calls))
    (t/is (= 130 (:exit-code result)))
    (t/is (= :interrupted (:status record)))
    (t/is (some? (:ended-at record)))
    (t/is (= "SIGINT" (get-in record [:configurations 0 :reason])))
    (t/is (= :unattempted (get-in record [:configurations 1 :status])))
    (t/is (= :unattempted (get-in record [:edges 0 :status])))
    (t/is (not (fs/existsSync (path/join (:dir (:home result)) "comparisons"))))))

(t/deftest ^:async interruption-between-configurations-stops-before-the-next-build
  (create-frontend-root)
  (let [prepared   (comparison-prepared ["a"] {})
        controller (js/AbortController.)
        builds     (atom 0)
        base       (execute-service {})
        execute    (fn [& args]
                     (let [result (apply base args)]
                       (.abort controller "SIGTERM")
                       result))
        result     (await (feature-comparison/execute
                           (frontend-root) prepared (.-signal controller)
                           {:services (comparison-services
                                       {:prepare-build
                                        (build-service
                                         {:inspect (fn [_] (swap! builds inc))})
                                        :execute-run execute})}))
        record     (:comparison result)]
    (t/is (= 1 @builds))
    (t/is (= 143 (:exit-code result)))
    (t/is (= :interrupted (:status record)))
    (t/is (= :completed (get-in record [:configurations 0 :status])))
    (t/is (= :unattempted (get-in record [:configurations 1 :status])))
    (t/is (str/includes? (get-in record [:configurations 1 :reason]) "SIGTERM"))
    (t/is (= :unattempted (get-in record [:edges 0 :status])))
    (t/is (not (fs/existsSync (path/join (:dir (:home result)) "comparisons"))))))

(t/deftest ^:async persistence-failure-retains-the-last-saved-state
  (create-frontend-root)
  (let [stdout      (atom "")
        prepared    (comparison-prepared ["a"] {})
        record-path (path/join (:output-dir prepared) "feature-comparison.transit")
        prepare     (fn [request]
                      (fs/rmSync record-path)
                      (fs/mkdirSync record-path)
                      (let [build (fake-build (:selection request) [])]
                        ((:on-created request) build)
                        (js/Promise.resolve build)))
        result      (do
                      (log/configure {:stdout #js {:write #(swap! stdout str %)}})
                      (await (feature-comparison/execute
                              (frontend-root) prepared nil
                              {:services (comparison-services
                                          {:prepare-build prepare
                                           :execute-run (execute-service {})})})))
        record      (:comparison result)]
    (t/is (= 1 (:exit-code result)))
    (t/is (str/ends-with? @stdout "Status failed\n"))
    (t/is (seq (:errors result)))
    (t/is (= :running (:status record)))
    (t/is (= :building (get-in record [:configurations 0 :status])))
    (t/is (nil? (get-in record [:configurations 0 :build])))
    (t/is (= :planned (get-in record [:configurations 1 :status])))))

(t/deftest ^:async comparison-storage-error-uses-comparison-evidence-after-a-failed-config
  (create-frontend-root)
  (let [prepared (comparison-prepared ["a"] {})
        record   (path/join (:output-dir prepared) "feature-comparison.transit")
        response (await (feature-comparison/execute
                         (frontend-root) prepared nil
                         {:services
                          (comparison-services
                           {:prepare-build
                            (build-service
                             {:inspect (fn [request]
                                         (when (seq (get-in request [:selection :features]))
                                           (fs/renameSync record (str record ".saved"))
                                           (fs/mkdirSync record)))})
                            :execute-run (execute-service {:kind :failed})})}))]
    (t/is (= 1 (:exit-code response)))
    (t/is (= :failed (get-in response [:comparison :configurations 0 :status])))
    (t/is (= (get-in response [:home :log-path])
             (:log-path (ex-data (first (:errors response))))))))

(t/deftest ^:async comparison-storage-error-keeps-its-origin-through-build-preparation
  (create-frontend-root)
  (let [prepared (comparison-prepared ["a"] {})
        record   (path/join (:output-dir prepared) "feature-comparison.transit")
        response (await (feature-comparison/execute
                         (frontend-root) prepared nil
                         {:services
                          {:prepare-build
                           (^:async fn [request]
                             (let [created (:on-created request)]
                               (await (build/prepare
                                       (assoc request :on-created
                                              (fn [selected]
                                                (fs/renameSync record (str record ".saved"))
                                                (fs/mkdirSync record)
                                                (created selected)))))))}}))]
    (t/is (= 1 (:exit-code response)))
    (t/is (= (get-in response [:home :log-path])
             (:log-path (ex-data (first (:errors response))))))))

(t/deftest ^:async pending-real-signals-stop-edge-analysis
  (create-frontend-root)
  (doseq [[signal exit-code] [["SIGINT" 130] ["SIGTERM" 143]]]
    (let [prepared   (comparison-prepared ["a"] {:output-dir (path/join @temporary-root signal)})
          controller (js/AbortController.)
          remove     (process/on-signals #(.abort controller %))
          execute    (execute-service
                      (fn [build]
                        (when (seq (get-in build [:manifest :functional :features]))
                          (.kill js/process (.-pid js/process) signal))
                        {}))]
      (try
        (let [response (await (feature-comparison/execute
                               (frontend-root) prepared (.-signal controller)
                               {:services (comparison-services
                                           {:prepare-build (build-service {})
                                            :execute-run execute})}))]
          (await (timers/setImmediate))
          (let [saved (result/decode (fs/readFileSync (get-in response [:home :record-path]) "utf8"))]
            (t/is (= exit-code (:exit-code response)))
            (t/is (= :interrupted (:status saved)))
            (t/is (every? #(= :unattempted (:status %)) (:edges saved)))))
        (finally
          (remove))))))
