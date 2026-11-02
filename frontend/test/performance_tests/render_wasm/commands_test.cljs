;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS SUBSIDIARY SL

(ns performance-tests.render-wasm.commands-test
  (:require
   [benches.render-wasm.builder :as builder]
   [benches.render-wasm.errors :as errors]
   [benches.render-wasm.runtime.camera :as camera]
   [benches.render-wasm.runtime.commands :as cmd :refer [defcommand]]
   [cljs.test :as t]))

(defcommand move
  "Moves the camera by the supplied offset."
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

(defn- context
  [clock]
  {:case {:id :commands/example}
   :scene (builder/scene {:seed 1})
   :view {:scale 1 :x 0 :y 0}
   :hooks {:now #(deref clock)
           :check (fn [])
           :render-sync #(vswap! clock + 7)}})

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
           [#(update % :hooks dissoc :render-sync) camera/render-sync* ::cmd/invalid-capabilities]
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
