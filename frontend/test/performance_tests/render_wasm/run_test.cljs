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
   [benches.render-wasm.node.artifacts :as artifacts]
   [benches.render-wasm.node.run :as run]
   [benches.render-wasm.result :as result]
   [cljs.test :as t :include-macros true]
   [clojure.string :as str]))

(def ^:private temporary-root (atom nil))

(t/use-fixtures :each
  {:before #(reset! temporary-root
                    (fs/mkdtempSync (path/join (os/tmpdir) "penpot-render-run-")))
   :after #(fs/rmSync @temporary-root #js {:recursive true :force true})})

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
              :schema-version 1
              :id id
              :status :ready
              :functional {:mode :release
                           :default-features? true
                           :features []}
              :provenance {:git {:sha "build-revision" :dirty false}}}})

(defn- collected
  [id context]
  {:id id
   :scene :case
   :scene-version 1
   :scene-description "test scene"
   :scene-seed 1
   :params {}
   :view {:scale 1 :x 0 :y 0}
   :context context
   :completion :render-full
   :batch-size 1
   :preparation {:version 1}})

(defn- next-count
  [state key]
  (inc (get @state key 0)))

(defn- check-page
  [page]
  (when-not (and (map? page) (some? (:id page)))
    (throw (ex-info "browser service requires the page object"
                    {:phase :browser-page-startup}))))

(defn- fake-browser
  [{:keys [launch-failure new-page-failure close-page-failure
           prepare-failure run-failure screenshot-failure
           interruption controller]}]
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
                               :fingerprint {:encoding-version 1
                                             :algorithm :sha-256
                                             :digest (apply str (repeat 64 "a"))}
                               :effective-graphics {}
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

            {:status "ok" :metrics {} :trace {}})))
      :screenshot
      (fn [page file _]
        (check-page page)
        (when (and screenshot-failure (screenshot-failure file))
          (throw (ex-info "screenshot failed" {:phase :screenshot})))
        (fs/writeFileSync file "png"))}}))

(defn- options
  [fake out]
  {:run-id "test-run"
   :output-dir out
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
                (selected-build (build-dir) "test-build")
                cases opts (.-signal controller) nil nil)))

