;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS SUBSIDIARY SL

(ns performance-tests.render-wasm.execution-test
  (:require
   ["fs" :as fs]
   ["node:module" :refer [createRequire]]
   ["node:stream" :as stream]
   ["node:timers/promises" :as timers]
   ["os" :as os]
   ["path" :as path]
   [app.common.transit :as transit]
   [benches.render-wasm.errors :as errors]
   [benches.render-wasm.node.cli.execution :as execution]
   [benches.render-wasm.node.cli.request :as request]
   [benches.render-wasm.node.cli.resolve :as cli]
   [benches.render-wasm.node.log :as log]
   [cljs.test :as t :include-macros true]
   [clojure.string :as str]))

(def temporary-root (atom nil))

(defn- within
  [promise]
  (let [timer (volatile! nil)]
    (-> (js/Promise.race
         #js [promise
              (js/Promise.
               (fn [_ reject]
                 (vreset! timer (js/setTimeout #(reject (js/Error. "Test stalled")) 5000))))])
        (.finally #(js/clearTimeout @timer)))))

(t/deftest ^:async real-signals-reach-the-callback-and-remover-restores-listeners
  (let [before-int  (.listenerCount js/process "SIGINT")
        before-term (.listenerCount js/process "SIGTERM")
        received    (atom [])
        next-signal (volatile! nil)
        remove      (execution/on-signals
                     (fn [signal]
                       (swap! received conj signal)
                       (@next-signal signal)))
        receive     (fn [signal]
                      (within
                       (js/Promise.
                        (fn [resolve _]
                          (vreset! next-signal resolve)
                          (.kill js/process (.-pid js/process) signal)))))]
    (try
      (t/is (= "SIGINT" (await (receive "SIGINT"))))
      (t/is (= "SIGTERM" (await (receive "SIGTERM"))))
      (t/is (= ["SIGINT" "SIGTERM"] @received))
      (finally
        (remove)))
    (remove)
    (t/is (= before-int (.listenerCount js/process "SIGINT")))
    (t/is (= before-term (.listenerCount js/process "SIGTERM")))))

(t/use-fixtures :each
  {:before #(reset! temporary-root (fs/mkdtempSync (path/join (os/tmpdir) "benchmark-execution-")))
   :after (fn []
            (log/configure {})
            (fs/rmSync @temporary-root #js {:recursive true :force true}))})

(defn- capture
  []
  (let [output (atom "")
        errors (atom "")
        writer (fn [text]
                 (stream/Writable.
                  #js {:write (fn [chunk _encoding done]
                                (swap! text str (.toString chunk))
                                (done))}))]
    {:stdout (writer output) :stderr (writer errors) :output output :errors errors}))

(defn- resolved
  [command options]
  (request/apply-defaults {:command command :console {:color :never} :options options}))

(defn- listeners
  []
  [(.listenerCount js/process "SIGINT") (.listenerCount js/process "SIGTERM")])


(t/deftest ^:async sync-and-async-handlers-publish-once-and-release-listeners
  (doseq [async [false true]]
    (let [local   (capture)
          before  (listeners)
          request (cli/parse-resolve ["compare" "--help" "--no-color"])
          handler (fn [_ {:keys [topic]}]
                    (log/info "Preparing help")
                    {:exit-code 0 :errors [] :log-path nil :report (cli/get-help topic)})
          handler (if async
                    (^:async fn [context options]
                      (await (timers/setTimeout 1))
                      (handler context options))
                    handler)]
      (t/is (= 0 (await (execution/execute request {"help" handler}
                                           (assoc local :get-root #(throw (js/Error. "root unavailable")))))))
      (t/is (= (str (cli/get-help "compare") "\n") @(:output local)))
      (t/is (= "Preparing help\n" @(:errors local)))
      (t/is (= before (listeners))))))

(t/deftest ^:async handlers-use-local-root-and-services-with-complete-options
  (let [local (capture)
        file  (path/join @temporary-root "input.txt")]
    (fs/writeFileSync file "saved input" "utf8")
    (t/is (= 0
             (await
              (execution/execute
               (cli/parse-resolve ["run" "--build" "ready" "--warmups" "0"])
               {"run" (fn [{:keys [get-root services]} {:keys [warmups build]}]
                        {:exit-code 0 :errors [] :log-path nil
                         :report (str (:id build) ":" warmups ":"
                                      ((:read-text services) (path/join (get-root) "input.txt")))})}
               (assoc local :get-root (constantly @temporary-root)
                      :services {:read-text #(fs/readFileSync % "utf8")})))))
    (t/is (= "ready:0:saved input\n" @(:output local)))))

(t/deftest ^:async malformed-and-unsupported-requests-fail-before-output-allocation
  (let [valid (resolved "run" {:build {:kind :existing :id "ready"}})]
    (doseq [[received field handlers]
            [[(dissoc valid :console) "console" nil]
             [(update valid :options dissoc :warmups) "warmups" nil]
             [(assoc (update valid :options dissoc :warmups)
                     :console {:level :warn :color :always}) "warmups" nil]
             [(assoc-in valid [:options :extra] true) "extra" nil]
             [(assoc-in valid [:console :color] :invalid) "color" nil]
             [(assoc-in valid [:console :stdout] #js {:write #(throw (js/Error. "untrusted stream"))})
              "stdout" nil]
             [valid "unsupported-command" {}]]]
      (let [local   (capture)
            output  (path/join @temporary-root (str (random-uuid)))
            before  (listeners)
            handler (fn [_ _]
                      (fs/mkdirSync output)
                      {:exit-code 0 :errors [] :log-path nil})]
        (t/is (= 1 (await (execution/execute received (or handlers {"run" handler})
                                             (assoc local :get-root #(fs/mkdirSync output))))))
        (t/is (str/includes? @(:errors local) field))
        (t/is (not (str/includes? @(:errors local) "\u001b[")))
        (t/is (not (fs/existsSync output)))
        (t/is (= before (listeners)))))))

(t/deftest ^:async received-command-policies-reject-before-output-allocation
  (doseq [[command options message]
          [["build" {:features ["stats"]} "diagnostic-features"]
           ["run" {:build {:kind :prepare :features ["profile"]}} "diagnostic-features"]
           ["ab" {:features ["profile-raf"] :dry-run true} "diagnostic-features"]
           ["ab" {:features ["cache"]} "confirmation-required"]
           ["ab" {:features ["cache"] :filter "  "} "confirmation-required"]
           ["ab" {:features ["a" "b"] :max-features 1 :dry-run true} "too-many-features"]]]
    (let [local   (capture)
          output  (path/join @temporary-root (str (random-uuid)))
          request (request/check (resolved command options))]
      (t/is (= 1 (await (execution/execute
                         request
                         {command (fn [_ _]
                                    (fs/mkdirSync output)
                                    {:exit-code 0 :errors [] :log-path nil})}
                         (assoc local :get-root #(fs/mkdirSync output))))))
      (t/is (str/includes? @(:errors local) message))
      (t/is (not (fs/existsSync output))))))

(t/deftest ^:async gpu-policy-uses-checked-console-and-dry-run-ab-needs-no-gpu
  (let [env (.-env js/process)
        old (.-PENPOT_GPU env)]
    (try
      (js-delete env "PENPOT_GPU")
      (doseq [[command options expected]
              [["run" {:build {:kind :existing :id "ready"} :require-gpu true} 1]
               ["ab" {:features ["cache"] :confirm true :require-gpu true} 1]
               ["ab" {:features ["cache"] :dry-run true :require-gpu true} 0]]]
        (let [local   (capture)
              request (assoc (resolved command options) :console {:level :warn :color :always})]
          (t/is (= expected
                   (await (execution/execute
                           request {command (fn [_ _] {:exit-code 0 :errors [] :log-path nil
                                                       :report "dry plan"})} local))))
          (if (zero? expected)
            (t/is (= "dry plan\n" @(:output local)))
            (do
              (t/is (str/includes? @(:errors local) "PENPOT_GPU=1"))
              (t/is (str/includes? @(:errors local) "\u001b["))))))
      (finally
        (if (nil? old) (js-delete env "PENPOT_GPU") (set! (.-PENPOT_GPU env) old))))))

(t/deftest ^:async thrown-and-rejected-failures-use-checked-console-and-release-listeners
  (doseq [[handler expected] [[(fn [_ _] (throw (js/Error. "failed work")))
                               "✖ failed work\n"]
                              [(^:async fn [_ _]
                                 (await (timers/setTimeout 1))
                                 (throw (js/Error. "failed work")))
                               "✖ failed work\n"]
                              [(^:async fn [_ _]
                                 (await (timers/setTimeout 1))
                                 (throw (errors/fatal-error "failed work" {} (js/Error. "storage"))))
                               "✖ failed work\n  fatal  true\n"]]]
    (let [local   (capture)
          before  (listeners)
          request (assoc (resolved "help" {:topic nil}) :console {:level :warn :color :never})]
      (t/is (= 1 (await (execution/execute
                         request {"help" (fn [context options]
                                           (log/info "hidden progress")
                                           (handler context options))} local))))
      (t/is (= expected @(:errors local)))
      (t/is (= before (listeners))))))

(t/deftest ^:async final-publication-uses-the-outcomes-log-and-numeric-status
  (let [local (capture)
        file  (path/join @temporary-root "command.log")]
    (t/is (= 7 (await (execution/execute
                       (resolved "help" {:topic nil})
                       {"help" (fn [_ _] {:exit-code 7 :errors [] :log-path file :report "saved report"})}
                       local))))
    (t/is (= "saved report\n" (fs/readFileSync file "utf8")))
    (t/is (= "saved report\n" @(:output local)))
    (let [silent (capture)]
      (t/is (= 0 (await (execution/execute
                         (resolved "help" {:topic nil})
                         {"help" (fn [_ _] {:exit-code 0 :errors [] :log-path nil})} silent))))
      (t/is (= "" @(:output silent))))))

(t/deftest ^:async final-errors-share-the-command-log-and-print-one-tail
  (let [local (capture)
        file  (path/join @temporary-root "failures.log")]
    (fs/writeFileSync file "saved evidence\n" "utf8")
    (t/is (= 1 (await (execution/execute
                       (resolved "help" {:topic nil})
                       {"help" (fn [_ _]
                                 {:exit-code 1 :log-path file
                                  :errors [(js/Error. "first failure") (js/Error. "second failure")]})}
                       local))))
    (t/is (= 2 (count (re-seq #"✖" @(:errors local)))))
    (t/is (= 1 (count (re-seq #"  tail\n" @(:errors local)))))
    (t/is (str/includes? @(:errors local) "saved evidence"))
    (t/is (str/includes? (fs/readFileSync file "utf8") "second failure"))))

(t/deftest ^:async publication-failure-fails-the-command-and-preserves-interruption-status
  (doseq [[code expected] [[0 1] [130 130] [143 143]]]
    (let [local   (capture)
          written (atom [])
          broken  #js {:write (fn [text]
                                (swap! written conj text)
                                (throw (js/Error. "stdout closed")))}]
      (t/is (= expected (await (execution/execute
                                (resolved "help" {:topic nil})
                                {"help" (fn [_ _] {:exit-code code :errors [] :log-path nil :report "result"})}
                                (assoc local :stdout broken)))))
      (t/is (= ["result\n"] @written))
      (t/is (= "✖ stdout closed\n" @(:errors local)))))
  (let [local (capture)]
    (t/is (= 1 (await (execution/execute
                       (resolved "help" {:topic nil})
                       {"help" (fn [_ _] {:exit-code 0 :errors [] :log-path @temporary-root :report "result"})}
                       local))))
    (t/is (str/includes? @(:errors local) "cannot append benchmark log"))
    (t/is (str/includes? @(:errors local) "Could not append log"))))

(t/deftest ^:async real-signals-retain-partial-evidence-and-fatal-errors
  (doseq [[first-signal second-signal code] [["SIGINT" "SIGTERM" 130] ["SIGTERM" "SIGINT" 143]]]
    (let [local   (capture)
          before  (listeners)
          file    (path/join @temporary-root first-signal)
          handler (fn [{:keys [signal]} _]
                    (js/Promise.
                     (fn [resolve reject]
                       (.addEventListener
                        signal "abort"
                        (fn []
                          (-> (timers/setTimeout 1)
                              (.then (fn []
                                       (t/is (.emit js/process second-signal))
                                       (fs/writeFileSync file "partial evidence" "utf8")
                                       (resolve {:exit-code 1 :log-path nil :report "partial report"
                                                 :errors [(js/Error. "aborted work")
                                                          (errors/fatal-error "fatal storage" {} (js/Error. "disk"))]})))
                              (.catch reject)))
                        #js {:once true})
                       (.kill js/process (.-pid js/process) first-signal))))]
      (t/is (= code (await (within (execution/execute (resolved "help" {:topic nil})
                                                      {"help" handler} local)))))
      (t/is (= "partial evidence" (fs/readFileSync file "utf8")))
      (t/is (= "partial report\n" @(:output local)))
      (t/is (= "✖ fatal storage\n  fatal  true\n" @(:errors local)))
      (t/is (= before (listeners))))))

(t/deftest failure-adapters-preserve-usage-validation-and-decoder-diagnostics
  (doseq [[get-cause message]
          [[#(cli/parse-resolve ["purge"]) "Usage: runner purge"]
           [#(request/check (update (resolved "run" {:build {:kind :existing :id "ready"}})
                                    :options dissoc :warmups)) "warmups"]
           [#(transit/decode-str "invalid transit") nil]
           [#((createRequire (path/join @temporary-root "entry.cjs"))
              (path/join @temporary-root "missing.cjs")) "missing.cjs"]]]
    (let [local (capture)
          cause (try
                  (get-cause)
                  (t/is false "expected a boundary failure")
                  (catch :default cause cause))]
      (t/is (= 1 (execution/fail cause {} local)))
      (t/is (str/includes? @(:errors local) (or message (errors/message-of cause))))
      (t/is (= 1 (count (re-seq #"✖" @(:errors local))))))))

(t/deftest ^:async failure-text-escapes-controls-and-survives-a-throwing-printer
  (let [local   (capture)
        hostile (reify IPrintWithWriter
                  (-pr-writer [_ _ _] (throw (js/Error. "cannot print path"))))
        failure (ex-info (str "work failed\n\u001b" (apply str (repeat 2000 "x")))
                         {:path hostile :reason "reason\u0007\u007f\u0085"
                          :mismatches [{:field [:metric (keyword "metric" "unsafe\u001b")]}]})]
    (t/is (= 1 (await (execution/execute
                       (resolved "help" {:topic nil})
                       {"help" (fn [_ _] (throw failure))} local))))
    (t/is (str/includes? @(:errors local) "work failed"))
    (t/is (str/includes? @(:errors local) "unrenderable value"))
    (t/is (str/includes? @(:errors local) "\\u001b"))
    (t/is (str/includes? @(:errors local) "\\u0007"))
    (t/is (every? #(<= (count %) 1100) (str/split-lines @(:errors local))))
    (t/is (every? #(let [code (.charCodeAt % 0)]
                     (or (= code 10) (and (>= code 32) (or (< code 127) (> code 159)))))
                  (seq @(:errors local))))))

(t/deftest ^:async error-reporting-failure-uses-a-bounded-fallback
  (doseq [[code expected] [[1 1] [130 130]]]
    (let [local    (capture)
          fallback (atom [])
          broken   #js {:write (fn [text]
                                 (if (str/starts-with? text "Could not report")
                                   (swap! fallback conj text)
                                   (throw (js/Error. (str "\u001b" (apply str (repeat 1100 "😀")))))))}]
      (t/is (= expected (await (execution/execute
                                (resolved "help" {:topic nil})
                                {"help" (fn [_ _] {:exit-code code :log-path nil :errors [(js/Error. "work failed")]})}
                                (assoc local :stderr broken)))))
      (t/is (= 1 (count @fallback)))
      (t/is (< (count (first @fallback)) 1100))
      (t/is (str/includes? (first @fallback) "\\u001b"))
      (t/is (not (re-find #"[\uD800-\uDBFF](?![\uDC00-\uDFFF])" (first @fallback))))))
  (let [broken #js {:write #(throw (js/Error. "closed"))}]
    (t/is (= 1 (await (execution/execute
                       (resolved "help" {:topic nil})
                       {"help" (fn [_ _] {:exit-code 0 :errors [] :log-path nil :report "result"})}
                       {:stdout broken :stderr broken}))))))
