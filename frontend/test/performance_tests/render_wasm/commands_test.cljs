;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS SUBSIDIARY SL

(ns performance-tests.render-wasm.commands-test
  (:require
   [app.common.render-wasm.wasm :as wasm]
   [benches.render-wasm.builder :as builder]
   [benches.render-wasm.errors :as errors]
   [benches.render-wasm.runtime.camera :as camera]
   [benches.render-wasm.runtime.commands :as cmd :refer [defcommand]]
   [cljs.test :as t]))

(defcommand move
  "Adds the supplied offsets to the command context's view."
  [rtx {:keys [dx dy]}]
  (-> rtx
      (update-in [:view :x] + dx)
      (update-in [:view :y] + dy)))

(defcommand selected-renders
  {:measured-variant false}
  [rtx]
  (-> rtx
      (camera/render-sync*)
      (move {:dx 10 :dy 5})
      (camera/render-sync)
      (camera/render-sync*)))

(defcommand unexpected-promise
  [rtx cause]
  (if cause
    (js/Promise.reject cause)
    (js/Promise.resolve rtx)))

(defcommand reject-offset
  [rtx offset]
  {:pre [(pos? offset)]}
  ((get-in rtx [:hooks :render-sync]))
  (update-in rtx [:view :x] + offset))

(defcommand replace-owner
  [rtx]
  (assoc-in rtx [:hooks :check] (fn [])))

(defcommand async-work
  {:async [complete fail]}
  [rtx work]
  (work rtx complete fail))

(defcommand selected-async
  {:async [complete fail] :measured-variant false}
  [rtx]
  (-> (camera/render-sync* rtx)
      (move {:dx 10 :dy 5})
      (camera/render-sync*)
      (.then complete)
      (.catch fail)))