(defn- attempts-for
  [run case-id]
  (filterv #(= case-id (:case-id %)) (:attempts run)))

(defn- preparations-for
  [run case-id]
  (filterv #(= case-id (:case-id %)) (:preparations run)))

(defn- saved-record
  [result]
  (result/decode (fs/readFileSync (get-in result [:home :record-path]) "utf8")))

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
  (let [fake   (fake-browser {:run-failure (fn [case-id n]
                                             (when (and (= case-id :case/fresh) (= n 1))
                                               :throw))})
        opts   (assoc (options fake (output-dir)) :repetitions 2)
        result (await (execute fake [(collected :case/fresh :fresh)] opts))
        run    (:run result)
        tries  (attempts-for run :case/fresh)]
    (t/is (= 2 (count tries)))
    (t/is (= [:failed :completed] (mapv :outcome tries)))
    (t/is (every? zero? (vals (get-in run [:remaining :case/fresh]))))
    (t/is (= :failed (get-in run [:termination :reason])))
    (t/is (= 1 (:exit-code result)))
    (t/is (= 2 (count (preparations-for run :case/fresh))))))

(t/deftest ^:async reuse-preparation-failure-leaves-slots-and-continues-to-the-next-case
  (let [fake   (fake-browser {:prepare-failure #(when (= % :case/reuse) :response)})
        opts   (assoc (options fake (output-dir)) :screenshots true)
        result (await (execute fake [(collected :case/reuse :reuse)
                                     (collected :case/fresh :fresh)] opts))
        run    (:run result)
        shots  (get-in run [:metadata :diagnostics :screenshots])]
    (t/is (empty? (attempts-for run :case/reuse)))
    (t/is (= {:warmups 0 :repetitions 1} (get-in run [:remaining :case/reuse])))
    (t/is (= 1 (count (attempts-for run :case/fresh))))
    (t/is (= :incomplete (get-in run [:termination :reason])))
    (t/is (= 1 (:exit-code result)))
    (t/is (= :skipped (get-in shots ["case/reuse" :status])))
    (t/is (= :ok (get-in shots ["case/fresh" :status])))
    (let [failure (last (get-in run [:metadata :diagnostics :failures]))]
      (t/is (= :preparation (:stage failure)))
      (t/is (= :case/reuse (:case-id failure)))
      (t/is (= "scene-build" (:phase failure))))))

(t/deftest ^:async fresh-preparation-failure-consumes-its-slot
  (let [fake   (fake-browser {:prepare-failure #(when (= % :case/fresh) :throw)})
        result (await (execute fake [(collected :case/fresh :fresh)]
                               (options fake (output-dir))))
        run    (:run result)
        tries  (attempts-for run :case/fresh)]
    (t/is (= 1 (count tries)))
    (t/is (= :failed (:outcome (first tries))))
    (t/is (nil? (:preparation-id (first tries))))
    (t/is (= "scene-build" (get-in (first tries) [:failure :phase])))
    (t/is (empty? (preparations-for run :case/fresh)))
    (t/is (every? zero? (vals (get-in run [:remaining :case/fresh]))))
    (t/is (= :failed (get-in run [:termination :reason])))
    (t/is (= 1 (:exit-code result)))))

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

(t/deftest ^:async sigint-and-sigterm-persist-partial-interrupted-records
  (doseq [[signal code] [["SIGINT" 130] ["SIGTERM" 143]]]
    (let [controller (js/AbortController.)
          fake       (fake-browser {:controller controller
                                    :interruption (fn [case-id n]
                                                    (when (and (= case-id :case/a) (= n 1))
                                                      signal))})
          opts       (assoc (options fake (output-dir)) :screenshots true)
          result     (await (execute fake [(collected :case/a :fresh)
                                           (collected :case/b :fresh)]
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
    (t/is (some? (:error result)))
    (t/is (= 1 (:exit-code result)))
    (t/is (empty? (attempts-for (:run result) :case/a)))
    (t/is (= 1 (:close-pages state)))
    (t/is (= 1 (:closes state)))
    (t/is (false? (.-listening ^js (:server host))))))

(t/deftest ^:async interruption-during-shutdown-is-persisted
  (doseq [[signal code] [["SIGINT" 130] ["SIGTERM" 143]]]
    (let [controller (js/AbortController.)
          base       (fake-browser {})
          close      (:close (:service base))
          service    (assoc (:service base)
                            :close
                            (fn [owner]
                              (when-not (.-aborted (.-signal controller))
                                (.abort controller
                                        (ex-info "run interrupted"
                                                 {:type ::interrupted :signal signal})))
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
  (let [fake   (fake-browser {})
        opts   (assoc (options fake (output-dir)) :screenshots true)
        result (await (execute fake [(collected :case/a :fresh)] opts))
        run    (:run result)
        shot   (get-in run [:metadata :diagnostics :screenshots "case/a"])]
    (t/is (= :ok (:status shot)))
    (t/is (= "case-a.png" (:file shot)))
    (t/is (fs/existsSync (path/join (get-in result [:home :dir]) "case-a.png")))
    (t/is (= :completed (get-in run [:termination :reason])))
    (t/is (zero? (:exit-code result))))
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
    (t/is (= {:mode :release :default-features? true :features []}
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

(t/deftest ^:async remove-deletes-the-used-build-after-the-record-is-saved
  (let [root   @temporary-root
        id     "removable-build"
        dir    (path/join (artifacts/managed-root root) "builds" id)
        _      (fs/mkdirSync dir #js {:recursive true})
        _      (fs/writeFileSync (path/join dir "index.html") "<!doctype html>")
        fake   (fake-browser {})
        out    (path/join @temporary-root "remove-run")
        result (await (run/execute root (selected-build dir id)
                                   [(collected :case/a :fresh)]
                                   (assoc (options fake out) :remove? true)
                                   (.-signal (js/AbortController.)) nil nil))]
    (t/is (fs/existsSync (get-in result [:home :record-path])))
    (t/is (not (fs/existsSync dir)))
    (t/is (= :completed (get-in result [:run :termination :reason])))
    (t/is (zero? (:exit-code result)))))
