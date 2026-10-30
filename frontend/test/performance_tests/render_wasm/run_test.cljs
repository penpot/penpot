;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS SUBSIDIARY SL

(ns performance-tests.render-wasm.run-test
  (:require
   ["fs" :as fs]
   ["os" :as os]
   ["path" :as path]
   [app.common.transit :as transit]
   [benches.render-wasm.errors :as errors]
   [benches.render-wasm.feature-plan :as feature-plan]
   [benches.render-wasm.node.artifacts :as artifacts]
   [benches.render-wasm.node.feature-comparison :as feature-comparison]
   [benches.render-wasm.node.log :as log]
   [benches.render-wasm.node.run :as run]
   [benches.render-wasm.node.runs :as runs]
   [benches.render-wasm.result :as result]
   [cljs.test :as t :include-macros true]
   [clojure.string :as str]))

(def ^:private temporary-root (atom nil))

(t/use-fixtures :each
  {:before #(reset! temporary-root
                    (fs/mkdtempSync (path/join (os/tmpdir) "penpot-render-run-")))
   :after (fn []
            (log/configure {})
            (fs/rmSync @temporary-root #js {:recursive true :force true}))})

(defn- output-dir
  []
  (path/join @temporary-root (str "out-" (random-uuid))))

(defn- build-dir
  []
  (let [dir (path/join @temporary-root (str "build-" (random-uuid)))]
    (fs/mkdirSync dir #js {:recursive true})
    (fs/writeFileSync (path/join dir "index.html") "<!doctype html>")
    dir))

(defn- selected-build
  [dir id]
  {:id id
   :dir dir
   :log-path (path/join dir "build.log")
   :manifest {:type :renderer-build
              :id id
              :status :ready
              :created-at "2026-10-27T00:00:00.000Z"
              :updated-at "2026-10-27T00:00:00.000Z"
              :functional {:mode :release
                           :default-features? true
                           :features []
                           :cljs-profile-marks false}
              :provenance {:git {:sha "build-revision" :dirty false}}}})

(defn- collected
  [id context]
  {:id id
   :scene :case
   :scene-description "test scene"
   :scene-seed 1
   :params {}
   :view {:scale 1 :x 0 :y 0}
   :context context
   :completion :render-full
   :batch-size 1
   :preparation {}})

(defn- next-count
  [state key]
  (inc (get @state key 0)))

(defn- check-page
  [page]
  (when-not (and (map? page) (some? (:id page)))
    (throw (ex-info "browser service requires the page object"
                    {:phase :browser-page-startup}))))

(def ^:private hardware-graphics
  {:webgl2 true :vendor "NVIDIA" :renderer "ANGLE (NVIDIA, Vulkan, NVIDIA)" :software false})

(defn- fake-browser
  [{:keys [launch-failure new-page-failure close-page-failure
           prepare-failure run-failure screenshot-failure
           interruption controller effective-graphics metrics]
    :or {effective-graphics (constantly {}) metrics {}}}]
  (let [state (atom {:launches 0 :pages 0 :close-pages 0 :closes 0
                     :host nil :runs {} :preparations {}})]
    {:state state
     :service
     {:launch
      (fn [_]
        (let [n (next-count state :launches)]
          (swap! state assoc :launches n)
          (when (and launch-failure (launch-failure n))
            (throw (ex-info "browser launch failed" {:phase :browser-startup})))
          {:version "FakeBrowser/1" :browser #js {}}))
      :new-page
      (fn [_ host _]
        (when-not (and (string? (:base-url host))
                       (some? (get-in host [:build :id])))
          (throw (ex-info "new-page requires the opened host"
                          {:phase :browser-page-startup})))
        (swap! state assoc :host host)
        (let [n (next-count state :pages)]
          (swap! state assoc :pages n)
          (when (and new-page-failure (new-page-failure n))
            (throw (ex-info "browser page failed" {:phase :browser-page-startup})))
          {:page {:id n} :context {}}))
      :close-page
      (fn [_]
        (let [n (next-count state :close-pages)]
          (swap! state assoc :close-pages n)
          (when (and close-page-failure (close-page-failure n))
            (throw (ex-info "page close failed" {:phase :browser-page-close})))))
      :close
      (fn [_]
        (swap! state update :closes inc))
      :connected?
      (fn [_] true)
      :prepare
      (fn [page request _]
        (check-page page)
        (let [{:keys [preparation-id] case-desc :case} (transit/decode-str request)
              case-id                       (:id case-desc)
              failure                       (when prepare-failure
                                              (prepare-failure case-id))]
          (case failure
            :throw
            (throw (ex-info "scene preparation failed" {:phase :scene-build}))

            :response
            {:status "failed" :phase "scene-build" :message "scene rejected"}

            (let [preparation {:preparation-id preparation-id
                               :case-id case-id
                               :fingerprint {:algorithm :sha-256
                                             :digest (apply str (repeat 64 "a"))}
                               :effective-graphics (effective-graphics case-id (:id page))
                               :diagnostics {}}]
              (swap! state assoc-in [:preparations preparation-id] case-id)
              {:status "ok" :preparation preparation}))))
      :run
      (fn [page request _]
        (check-page page)
        (let [{:keys [preparation-id]} (transit/decode-str request)
              case-id (get-in @state [:preparations preparation-id])
              n       (inc (get-in @state [:runs case-id] 0))
              _       (swap! state assoc-in [:runs case-id] n)
              signal  (when interruption (interruption case-id n))]
          (when signal
            (let [reason (ex-info "run interrupted"
                                  {:type ::interrupted :signal signal})]
              (.abort controller reason)
              (throw reason)))
          (case (when run-failure (run-failure case-id n))
            :throw
            (throw (ex-info "attempt failed" {:phase "interact"}))

            :response
            {:status "failed" :phase "interact" :message "attempt rejected"}

            {:status "ok" :metrics metrics :trace {}})))
      :screenshot
      (fn [page file _]
        (check-page page)
        (when (and screenshot-failure (screenshot-failure file))
          (throw (ex-info "screenshot failed" {:phase :screenshot})))
        (fs/writeFileSync file "png"))}}))

(defn- options
  [fake out]
  {:output-dir out
   :seed 42
   :filter nil
   :warmups 0
   :repetitions 1
   :timeout-ms 5000
   :build-timeout-ms 60000
   :startup-timeout-ms 5000
   :screenshots false
   :remove false
   :browser (:service fake)})

(defn- execute
  ([fake cases opts]
   (execute fake cases opts (js/AbortController.)))
  ([_fake cases opts controller]
   (run/execute (artifacts/frontend-root)
                (runs/create-home {:root (artifacts/frontend-root) :run-id "test-run"
                                   :output-dir (:output-dir opts)})
                (selected-build (build-dir) "test-build")
                cases opts (.-signal controller))))

(defn- attempts-for
  [run case-id]
  (filterv #(= case-id (:case-id %)) (:attempts run)))

(defn- preparations-for
  [run case-id]
  (filterv #(= case-id (:case-id %)) (:preparations run)))

(defn- saved-record
  [result]
  (result/decode (fs/readFileSync (get-in result [:home :record-path]) "utf8")))

(t/deftest ^:async run-rejects-invalid-options-before-allocating-output
  (doseq [invalid [{:seed 4294967296}
                   {:warmups -1}
                   {:repetitions 1.5}
                   {:timeout-ms js/Infinity}
                   {:build-timeout-ms 2147483648}
                   {:startup-timeout-ms 0}
                   {:screenshots "false"}
                   {:remove nil}
                   {:require-gpu 1}
                   {:repetitons 1}]]
    (let [out   (output-dir)
          opts  (merge (dissoc (options nil out) :browser)
                       {:build-id "unavailable"} invalid)
          cause (try
                  (await (run/run @temporary-root opts nil))
                  nil
                  (catch :default cause cause))]
      (t/testing (pr-str invalid)
        (t/is (= ::run/invalid-options (:type (ex-data cause))))
        (t/is (not (fs/existsSync out)))))))

(t/deftest ^:async execute-rejects-invalid-options-before-writing-evidence
  (let [root       @temporary-root
        fake       (fake-browser {})
        home       (runs/create-home {:root root :run-id "invalid-options"
                                      :output-dir (output-dir)})
        files      (vec (fs/readdirSync (:dir home)))
        log-text   (fs/readFileSync (:log-path home) "utf8")
        opts       (assoc (options fake (:dir home)) :screenshots "false")
        cause      (try
                     (await (run/execute root home (selected-build (build-dir) "test-build")
                                         [(collected :case/a :fresh)] opts nil))
                     nil
                     (catch :default cause cause))]
    (t/is (= ::run/invalid-options (:type (ex-data cause))))
    (t/is (= files (vec (fs/readdirSync (:dir home)))))
    (t/is (= log-text (fs/readFileSync (:log-path home) "utf8")))))

(t/deftest ^:async retained-run-prints-results-and-keeps-all-levels-in-its-log
  (doseq [level [:info :warn :debug]]
    (let [root   @temporary-root
          cases  [(collected :case/a :fresh) (collected :case/b :reuse)]
          build  (artifacts/create-build root {:default-features? true :features []}
                                         {:git {:sha "fixture" :dirty false}})
          stdout (atom "")
          stderr (atom "")
          fake   (fake-browser {})
          out    (output-dir)]
      (doseq [file [(:index-path build) (path/join (:browser-dir build) "browser.js")
                    (path/join (:renderer-dir build) "render-wasm.js")
                    (path/join (:renderer-dir build) "render-wasm.wasm")
                    (path/join (:renderer-dir build) "shared.js")]]
        (fs/writeFileSync file "fixture" "utf8"))
      (fs/writeFileSync (:cases-path build)
                        (str "module.exports = {buildIdentity() {return "
                             (js/JSON.stringify (transit/encode-str {:build-id (:id build)}))
                             ";}, collectCases() {return "
                             (js/JSON.stringify (transit/encode-str {:build-id (:id build) :cases cases}))
                             ";}};") "utf8")
      (artifacts/write-manifest build (assoc (:manifest build) :status :ready))
      (log/configure {:level level :color :never
                      :stdout #js {:write #(swap! stdout str %)}
                      :stderr #js {:write #(swap! stderr str %)}})
      (let [response (await (run/run root (assoc (options fake out) :build-id (:id build)
                                                 :warmups 1 :repetitions 2) nil))
            saved    (saved-record response)
            text     (fs/readFileSync (get-in response [:home :log-path]) "utf8")]
        (t/is (= 0 (:exit-code response)))
        (t/is (= (str "Run " (:run-id saved) "\nOutput " out "\nReport\n"
                      (str/replace (:report response) #"\n$" "") "\n") @stdout))
        (t/is (str/includes? text "▸ Case case/a (fresh)\n"))
        (doseq [case-text (rest (str/split text #"▸ Case "))]
          (t/is (str/includes? case-text "  attempt 1/3 completed (warmup)\n"))
          (t/is (str/includes? case-text "  attempt 2/3 completed\n"))
          (t/is (str/includes? case-text "  attempt 3/3 completed\n")))
        (t/is (str/includes? text (:report response)))
        (t/is (str/includes? text "Browser FakeBrowser/1"))
        (t/is (= (not= :warn level) (str/includes? @stderr "▸ Case case/a")))
        (t/is (= (= :debug level) (str/includes? @stderr "Browser FakeBrowser/1")))))))

(t/deftest ^:async fresh-and-reuse-cases-follow-their-context-policies
  (let [fake    (fake-browser {})
        cases   [(collected :case/fresh :fresh) (collected :case/reuse :reuse)]
        opts    (assoc (options fake (output-dir)) :warmups 1 :repetitions 2)
        result  (await (execute fake cases opts))
        saved   (saved-record result)
        run     (:run result)
        fresh   (attempts-for run :case/fresh)
        reuse   (attempts-for run :case/reuse)]
    (t/is (= saved run))
    (t/is (= 6 (count (:attempts run))))
    (t/is (= 3 (count (preparations-for run :case/fresh))))
    (t/is (= 1 (count (preparations-for run :case/reuse))))
    (t/is (= 3 (count (distinct (map :preparation-id fresh)))))
    (t/is (= 1 (count (distinct (map :preparation-id reuse)))))
    (t/is (every? #(= :completed (:outcome %)) (:attempts run)))
    (t/is (every? #(every? zero? (vals %)) (vals (:remaining run))))
    (t/is (= :completed (get-in run [:termination :reason])))
    (t/is (zero? (:exit-code result)))
    (t/is (empty? (get-in run [:metadata :diagnostics :screenshots])))
    (t/is (empty? (filter #(str/ends-with? % ".png")
                          (array-seq (fs/readdirSync (get-in result [:home :dir]))))))))

(t/deftest ^:async page-startup-failure-abandons-the-case-and-keeps-its-slots
  (doseq [context [:fresh :reuse]]
    (let [fake   (fake-browser {:new-page-failure #(= 1 %)})
          opts   (assoc (options fake (output-dir)) :repetitions 2)
          result (await (execute fake [(collected :case/a context)
                                       (collected :case/b :fresh)]
                                 opts))
          run    (:run result)
          state  @(:state fake)]
      (t/is (empty? (attempts-for run :case/a)))
      (t/is (empty? (preparations-for run :case/a)))
      (t/is (= {:warmups 0 :repetitions 2}
               (get-in run [:remaining :case/a])))
      (t/is (= [:completed :completed]
               (mapv :outcome (attempts-for run :case/b))))
      (t/is (= :incomplete (get-in run [:termination :reason])))
      (t/is (= :browser-page (get-in run [:termination :failure :stage])))
      (t/is (= 1 (:exit-code result)))
      (t/is (= 2 (:launches state)))
      (t/is (= 2 (:closes state))))))

(t/deftest ^:async failed-attempts-consume-slots-and-the-runner-continues
  (doseq [failure [:throw :response]]
    (let [fake   (fake-browser {:run-failure (fn [case-id n]
                                               (when (and (= case-id :case/fresh) (= n 1))
                                                 failure))})
          opts   (assoc (options fake (output-dir)) :repetitions 2)
          result (await (execute fake [(collected :case/fresh :fresh)] opts))
          run    (:run result)
          tries  (attempts-for run :case/fresh)]
      (t/is (= 2 (count tries)))
      (t/is (= [:failed :completed] (mapv :outcome tries)))
      (t/is (every? zero? (vals (get-in run [:remaining :case/fresh]))))
      (t/is (= :failed (get-in run [:termination :reason])))
      (t/is (= 1 (:exit-code result)))
      (t/is (= 2 (count (preparations-for run :case/fresh)))))))

(t/deftest ^:async reuse-preparation-failure-leaves-slots-and-continues-to-the-next-case
  (doseq [kind [:throw :response]]
    (let [fake   (fake-browser {:prepare-failure #(when (= % :case/reuse) kind)})
          opts   (assoc (options fake (output-dir)) :screenshots true :repetitions 2)
          result (await (execute fake [(collected :case/reuse :reuse)
                                       (collected :case/fresh :fresh)] opts))
          run    (:run result)
          shots  (get-in run [:metadata :diagnostics :screenshots])]
      (t/is (empty? (attempts-for run :case/reuse)))
      (t/is (= {:warmups 0 :repetitions 2} (get-in run [:remaining :case/reuse])))
      (t/is (= [:completed :completed] (mapv :outcome (attempts-for run :case/fresh))))
      (t/is (= :incomplete (get-in run [:termination :reason])))
      (t/is (= 1 (:exit-code result)))
      (t/is (= :skipped (get-in shots ["case/reuse" :status])))
      (t/is (= :ok (get-in shots ["case/fresh" :status])))
      (let [failure (last (get-in run [:metadata :diagnostics :failures]))]
        (t/is (= :preparation (:stage failure)))
        (t/is (= :case/reuse (:case-id failure)))
        (t/is (= "scene-build" (:phase failure)))))))

(t/deftest ^:async fresh-preparation-failure-consumes-one-slot-and-retries
  (doseq [kind [:throw :response]]
    (let [preparations (atom 0)
          fake   (fake-browser {:prepare-failure #(when (and (= % :case/fresh)
                                                             (= 1 (swap! preparations inc)))
                                                    kind)})
          result (await (execute fake [(collected :case/fresh :fresh)]
                                 (assoc (options fake (output-dir)) :repetitions 2 :screenshots true)))
          run    (:run result)
          tries  (attempts-for run :case/fresh)]
      (t/is (= [:failed :completed] (mapv :outcome tries)))
      (t/is (nil? (:preparation-id (first tries))))
      (t/is (= "scene-build" (get-in (first tries) [:failure :phase])))
      (t/is (= 1 (count (preparations-for run :case/fresh))))
      (t/is (= :ok (get-in run [:metadata :diagnostics :screenshots "case/fresh" :status])))
      (t/is (every? zero? (vals (get-in run [:remaining :case/fresh]))))
      (t/is (= :failed (get-in run [:termination :reason])))
      (t/is (= 1 (:exit-code result))))))

(t/deftest ^:async reuse-attempt-failure-leaves-pending-slots-and-continues-to-the-next-case
  (doseq [kind [:throw :response]]
    (let [fake   (fake-browser {:run-failure (fn [case-id n]
                                               (when (and (= case-id :case/reuse) (= n 1)) kind))})
          opts   (assoc (options fake (output-dir)) :warmups 1 :repetitions 2 :screenshots true)
          result (await (execute fake [(collected :case/reuse :reuse)
                                       (collected :case/fresh :fresh)] opts))
          run    (saved-record result)]
      (t/is (= [:failed] (mapv :outcome (attempts-for run :case/reuse))))
      (t/is (= {:warmups 0 :repetitions 2} (get-in run [:remaining :case/reuse])))
      (t/is (= [:completed :completed :completed] (mapv :outcome (attempts-for run :case/fresh))))
      (t/is (= {:status :skipped :reason :case-abandoned}
               (get-in run [:metadata :diagnostics :screenshots "case/reuse"])))
      (t/is (= :incomplete (get-in run [:termination :reason])))
      (t/is (= 1 (:exit-code result))))))

(t/deftest ^:async final-attempt-failure-records-why-the-screenshot-was-skipped
  (doseq [context [:fresh :reuse]
          failure [:preparation :attempt]
          :when (or (= context :fresh) (= failure :attempt))]
    (let [fake   (fake-browser (if (= failure :preparation)
                                 {:prepare-failure (constantly :response)}
                                 {:run-failure (fn [_ _] :response)}))
          opts   (assoc (options fake (output-dir)) :screenshots true)
          result (await (execute fake [(collected :case/a context)] opts))
          run    (saved-record result)]
      (t/is (= [:failed] (mapv :outcome (:attempts run))))
      (t/is (= {:status :skipped :reason :no-completed-measured-attempt}
               (get-in run [:metadata :diagnostics :screenshots "case/a"])))
      (t/is (= :failed (get-in run [:termination :reason])))
      (t/is (= 1 (:exit-code result))))))

(t/deftest ^:async browser-close-failure-abandons-the-case-and-relaunches-for-the-next
  (let [fake   (fake-browser {:run-failure (fn [case-id n]
                                             (when (and (= case-id :case/a) (= n 1))
                                               :throw))
                              :close-page-failure #(= % 1)})
        opts   (assoc (options fake (output-dir)) :repetitions 2)
        result (await (execute fake [(collected :case/a :fresh)
                                     (collected :case/b :fresh)] opts))
        run    (:run result)
        state  @(:state fake)]
    (t/is (= 2 (:launches state)))
    (t/is (= 1 (count (attempts-for run :case/a))))
    (t/is (= :failed (:outcome (first (attempts-for run :case/a)))))
    (t/is (= 1 (get-in run [:remaining :case/a :repetitions])))
    (t/is (= 2 (count (attempts-for run :case/b))))
    (t/is (every? #(= :completed (:outcome %)) (attempts-for run :case/b)))
    (t/is (every? zero? (vals (get-in run [:remaining :case/b]))))
    (t/is (= :incomplete (get-in run [:termination :reason])))
    (t/is (= 1 (:exit-code result)))))

(t/deftest ^:async page-close-failure-after-the-final-slot-is-a-harness-failure
  (let [fake   (fake-browser {:close-page-failure #(= % 1)})
        result (await (execute fake [(collected :case/a :fresh)]
                               (options fake (output-dir))))
        run    (:run result)]
    (t/is (= :completed (get-in run [:termination :reason])))
    (t/is (every? zero? (vals (get-in run [:remaining :case/a]))))
    (t/is (= :browser-close
             (:stage (last (get-in run [:metadata :diagnostics :failures])))))
    (t/is (= :browser-close (get-in run [:termination :failure :stage])))
    (t/is (= 1 (:exit-code result)))))

(t/deftest ^:async initial-browser-launch-failure-aborts-before-any-case
  (let [fake   (fake-browser {:launch-failure #(= % 1)})
        result (await (execute fake [(collected :case/a :fresh)]
                               (options fake (output-dir))))
        run    (:run result)
        state  @(:state fake)]
    (t/is (= 1 (:launches state)))
    (t/is (empty? (:attempts run)))
    (t/is (= {:warmups 0 :repetitions 1} (get-in run [:remaining :case/a])))
    (t/is (= :incomplete (get-in run [:termination :reason])))
    (t/is (= :browser-launch (:stage (last (get-in run [:metadata :diagnostics :failures])))))
    (t/is (= 1 (:exit-code result)))))

(t/deftest ^:async hardware-preparations-complete-fresh-and-reuse-cases
  (let [fake   (fake-browser {:effective-graphics (constantly hardware-graphics)})
        opts   (assoc (options fake (output-dir)) :require-gpu true :warmups 1 :repetitions 2)
        result (await (execute fake [(collected :case/fresh :fresh)
                                     (collected :case/reuse :reuse)] opts))
        run    (saved-record result)]
    (t/is (= 0 (:exit-code result)))
    (t/is (= :completed (get-in run [:termination :reason])))
    (t/is (= 6 (count (:attempts run))))
    (t/is (every? #(= hardware-graphics (:effective-graphics %)) (:preparations run)))
    (t/is (true? (get-in run [:metadata :diagnostics :require-gpu?])))))

(t/deftest ^:async required-gpu-failures-preserve-evidence-and-all-attempt-slots
  (doseq [context [:fresh :reuse]
          graphics [(assoc hardware-graphics :renderer "ANGLE (SwiftShader)" :software true)
                    (assoc hardware-graphics :webgl2 false)
                    (assoc hardware-graphics :renderer nil)
                    (assoc hardware-graphics :renderer "  UnKnOwN  ")
                    (assoc hardware-graphics :vendor "")
                    {}]]
    (let [fake    (fake-browser {:effective-graphics (constantly graphics)})
          opts    (assoc (options fake (output-dir)) :require-gpu true :warmups 1)
          result  (await (execute fake [(collected :case/a context)
                                        (collected :case/b :reuse)] opts))
          run     (saved-record result)
          failure (get-in run [:termination :failure])
          state   @(:state fake)]
      (t/is (= 1 (:exit-code result)))
      (t/is (= :incomplete (get-in run [:termination :reason])))
      (t/is (= [graphics] (mapv :effective-graphics (:preparations run))))
      (t/is (= [] (:attempts run)))
      (t/is (= {:case/a {:warmups 1 :repetitions 1}
                :case/b {:warmups 1 :repetitions 1}} (:remaining run)))
      (t/is (= :gpu (:stage failure)))
      (t/is (= graphics (get-in failure [:partial :graphics])))
      (t/is (str/includes? (:message failure) (pr-str (:renderer graphics))))
      (t/is (str/includes? (:message failure) "./manage.sh run-devenv --gpu"))
      (t/is (str/includes? (:message failure) "pnpm run render-bench"))
      (t/is (= 1 (:close-pages state)))
      (t/is (= 1 (:closes state))))))

(t/deftest ^:async graphics-rejection-preserves-earlier-attempts-and-stops-the-run
  (doseq [context [:fresh :reuse]]
    (let [software (assoc hardware-graphics :renderer "SwiftShader" :software true)
          fake    (fake-browser {:effective-graphics (fn [_ page-id]
                                                       (if (= page-id 1) hardware-graphics software))})
          opts    (assoc (options fake (output-dir)) :require-gpu true :repetitions 2)
          result  (await (execute fake [(collected :case/a context)
                                        (collected :case/b context)] opts))
          run     (saved-record result)
          failure (get-in run [:termination :failure])]
      (t/is (= 1 (:exit-code result)))
      (t/is (= :gpu (:stage failure)))
      (t/is (= software (get-in failure [:partial :graphics])))
      (t/is (= (if (= context :fresh) 1 2) (count (:attempts run))))
      (t/is (every? #(= :completed (:outcome %)) (:attempts run)))
      (t/is (= [hardware-graphics software] (mapv :effective-graphics (:preparations run))))
      (t/is (= {:case/a {:warmups 0 :repetitions (if (= context :fresh) 1 0)}
                :case/b {:warmups 0 :repetitions 2}} (:remaining run)))
      (t/is (= 2 (:close-pages @(:state fake))))
      (t/is (= 1 (:closes @(:state fake)))))))

(t/deftest ^:async sigint-and-sigterm-persist-partial-interrupted-records
  (doseq [context [:fresh :reuse]
          [signal code] [["SIGINT" 130] ["SIGTERM" 143]]]
    (let [controller (js/AbortController.)
          fake       (fake-browser {:controller controller
                                    :interruption (fn [case-id n]
                                                    (when (and (= case-id :case/a) (= n 1))
                                                      signal))})
          opts       (assoc (options fake (output-dir)) :screenshots true)
          result     (await (execute fake [(collected :case/a context)
                                           (collected :case/b context)]
                                     opts controller))
          run        (:run result)
          saved      (saved-record result)
          shots      (get-in run [:metadata :diagnostics :screenshots])]
      (t/is (= saved run))
      (t/is (= :interrupted (get-in run [:termination :reason])))
      (t/is (= signal (get-in run [:termination :signal])))
      (t/is (= code (:exit-code result)))
      (t/is (= 1 (count (attempts-for run :case/a))))
      (t/is (empty? (attempts-for run :case/b)))
      (t/is (= 1 (get-in run [:remaining :case/b :repetitions])))
      (t/is (= :skipped (get-in shots ["case/b" :status])))
      (t/is (= :not-run (get-in shots ["case/b" :reason])))
      (t/is (pos? (:closes @(:state fake)))))))

(t/deftest ^:async interruption-before-an-attempt-preserves-unspent-slots
  (doseq [context [:fresh :reuse]
          method [:launch :new-page :prepare]
          [reason signal code] [["SIGINT" "SIGINT" 130]
                                ["SIGTERM" "SIGTERM" 143]
                                [:SIGTERM "SIGTERM" 143]
                                [(ex-info "stopped" {:signal "SIGTERM"}) "SIGTERM" 143]
                                [(js/Error. "stopped") "SIGINT" 130]]]
    (let [controller (js/AbortController.)
          base       (fake-browser {})
          stop       (^:async fn [& _]
                       (let [cause (ex-info "interrupted setup" {:signal signal :phase :preparation})]
                         (.abort controller reason)
                         (throw cause)))
          fake       (assoc base :service (assoc (:service base) method stop))
          opts       (assoc (options fake (output-dir)) :warmups 1 :repetitions 2)
          result     (await (execute fake [(collected :case/a context)
                                           (collected :case/b context)] opts controller))
          run        (saved-record result)
          consumed   (if (and (= method :prepare) (= context :fresh)) 1 0)]
      (t/is (= :interrupted (get-in run [:termination :reason])))
      (t/is (= signal (get-in run [:termination :signal])))
      (t/is (= code (:exit-code result)))
      (t/is (= consumed (count (:attempts run))))
      (t/is (= {:warmups (- 1 consumed) :repetitions 2} (get-in run [:remaining :case/a])))
      (t/is (= {:warmups 1 :repetitions 2} (get-in run [:remaining :case/b])))
      (t/is (= (if (and (= method :prepare) (= context :reuse)) [:preparation] [])
               (mapv :stage (get-in run [:metadata :diagnostics :failures])))))))

(t/deftest ^:async fresh-preparation-and-rendering-share-the-attempt-time-budget
  (doseq [context [:fresh :reuse]]
    (let [system-now (.-now js/Date)
          clock      (atom (system-now))
          base       (fake-browser {})
          work       (^:async fn [bounds delegate page request]
                       (await (js/Promise.resolve nil))
                       (swap! clock + 150)
                       (when (>= @clock (:deadline bounds))
                         (throw (ex-info "browser work timed out" {:phase (:phase bounds)})))
                       (delegate page request bounds))
          fake       (assoc base :service
                            (assoc (:service base)
                                   :prepare (fn [page request bounds]
                                              (work bounds (:prepare (:service base)) page request))
                                   :run (fn [page request bounds]
                                          (work bounds (:run (:service base)) page request))))]
      (try
        (set! (.-now js/Date) (fn [] @clock))
        (let [result (await (execute fake [(collected :case/a context)]
                                     (assoc (options fake (output-dir)) :timeout-ms 250)))
              saved  (saved-record result)]
          (t/is (= (if (= context :fresh) :failed :completed) (:outcome (first (:attempts saved)))))
          (t/is (= (if (= context :fresh) 1 0) (:exit-code result))))
        (finally
          (set! (.-now js/Date) system-now))))))

(t/deftest ^:async browser-disconnection-leaves-pending-slots-and-relaunches-for-the-next-case
  (let [base         (fake-browser {})
        disconnected (atom nil)
        new-page     (:new-page (:service base))
        service      (assoc (:service base)
                            :connected? (fn [owner]
                                          (compare-and-set! disconnected nil (:browser owner))
                                          (not (identical? @disconnected (:browser owner))))
                            :new-page (fn [owner host options]
                                        (when (identical? @disconnected (:browser owner))
                                          (throw (js/Error. "browser disconnected")))
                                        (new-page owner host options)))
        fake         (assoc base :service service)
        opts         (assoc (options fake (output-dir)) :repetitions 2)
        result       (await (execute fake [(collected :case/a :fresh) (collected :case/b :fresh)] opts))
        run          (saved-record result)]
    (t/is (= [:completed] (mapv :outcome (attempts-for run :case/a))))
    (t/is (= {:warmups 0 :repetitions 1} (get-in run [:remaining :case/a])))
    (t/is (= [:completed :completed] (mapv :outcome (attempts-for run :case/b))))
    (t/is (= :incomplete (get-in run [:termination :reason])))
    (t/is (= 1 (:exit-code result)))))

(t/deftest ^:async provenance-timeout-saves-the-unattempted-run
  (let [bin      (path/join @temporary-root "bin")
        prior    (.-PATH (.-env js/process))
        _        (fs/mkdirSync bin)
        _        (fs/writeFileSync (path/join bin "git")
                                   (str "#!" (.-execPath js/process) "\nsetInterval(() => {}, 1000);\n")
                                   #js {:mode 8r755})
        fake     (fake-browser {})]
    (try
      (set! (.-PATH (.-env js/process)) (str bin path/delimiter prior))
      (let [result (await (execute fake [(collected :case/a :fresh)]
                                   (assoc (options fake (output-dir)) :startup-timeout-ms 50)))
            run    (saved-record result)]
        (t/is (= :incomplete (get-in run [:termination :reason])))
        (t/is (= :runner-git (get-in run [:termination :failure :stage])))
        (t/is (= {:case/a {:warmups 0 :repetitions 1}} (:remaining run)))
        (t/is (= [] (:attempts run)))
        (t/is (= 1 (:exit-code result))))
      (finally
        (set! (.-PATH (.-env js/process)) prior)))))

(t/deftest ^:async unavailable-build-directory-saves-a-server-startup-failure
  (let [root     @temporary-root
        selected (selected-build (build-dir) "unavailable-build")
        home     (runs/create-home {:root root :run-id "unavailable-build" :output-dir (output-dir)})
        _        (fs/renameSync (:dir selected) (str (:dir selected) ".unavailable"))
        fake     (fake-browser {})
        result   (await (run/execute root home selected [(collected :case/a :fresh)]
                                     (options fake (:dir home)) (.-signal (js/AbortController.))))
        run      (saved-record result)]
    (t/is (= :incomplete (get-in run [:termination :reason])))
    (t/is (= :server-startup (get-in run [:termination :failure :stage])))
    (t/is (= {:case/a {:warmups 0 :repetitions 1}} (:remaining run)))
    (t/is (= [] (:attempts run)))
    (t/is (= 1 (:exit-code result)))))

(t/deftest ^:async failed-browser-relaunch-aborts-the-pending-cases
  (let [fake   (fake-browser {:new-page-failure #(= % 1) :launch-failure #(= % 2)})
        cases  [(collected :case/a :fresh) (collected :case/b :reuse) (collected :case/c :fresh)]
        result (await (execute fake cases (options fake (output-dir))))
        run    (saved-record result)]
    (t/is (= :incomplete (get-in run [:termination :reason])))
    (t/is (= [] (:attempts run)))
    (t/is (= [:browser-page :browser-launch] (mapv :stage (get-in run [:metadata :diagnostics :failures]))))
    (t/is (= {:case/a {:warmups 0 :repetitions 1}
              :case/b {:warmups 0 :repetitions 1}
              :case/c {:warmups 0 :repetitions 1}} (:remaining run)))
    (t/is (= 1 (:exit-code result)))))

(t/deftest ^:async interruption-exit-status-takes-precedence-over-finalization-errors
  (doseq [failure [:cleanup :record]
          [signal code] [["SIGINT" 130] ["SIGTERM" 143]]]
    (let [controller (js/AbortController.)
          out        (output-dir)
          base       (fake-browser {:controller controller :interruption (fn [_ _] signal)})
          close      (:close (:service base))
          fake       (assoc base :service
                            (assoc (:service base) :close
                                   (fn [owner]
                                     (close owner)
                                     (if (= failure :cleanup)
                                       (throw (js/Error. "cleanup rejected"))
                                       (let [record (path/join out "run.transit")]
                                         (fs/renameSync record (str record ".saved"))
                                         (fs/mkdirSync record))))))
          result     (await (execute fake [(collected :case/a :fresh)] (options fake out) controller))]
      (t/is (= code (:exit-code result)))
      (t/is (= :interrupted (get-in result [:run :termination :reason])))
      (t/is (= signal (get-in result [:run :termination :signal])))
      (t/is (seq (:errors result))))))

(t/deftest ^:async log-failure-still-closes-pages-browsers-and-hosts
  (let [out     (output-dir)
        base    (fake-browser {})
        prepare (:prepare (:service base))
        service (assoc (:service base)
                       :prepare
                       (fn [page request bounds]
                         (let [response (prepare page request bounds)]
                           (fs/renameSync (path/join out "run.log")
                                          (path/join out "run.log.saved"))
                           (fs/mkdirSync (path/join out "run.log"))
                           response)))
        fake    (assoc base :service service)
        result  (await (execute fake [(collected :case/a :fresh)]
                                (options fake out)))
        state   @(:state fake)
        host    (:host state)]
    (t/is (some errors/fatal? (:errors result)))
    (t/is (= 1 (:exit-code result)))
    (t/is (empty? (attempts-for (:run result) :case/a)))
    (t/is (= 1 (:close-pages state)))
    (t/is (= 1 (:closes state)))
    (t/is (false? (.-listening ^js (:server host))))))

(t/deftest ^:async storage-failure-survives-recovery-and-cleanup-error-takes-precedence
  (doseq [cleanup-fails [false true]]
    (let [out     (output-dir)
          file    (path/join out "run.log")
          saved   (str file ".saved")
          base    (fake-browser {})
          prepare (:prepare (:service base))
          close   (:close (:service base))
          service (assoc (:service base)
                         :prepare (fn [page request bounds]
                                    (let [response (prepare page request bounds)]
                                      (fs/renameSync file saved)
                                      (fs/mkdirSync file)
                                      response))
                         :close (fn [owner]
                                  (close owner)
                                  (fs/rmdirSync file)
                                  (fs/renameSync saved file)
                                  (when cleanup-fails
                                    (throw (js/Error. "cleanup rejected")))))
          fake    (assoc base :service service)
          result  (await (execute fake [(collected :case/a :fresh)] (options fake out)))
          run     (saved-record result)]
      (t/is (= 1 (:exit-code result)))
      (t/is (some errors/fatal? (:errors result)))
      (t/is (str/includes? (ex-message (first (:errors result)))
                           (if cleanup-fails "cleanup rejected" "cannot append benchmark log")))
      (t/is (= :incomplete (get-in run [:termination :reason])))
      (t/is (str/includes? (fs/readFileSync file "utf8") "Run incomplete")))))

(t/deftest ^:async cleanup-error-keeps-completed-attempt-evidence-and-exits-nonzero
  (let [base   (fake-browser {})
        fake   (assoc base :service (assoc (:service base) :close
                                           (fn [_] (throw (js/Error. "cleanup rejected")))))
        result (await (execute fake [(collected :case/a :fresh)] (options fake (output-dir))))
        run    (saved-record result)]
    (t/is (= [:completed] (mapv :outcome (:attempts run))))
    (t/is (= :completed (get-in run [:termination :reason])))
    (t/is (= :cleanup (get-in run [:termination :failure :stage])))
    (t/is (= "cleanup rejected" (ex-message (first (:errors result)))))
    (t/is (= 1 (:exit-code result)))))

(t/deftest ^:async interruption-during-shutdown-is-persisted
  (doseq [[signal code] [["SIGINT" 130] ["SIGTERM" 143]]]
    (let [controller (js/AbortController.)
          base       (fake-browser {})
          close      (:close (:service base))
          service    (assoc (:service base)
                            :close
                            (fn [owner]
                              (when-not (.-aborted (.-signal controller))
                                (.abort controller signal))
                              (close owner)))
          fake       (assoc base :service service)
          result     (await (execute fake [(collected :case/a :fresh)]
                                     (options fake (output-dir))
                                     controller))
          run        (:run result)]
      (t/is (= :interrupted (get-in run [:termination :reason])))
      (t/is (= signal (get-in run [:termination :signal])))
      (t/is (= code (:exit-code result)))
      (t/is (= run (saved-record result)))
      (t/is (= 1 (:closes @(:state fake)))))))

(t/deftest ^:async screenshots-record-files-and-failures-as-diagnostics
  (doseq [context [:fresh :reuse]]
    (let [fake   (fake-browser {})
          opts   (assoc (options fake (output-dir)) :screenshots true)
          result (await (execute fake [(collected :case/a context)] opts))
          run    (:run result)
          shot   (get-in run [:metadata :diagnostics :screenshots "case/a"])]
      (t/is (= :ok (:status shot)))
      (t/is (= "case-a.png" (:file shot)))
      (t/is (fs/existsSync (path/join (get-in result [:home :dir]) "case-a.png")))
      (t/is (= :completed (get-in run [:termination :reason])))
      (t/is (zero? (:exit-code result)))))
  (let [fake   (fake-browser {:screenshot-failure (constantly true)})
        opts   (assoc (options fake (output-dir)) :screenshots true)
        result (await (execute fake [(collected :case/a :fresh)] opts))
        run    (:run result)
        shot   (get-in run [:metadata :diagnostics :screenshots "case/a"])]
    (t/is (= :failed (:status shot)))
    (t/is (= "case-a.png" (:file shot)))
    (t/is (= :completed (get-in run [:termination :reason])))
    (t/is (zero? (:exit-code result)))))

(t/deftest ^:async metadata-records-build-and-controller-provenance-and-budgets
  (let [fake   (fake-browser {})
        opts   (assoc (options fake (output-dir))
                      :timeout-ms 1234 :build-timeout-ms 5678 :startup-timeout-ms 9012)
        result (await (execute fake [(collected :case/a :fresh)] opts))
        run    (:run result)
        diag   (get-in run [:metadata :diagnostics])]
    (t/is (= {:attempt-ms 1234} (get-in run [:plan :timeout-policy])))
    (t/is (= {:sha "build-revision" :dirty false} (get-in run [:metadata :git])))
    (t/is (= {:mode :release :default-features? true :features [] :cljs-profile-marks false}
             (get-in run [:metadata :build :functional])))
    (t/is (= "test-build" (get-in run [:metadata :build :provenance :build-id])))
    (t/is (true? (get-in run [:metadata :scored?])))
    (t/is (= "FakeBrowser/1" (get-in run [:metadata :environment :browser])))
    (t/is (= 1234 (:timeout-ms diag)))
    (t/is (= 5678 (:build-timeout-ms diag)))
    (t/is (= 9012 (:startup-timeout-ms diag)))
    (t/is (= 42 (:seed diag)))
    (t/is (= 0 (:warmups diag)))
    (t/is (= 1 (:repetitions diag)))
    (t/is (false? (:screenshots? diag)))
    (t/is (map? (:runner-git diag)))
    (t/is (= :unverified (:runtime-helper-identity diag)))))

(t/deftest ^:async run-metadata-copies-only-saved-build-environment-evidence
  (doseq [environment [::missing {} {"CFLAGS" "" "NODE_ENV" "recorded-build-value"}]]
    (let [fake       (fake-browser {})
          root       (artifacts/frontend-root)
          opts       (options fake (output-dir))
          build      (cond-> (selected-build (build-dir) "test-build")
                       (not= ::missing environment) (assoc-in [:manifest :environment] environment))
          home       (runs/create-home {:root root :run-id "test-run" :output-dir (:output-dir opts)})
          controller (js/AbortController.)
          executed   (await (run/execute root home build [(collected :case/a :fresh)]
                                         opts (.-signal controller)))
          saved      (saved-record executed)
          facts      (get-in saved [:metadata :build])]
      (t/is (zero? (:exit-code executed)))
      (t/is (= saved (:run executed)))
      (if (= ::missing environment)
        (t/is (not (contains? facts :environment)))
        (t/is (= environment (:environment facts)))))))

(t/deftest ^:async remove-deletes-the-used-build-after-the-record-is-saved
  (let [root   @temporary-root
        id     "removable-build"
        dir    (path/join (artifacts/managed-root root) "builds" id)
        _      (fs/mkdirSync dir #js {:recursive true})
        _      (fs/writeFileSync (path/join dir "index.html") "<!doctype html>")
        fake   (fake-browser {})
        out    (path/join @temporary-root "remove-run")
        home   (runs/create-home {:root root :run-id "remove-run"
                                  :output-dir out :remove true})
        result (await (run/execute root home (selected-build dir id)
                                   [(collected :case/a :fresh)]
                                   (assoc (options fake out) :remove true)
                                   (.-signal (js/AbortController.))))]
    (t/is (fs/existsSync (get-in result [:home :record-path])))
    (t/is (fs/existsSync dir))
    (t/is (not (fs/existsSync (path/join dir "index.html"))))
    (t/is (= :completed (get-in result [:run :termination :reason])))
    (t/is (zero? (:exit-code result)))))

(t/deftest ^:async failed-final-record-write-retains-the-selected-build
  (let [root       @temporary-root
        id         "retained-after-write-failure"
        dir        (path/join (artifacts/managed-root root) "builds" id)
        _          (fs/mkdirSync dir #js {:recursive true})
        _          (fs/writeFileSync (path/join dir "index.html") "<!doctype html>")
        out        (output-dir)
        record     (path/join out "run.transit")
        prior      (path/join out "run.before-finish.transit")
        base       (fake-browser {})
        close      (:close (:service base))
        fake       (assoc base :service
                          (assoc (:service base) :close
                                 (fn [owner]
                                   (close owner)
                                   ;; Make the final atomic rename fail after all
                                   ;; attempts have reached the saved record.
                                   (fs/renameSync record prior)
                                   (fs/mkdirSync record))))
        home       (runs/create-home {:root root :run-id "failed-write"
                                      :output-dir out :remove true})
        response   (await (run/execute root home (selected-build dir id)
                                       [(collected :case/a :fresh)]
                                       (assoc (options fake out) :remove true)
                                       (.-signal (js/AbortController.))))
        saved      (result/decode (fs/readFileSync prior "utf8"))]
    (t/is (= :running (get-in saved [:termination :reason])))
    (t/is (= 1 (count (:attempts saved))))
    (t/is (every? zero? (vals (get-in saved [:remaining :case/a]))))
    (t/is (seq (:errors response)))
    (t/is (= 1 (:exit-code response)))
    (t/is (= "<!doctype html>"
             (fs/readFileSync (path/join dir "index.html") "utf8")))))

(defn- prepare-comparison-build
  [{:keys [selection on-created]}]
  (let [dir   (build-dir)
        build (-> (selected-build dir (str (random-uuid)))
                  (assoc :manifest-path (path/join dir "build.transit"))
                  (assoc-in [:manifest :functional :default-features?] false)
                  (assoc-in [:manifest :functional :features] (:features selection))
                  (assoc-in [:manifest :provenance :feature-selection] selection))]
    (on-created build)
    (js/Promise.resolve build)))

(t/deftest ^:async feature-comparison-completes-with-managed-output
  (let [root     @temporary-root
        cases    [(collected :case/a :fresh)]
        fake     (fake-browser {:metrics {:first-render-ms
                                          (result/metric {:value 1 :unit :ms
                                                          :semantics :prepared-scene-to-full
                                                          :basis {:kind :attempt-total}
                                                          :zero-policy :positive})}})
        prepared {:plan (feature-plan/plan ["a"] {:dry-run true})
                  :cases cases
                  :output-dir nil
                  :options (dissoc (options nil nil) :browser)}
        response (await
                  (feature-comparison/execute
                   root prepared nil
                   {:services
                    {:prepare-build prepare-comparison-build
                     :collect-catalog (fn [_ _] cases)
                     :execute-run
                     (fn [root home selected cases opts signal]
                       (run/execute root home selected cases
                                    (assoc opts :browser (:service fake)) signal))}}))
        saved    (result/decode (fs/readFileSync (get-in response [:home :record-path]) "utf8"))]
    (t/is (= 0 (:exit-code response)))
    (t/is (= [:completed :completed] (mapv :status (:configurations saved))))
    (doseq [configuration (:configurations saved)]
      (let [run (result/decode (fs/readFileSync (get-in configuration [:run :record-path]) "utf8"))]
        (t/is (= :completed (get-in run [:termination :reason])))
        (t/is (= 1 (count (:attempts run))))))))

(t/deftest ^:async gpu-rejection-stops-later-matrix-configurations
  (doseq [[hardware-first close-fails] [[false false] [true false] [true true]]]
    (let [root     @temporary-root
          out      (output-dir)
          cases    [(collected :case/a :fresh)]
          software (assoc hardware-graphics :renderer "SwiftShader" :software true)
          prepared {:plan (feature-plan/plan ["a"] {:dry-run true})
                    :cases cases
                    :output-dir out
                    :options (assoc (dissoc (options nil out) :browser)
                                    :require-gpu true :repetitions 2)}
          response (await
                    (feature-comparison/execute
                     root prepared nil
                     {:services
                      {:prepare-build prepare-comparison-build
                       :collect-catalog (fn [_ _] cases)
                       :execute-run
                       (fn [root home selected cases opts signal]
                         (let [fake (fake-browser
                                     {:close-page-failure (when close-fails #(= % 2))
                                      :effective-graphics
                                      (fn [_ page-id]
                                        (if (and hardware-first (= page-id 1))
                                          hardware-graphics
                                          software))})]
                           (run/execute root home selected cases
                                        (assoc opts :browser (:service fake)) signal)))}}))
          saved    (result/decode (fs/readFileSync (get-in response [:home :record-path]) "utf8"))
          run      (result/decode (fs/readFileSync (get-in saved [:configurations 0 :run :record-path]) "utf8"))
          failure  (some #(when (= :gpu (:stage %)) %)
                         (get-in run [:metadata :diagnostics :failures]))]
      (t/is (= 1 (:exit-code response)))
      (t/is (= (if hardware-first 1 0) (count (:attempts run))))
      (t/is (= software (get-in failure [:partial :graphics])))
      (t/is (= (if close-fails :browser-close :gpu) (get-in run [:termination :failure :stage])))
      (t/is (= :failed (get-in saved [:configurations 0 :status])))
      (t/is (= :gpu (get-in saved [:configurations 0 :failure :stage])))
      (t/is (= :unattempted (get-in saved [:configurations 1 :status]))))))

(t/deftest ^:async comparison-stops-after-run-record-or-log-storage-fails
  (doseq [filename ["run.transit" "run.log"]]
    (let [executed (atom nil)
          root     @temporary-root
          out      (output-dir)
          cases    [(collected :case/a :fresh)]
          prepared {:plan (feature-plan/plan ["a"] {:dry-run true})
                    :cases cases
                    :output-dir out
                    :options (dissoc (options nil out) :browser)}
          response (await
                    (feature-comparison/execute
                     root prepared nil
                     {:services
                      {:prepare-build prepare-comparison-build
                       :collect-catalog (fn [_ _] cases)
                       :execute-run
                       (^:async fn [root home selected cases opts signal]
                         (let [base    (fake-browser {})
                               close   (:close (:service base))
                               file    (path/join (:dir home) filename)
                               service (assoc (:service base) :close
                                              (fn [owner]
                                                (close owner)
                                                (fs/renameSync file (str file ".saved"))
                                                (fs/mkdirSync file)))
                               outcome (await (run/execute root home selected cases
                                                           (assoc opts :browser service) signal))]
                           (reset! executed outcome)
                           outcome))}}))
          saved    (result/decode (fs/readFileSync (get-in response [:home :record-path]) "utf8"))
          evidence (path/join out "configs" "0"
                              (if (= filename "run.transit") "run.transit.saved" "run.transit"))]
      (t/is (= 1 (count (:attempts (result/decode (fs/readFileSync evidence "utf8"))))))
      (t/is (= 1 (:exit-code response)))
      (t/is (re-find #"Case case/a" (or (:report @executed) "")))
      (t/is (= :failed (get-in saved [:configurations 0 :status])))
      (t/is (= :unattempted (get-in saved [:configurations 1 :status]))))))