(defn- context
  [clock]
  {:case {:id :commands/example}
   :scene (builder/scene {:seed 1})
   :view {:scale 1 :x 0 :y 0}
   :hooks {:now #(deref clock)
           :check (fn [])
           :frame #(js/Promise.resolve (vswap! clock + 16))
           :sleep #(do (vswap! clock + %) (js/Promise.resolve nil))
           :render (fn [_ _] (vswap! clock + 7) wasm/FRAME_TYPE_FULL)
           :render-sync #(vswap! clock + 7)
           :render-from-cache #(vswap! clock + 2)
           :set-view (fn [_])
           :set-view-start (fn [])
           :set-view-end #(vswap! clock + 3)}})

(t/deftest ^:async measurement-uses-core-time
  (let [clock (volatile! 0)
        rtx   (assoc-in (context clock) [:hooks :render-sync]
                        #(do
                           (vswap! clock + 7)
                           (js/queueMicrotask (fn [] (vswap! clock + 500)))))
        {:keys [outcome events context]}
        (await
         (cmd/execute
          rtx
          (fn [rtx]
            (let [input (-> (js/Promise.resolve rtx)
                            (.then (fn [rtx]
                                     (vswap! clock + 1000)
                                     rtx)))]
              (-> (camera/render-sync* input)
                  (.then (fn [rtx]
                           (vswap! clock + 2000)
                           rtx)))))))]
    (t/is (= :completed outcome))
    (t/is (= (:view rtx) (:view context)))
    (t/is (= [{:command ::camera/render-sync
               :metrics {:command/elapsed [7]}}]
             events))
    (t/is (= 3507 @clock))))

(t/deftest ^:async composite-preserves-view-and-selected-observations
  (doseq [promised [false true]]
    (let [clock (volatile! 0)
          cost  (volatile! 0)
          rtx   (assoc-in (context clock) [:hooks :render-sync]
                          #(vswap! clock + (vswap! cost + 7)))
          {:keys [outcome context events]}
          (await (cmd/execute rtx #(selected-renders
                                    (if promised (js/Promise.resolve %) %))))]
      (t/is (= :completed outcome))
      (t/is (= {:scale 1 :x 10 :y 5} (:view context)))
      (t/is (= [{:command ::camera/render-sync
                 :metrics {:command/elapsed [7]}}
                {:command ::camera/render-sync
                 :metrics {:command/elapsed [21]}}]
               events))
      (t/is (= 42 @clock)))))

(t/deftest measured-command-documentation
  (t/is (.startsWith (:doc (meta #'camera/render-sync*))
                     "Measured variant of `render-sync`."))
  (t/is (= "Measured variant of `unexpected-promise`."
           (:doc (meta #'unexpected-promise*)))))

(t/deftest ^:async zero-duration-is-a-raw-observation
  (let [rtx    (context (volatile! 0))
        result (await (cmd/execute rtx #(move* % {:dx 2 :dy 3})))]
    (t/is (= :completed (:outcome result)))
    (t/is (= {:scale 1 :x 2 :y 3} (get-in result [:context :view])))
    (t/is (= [{:command ::move :metrics {:command/elapsed [0]}}]
             (:events result)))))

(t/deftest ^:async async-completion-keeps-endpoints-and-raw-evidence
  (doseq [command [async-work async-work*]
          supplied [false true]]
    (let [clock    (volatile! 0)
          rtx      (context clock)
          metrics  {:renderer/call-elapsed [1 2 4]}
          trace    {:steps [1 2 3]}
          result   (await
                    (cmd/execute
                     rtx
                     (fn [rtx]
                       (command
                        (-> (js/Promise.resolve rtx)
                            (.then #(do (vswap! clock + 1000) %)))
                        (fn [rtx complete _fail]
                          (vswap! clock + 7)
                          (let [end      @clock
                                evidence {:metrics metrics :trace trace}
                                output   (assoc-in rtx [:view :x] 10)]
                            (if supplied
                              (-> (js/Promise.resolve nil)
                                  (.then (fn [_]
                                           (vswap! clock + 500)
                                           (complete output (assoc evidence :end-ms end)))))
                              (do
                                (complete output evidence)
                                (js/queueMicrotask #(vswap! clock + 500))))))))))]
      (t/is (= :completed (:outcome result)))
      (t/is (= 10 (get-in result [:context :view :x])))
      (t/is (= 1507 @clock))
      (t/is (= (if (= command async-work*)
                 [{:command ::async-work
                   :metrics (assoc metrics :command/elapsed [7])
                   :trace trace}]
                 [])
               (:events result)))))
  (let [result (await (cmd/execute (context (volatile! 0)) selected-async))]
    (t/is (= :completed (:outcome result)))
    (t/is (= {:scale 1 :x 10 :y 5} (get-in result [:context :view])))
    (t/is (= [::camera/render-sync ::camera/render-sync]
             (mapv :command (:events result))))))

(t/deftest ^:async async-completion-retains-invalid-numeric-observations
  (let [result (await
                (cmd/execute
                 (context (volatile! 0))
                 #(async-work* % (fn [rtx complete _fail]
                                   (complete rtx {:end-ms js/Infinity
                                                  :metrics {:sample/raw [-1 0 js/NaN js/Infinity]}})))))
        values (get-in result [:events 0 :metrics :sample/raw])]
    (t/is (= :completed (:outcome result)))
    (t/is (= [js/Infinity] (get-in result [:events 0 :metrics :command/elapsed])))
    (t/is (= [-1 0] (subvec values 0 2)))
    (t/is (js/Number.isNaN (nth values 2)))
    (t/is (= js/Infinity (nth values 3)))))

(t/deftest ^:async async-commands-use-the-first-terminal-signal
  (let [result (await
                (cmd/execute
                 (context (volatile! 0))
                 #(async-work* % (fn [rtx complete fail]
                                   (complete (assoc-in rtx [:view :x] 10))
                                   (complete nil)
                                   (fail (js/Error. "late failure"))
                                   (throw (js/Error. "late throw"))))))]
    (t/is (= :completed (:outcome result)))
    (t/is (= 10 (get-in result [:context :view :x])))
    (t/is (= [{:command ::async-work :metrics {:command/elapsed [0]}}] (:events result)))
    (t/is (= [:complete :fail :fail] (mapv :signal (:diagnostics result)))))
  (let [result (await
                (cmd/execute
                 (context (volatile! 0))
                 #(async-work* % (fn [rtx complete _fail]
                                   (js/queueMicrotask (fn [] (complete (assoc-in rtx [:view :x] 20))))
                                   rtx))))]
    (t/is (= :completed (:outcome result)))
    (t/is (= 20 (get-in result [:context :view :x]))))
  (let [unhandled (atom [])
        observe   #(swap! unhandled conj %)]
    (.on js/process "unhandledRejection" observe)
    (try
      (let [result (await
                    (cmd/execute
                     (context (volatile! 0))
                     #(async-work* % (fn [rtx complete _fail]
                                       (complete rtx)
                                       (js/Promise.reject (js/Error. "rejected after completion"))))))]
        (await (js/Promise. #(js/setImmediate %)))
        (t/is (= :completed (:outcome result)))
        (t/is (= [{:command ::async-work :metrics {:command/elapsed [0]}}] (:events result)))
        (t/is (= [] @unhandled)))
      (finally
        (.removeListener js/process "unhandledRejection" observe)))))

(t/deftest ^:async async-failures-retain-causes-and-earlier-events
  (doseq [mode [:throw :reject :fail]]
    (let [cause  (ex-info "renderer failed" {:partial {:slices [{:duration-ms 3}]}})
          result (await
                  (cmd/execute
                   (context (volatile! 0))
                   #(-> %
                        (camera/render-sync*)
                        (async-work* (fn [rtx complete fail]
                                       (case mode
                                         :throw (throw cause)
                                         :reject (js/Promise.reject cause)
                                         :fail (do (fail cause) (complete rtx))))))))]
      (t/is (= :failed (:outcome result)))
      (t/is (identical? cause (:error result)))
      (t/is (= [{:command ::camera/render-sync :metrics {:command/elapsed [7]}}]
               (:events result))))))

(t/deftest ^:async malformed-async-completion-fails-before-recording
  (doseq [[output evidence expected]
          [[identity {:end-ms nil} ::cmd/invalid-completion]
           [identity {:metrics {:sample/raw []}} ::cmd/invalid-completion]
           [identity {:metrics {:sample/raw ["bad"]}} ::cmd/invalid-completion]
           [identity {:metrics {:command/elapsed [123]}} ::cmd/invalid-completion]
           [#(assoc-in % [:view :scale] 0) {} ::cmd/invalid-context]
           [#(assoc-in % [:hooks :check] (fn [])) {} ::cmd/ownership-lost]]]
    (let [result (await (cmd/execute (context (volatile! 0))
                                     #(async-work* % (fn [rtx complete _fail]
                                                       (complete (output rtx) evidence)))))]
      (t/is (= :failed (:outcome result)))
      (t/is (= expected (:type (ex-data (:error result)))))
      (t/is (= [] (:events result))))))

(t/deftest ^:async async-completion-checks-the-owner-guard
  (doseq [cause [(ex-info "deadline expired" {:phase :deadline})
                 (ex-info "owner superseded" {::errors/stale true})]]
    (let [blocked (volatile! nil)
          rtx     (assoc-in (context (volatile! 0)) [:hooks :check]
                            #(when @blocked (throw @blocked)))
          result  (await (cmd/execute rtx #(async-work* % (fn [rtx complete _fail]
                                                            (vreset! blocked cause)
                                                            (complete rtx)))))]
      (t/is (= :failed (:outcome result)))
      (t/is (identical? cause (:error result)))
      (t/is (= [] (:events result))))))

(t/deftest ^:async late-completion-cannot-record-in-another-execution
  (let [callbacks (volatile! nil)
        pending   (volatile! nil)
        first-run (await
                   (cmd/execute
                    (context (volatile! 0))
                    (fn [rtx]
                      (vreset! pending
                               (.catch (async-work* rtx (fn [rtx complete fail]
                                                          (vreset! callbacks [rtx complete fail])))
                                       errors/as-failure))
                      rtx)))
        [rtx complete fail] @callbacks
        next-run (await (cmd/execute (:context first-run)
                                     (fn [next-rtx]
                                       (complete rtx)
                                       (fail (js/Error. "late failure"))
                                       (camera/render-sync* next-rtx))))
        cause    (await @pending)]
    (t/is (= :completed (:outcome first-run)))
    (t/is (= [] (:events first-run)))
    (t/is (= ::cmd/inactive-execution (:type (ex-data cause))))
    (t/is (= :completed (:outcome next-run)))
    (t/is (= [{:command ::camera/render-sync :metrics {:command/elapsed [7]}}]
             (:events next-run)))))

(t/deftest ^:async pan-commands-return-the-view-and-renderer-evidence
  (doseq [command [camera/pan camera/pan*]]
    (let [clock     (volatile! 0)
          view      (volatile! nil)
          remaining (volatile! [wasm/FRAME_TYPE_PARTIAL wasm/FRAME_TYPE_FULL])
          rtx       (update (context clock) :hooks merge
                            {:set-view #(vreset! view %)
                             :render (fn [_ _]
                                       (vswap! clock + 7)
                                       (let [frame-type (first @remaining)]
                                         (vswap! remaining rest)
                                         (when (= wasm/FRAME_TYPE_FULL frame-type)
                                           (js/queueMicrotask #(vswap! clock + 500)))
                                         frame-type))})
          options   {:dx 10 :dy 5 :steps 2 :settle-ms 5}
          result    (await (cmd/execute rtx #(command % options)))]
      (t/is (= :completed (:outcome result)))
      (t/is (= {:scale 1 :x 10 :y 5} @view (get-in result [:context :view])))
      (t/is (= 574 @clock))
      (if (= command camera/pan*)
        (do
          (t/is (= ::camera/pan (get-in result [:events 0 :command])))
          (t/is (= {:command/elapsed [74]
                    :renderer/call-elapsed [7 7]
                    :renderer/cache-call-elapsed [2 2]
                    :camera/finalization-to-full [33]}
                   (get-in result [:events 0 :metrics])))
          (t/is (= 74 (get-in result [:events 0 :trace :end-ms]))))
        (t/is (= [] (:events result))))
      (vreset! clock 0)
      (let [invalid (await (cmd/execute rtx #(command % (assoc options :steps 0))))]
        (t/is (= :failed (:outcome invalid)))
        (t/is (= ::cmd/invalid-arguments (:type (ex-data (:error invalid)))))
        (t/is (= [] (:events invalid)))
        (t/is (zero? @clock))))))

(t/deftest ^:async failed-work-retains-earlier-observations
  (let [unhandled (atom [])
        observe   #(swap! unhandled conj %)]
    (.on js/process "unhandledRejection" observe)
    (try
      (doseq [cause [nil (js/Error. "rejected core")]]
        (let [rtx    (context (volatile! 0))
              result (await (cmd/execute rtx #(-> %
                                                  (camera/render-sync*)
                                                  (unexpected-promise* cause))))]
          (t/is (= :failed (:outcome result)))
          (t/is (= ::cmd/async-result (:type (ex-data (:error result)))))
          (t/is (= [{:command ::camera/render-sync
                     :metrics {:command/elapsed [7]}}]
                   (:events result)))))
      (await (js/Promise. #(js/setImmediate %)))
      (t/is (= [] @unhandled))
      (finally
        (.removeListener js/process "unhandledRejection" observe)))))

(t/deftest ^:async execution-failures-preserve-causes-and-earlier-observations
  (doseq [cause [(js/Error. "renderer failed")
                 "renderer disconnected"
                 {:reason :renderer-disconnected}
                 (errors/fatal-error "recording failed" {} (js/Error. "storage"))]]
    (let [clock  (volatile! 0)
          rtx    (assoc-in (context clock) [:hooks :render-sync]
                           #(if (zero? @clock)
                              (vswap! clock + 7)
                              (throw cause)))
          result (await (cmd/execute rtx #(-> %
                                              (camera/render-sync*)
                                              (camera/render-sync*))))
          error  (:error result)]
      (t/is (= :failed (:outcome result)))
      (t/is (errors/failure? error))
      (if (errors/failure? cause)
        (t/is (identical? cause error))
        (t/is (= cause (:thrown (ex-data error)))))
      (t/is (= [{:command ::camera/render-sync
                 :metrics {:command/elapsed [7]}}]
               (:events result))))))

(t/deftest ^:async invalid-boundaries-fail-the-execution
  (doseq [[input command expected]
          [[#(assoc-in % [:view :scale] 0) camera/render-sync* ::cmd/invalid-context]
           [#(assoc % :module #js {}) camera/render-sync* ::cmd/invalid-context]
           [#(update % :hooks dissoc :render-sync) camera/render-sync* ::cmd/invalid-context]
           [#(update % :hooks dissoc :frame) camera/render-sync* ::cmd/invalid-context]
           [#(assoc-in % [:hooks :sleep] 0) camera/render-sync* ::cmd/invalid-context]
           [identity #(reject-offset* % -1) ::cmd/invalid-arguments]]]
    (let [clock     (volatile! 0)
          now-reads (volatile! 0)
          rtx       (assoc-in (context clock) [:hooks :now]
                              #(do (vswap! now-reads inc) @clock))
          result    (await (cmd/execute rtx #(command (input %))))]
      (t/is (= :failed (:outcome result)))
      (t/is (= expected (:type (ex-data (:error result)))))
      (t/is (= [] (:events result)))
      (t/is (zero? @now-reads))
      (t/is (zero? @clock)))))

(t/deftest ^:async commands-enforce-execution-ownership
  (let [rtx    (context (volatile! 0))
        result (await (cmd/execute rtx replace-owner*))
        cause  (await (.then (camera/render-sync* rtx) (fn [_] nil) identity))]
    (t/is (= :failed (:outcome result)))
    (t/is (= ::cmd/ownership-lost (:type (ex-data (:error result)))))
    (t/is (= [] (:events result)))
    (t/is (= ::cmd/missing-execution (:type (ex-data cause))))))

(t/deftest ^:async completed-context-starts-a-new-execution
  (let [first-result (await (cmd/execute (context (volatile! 0))
                                         #(move* % {:dx 10 :dy 5})))
        rtx          (:context first-result)
        late-error   (await (.then (camera/render-sync* rtx)
                                   (fn [_] nil)
                                   identity))
        next-result  (await (cmd/execute rtx camera/render-sync*))]
    (t/is (= ::cmd/inactive-execution (:type (ex-data late-error))))
    (t/is (= :completed (:outcome next-result)))
    (t/is (= {:scale 1 :x 10 :y 5} (get-in next-result [:context :view])))
    (t/is (= [{:command ::camera/render-sync
               :metrics {:command/elapsed [7]}}]
             (:events next-result)))))
