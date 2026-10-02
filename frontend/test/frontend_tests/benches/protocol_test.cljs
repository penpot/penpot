;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS SUBSIDIARY SL

(ns frontend-tests.benches.protocol-test
  "Tests drain, restore and interact with an injected clock and renderer.
  Browser entry tests cover load and warm pan with a fake module."
  (:require
   [app.common.render-wasm.wasm :as wasm]
   [benches.render-wasm.runtime.protocol :as protocol]
   [cljs.test :as t :include-macros true]))

(defn- fake-clock
  "Controllable ms clock. `advance!` moves it; tests drive time by hand."
  [start]
  (let [t (atom start)]
    {:now      (fn [] @t)
     :advance! (fn [ms] (swap! t + ms) @t)}))

(defn- drain-hooks
  "Fake hooks driving `:render` through `script` (a vector of frame types
  returned in order), recording observed flags in `calls`. `:frame`
  advances 16 ms per rAF; `:sleep` advances the clock without yielding."
  [{:keys [clock script calls check]}]
  (let [remaining (atom (vec script))]
    {:now               (:now clock)
     :frame             (fn []
                          ((:advance! clock) 16)
                          (js/Promise.resolve ((:now clock))))
     :sleep             (fn [ms]
                          ((:advance! clock) ms)
                          (js/Promise.resolve nil))
     :check             (or check (fn [] nil))
     :render            (fn [_timestamp flags]
                          (swap! calls conj flags)
                          (let [next (first @remaining)]
                            (swap! remaining rest)
                            next))
     :render-from-cache (fn [] 0)
     :set-view          (fn [_view] nil)
     :set-view-start    (fn [] nil)
     :set-view-end      (fn [] nil)}))

(t/deftest immediate-full-reports-equal-boundaries
  (t/async done
    (let [clock (fake-clock 1000)
          calls (atom [])
          hooks (drain-hooks {:clock clock :script [2] :calls calls})]
      (-> (protocol/drain hooks {:flags 0 :origin 1000 :immediate true})
          (.then (fn [{:keys [slices viewport-ready-ms full-ms]}]
                   (t/is (= 1 (count slices)))
                   (t/is (= {:timestamp   1000
                             :flags       0
                             :frame-type  2
                             :duration-ms 0}
                            (first slices)))
                   (t/is (= full-ms viewport-ready-ms)
                         "immediate Full supplies both boundaries")
                   (done)))
          (.catch (fn [cause]
                    (t/is false (str "must resolve, threw: " cause))
                    (done)))))))

(t/deftest progressive-frames-propagate-flags
  (t/async done
    (let [clock (fake-clock 0)
          calls (atom [])
          hooks (drain-hooks {:clock clock :script [1 3 2] :calls calls})]
      (-> (protocol/drain hooks {:flags 0 :origin 0 :immediate false})
          (.then (fn [{:keys [slices viewport-ready-ms full-ms]}]
                   (t/is (= [0 1 3] @calls)
                         "the previous frame type becomes the next flags")
                   (t/is (= [1 3 2] (mapv :frame-type slices)))
                   (t/is (< viewport-ready-ms full-ms)
                         "ViewportReady lands before Full")
                   (t/is (= (:timestamp (nth slices 1)) (+ 16 16))
                         "later frames wait for rAF")
                   (done)))
          (.catch (fn [cause]
                    (t/is false (str "must resolve, threw: " cause))
                    (done)))))))

(t/deftest partial-jumps-straight-to-full
  (t/async done
    (let [clock (fake-clock 0)
          calls (atom [])
          hooks (drain-hooks {:clock clock :script [1 2] :calls calls})]
      (-> (protocol/drain hooks {:flags 0 :origin 0 :immediate true})
          (.then (fn [{:keys [slices viewport-ready-ms full-ms]}]
                   (t/is (= [0 1] @calls))
                   (t/is (= [1 2] (mapv :frame-type slices)))
                   (t/is (= viewport-ready-ms full-ms)
                         "Full as the first completion supplies both boundaries")
                   (done)))
          (.catch (fn [cause]
                    (t/is false (str "must resolve, threw: " cause))
                    (done)))))))

(t/deftest unknown-frame-types-reject
  (t/async done
    ;; One async context chaining the bad values: a `t/async` inside
    ;; `doseq` would never run (the doseq returns nil to the runner).
    (letfn [(step [remaining]
              (if (empty? remaining)
                (done)
                (let [bad   (first remaining)
                      clock (fake-clock 0)
                      calls (atom [])
                      hooks (drain-hooks {:clock clock :script [bad] :calls calls})]
                  (-> (protocol/drain hooks {:flags 0 :origin 0 :immediate true})
                      (.then (fn [_]
                               (t/is false (str "must reject for frame type " bad))
                               (done)))
                      (.catch (fn [cause]
                                (t/is (= :benches.render-wasm.runtime.protocol/unexpected-frame-type
                                         (:type (ex-data cause)))
                                      (str "frame type " bad))
                                (step (rest remaining))))))))]
      (step [0 7 js/NaN]))))

(t/deftest cancellation-stops-further-renders
  (t/async done
    (let [clock  (fake-clock 0)
          calls  (atom [])
          fired  (atom 0)
          hooks  (drain-hooks {:clock  clock
                               :script [1 1 1 1]
                               :calls  calls
                               :check  (fn []
                                         (swap! fired inc)
                                         (when (> @fired 2)
                                           (throw (ex-info "canceled" {:phase "test"}))))})]
      (-> (protocol/drain hooks {:flags 0 :origin 0 :immediate true})
          (.then (fn [_]
                   (t/is false "must reject once check throws")
                   (done)))
          (.catch (fn [cause]
                    (t/is (= "canceled" (ex-message cause)))
                    (t/is (= 1 (count @calls)) "cancellation stops before the next render")
                    (t/is (= 3 @fired) "guard fires once before and once after the render")
                    (done)))))))

(t/deftest restore-drains-with-sync-tiles-immediately
  (t/async done
    (let [clock (fake-clock 500)
          calls (atom [])
          order (atom [])
          hooks (assoc (drain-hooks {:clock clock :script [1 3 2] :calls calls})
                       :set-view-start (fn [] (swap! order conj :start) nil)
                       :set-view (fn [view]
                                   (swap! order conj [:view view])
                                   nil)
                       :set-view-end (fn [] (swap! order conj :end) nil))
          view  {:scale 1 :x 10 :y 20}]
      (-> (protocol/restore hooks view)
          (.then (fn [{:keys [slices]}]
                   (t/is (= [:start [:view view] :end] @order)
                         "camera resets before the drain")
                   (t/is (= [4 1 3] @calls)
                         "restore drains with SyncTiles first")
                   (t/is (= [1 3 2] (mapv :frame-type slices)))
                   (done)))
          (.catch (fn [cause]
                    (t/is false (str "must resolve, threw: " cause))
                    (done)))))))

(t/deftest interact-times-gesture-settle-and-final-drain
  (t/async done
    (let [clock    (fake-clock 0)
          renders  (atom [])
          cached   (atom [])
          views    (atom [])
          hooks    {:now               (:now clock)
                    :frame             (fn []
                                         ((:advance! clock) 16)
                                         (js/Promise.resolve ((:now clock))))
                    :sleep             (fn [ms]
                                         ((:advance! clock) ms)
                                         (js/Promise.resolve nil))
                    :check             (fn [] nil)
                    :render            (fn [_timestamp flags]
                                         (swap! renders conj flags)
                                         2)
                    :render-from-cache (fn []
                                         (swap! cached conj true)
                                         ((:advance! clock) 3)
                                         nil)
                    :set-view          (fn [view]
                                         (swap! views conj view)
                                         nil)
                    :set-view-start    (fn [] nil)
                    :set-view-end      (fn []
                                         ((:advance! clock) 5)
                                         nil)}
          frames   [{:scale 1 :x 0 :y 0} {:scale 1 :x 100 :y 0}]]
      (-> (protocol/interact hooks {:frames frames :settle-ms 100})
          (.then (fn [m]
                   (t/is (= frames @views) "every gesture frame sets the view")
                   (t/is (= 2 (count @cached)) "one cached preview per frame")
                   (t/is (= 2 (count (:cached-slices m))))
                   (t/is (every? #(contains? % :timestamp) (:cached-slices m)))
                   (t/is (every? #(contains? % :duration-ms) (:cached-slices m))
                         "cached slices keep their own shape")
                   (t/is (= 100 (:settling-requested-ms m)))
                   (t/is (= 100 (:settling-actual-ms m))
                         "fake sleep advances the clock exactly")
                   (t/is (= 5 (:set-view-end-ms m)))
                   (t/is (= [4] @renders) "final drain starts with SyncTiles")
                   (t/is (>= (:time-to-full-ms m) (:set-view-end-ms m))
                         "finalization includes _set_view_end")
                   (t/is (= (:time-to-viewport-ready-ms m) (:time-to-full-ms m))
                         "immediate final drain gives equal boundaries")
                   (t/is (= 108 (:last-input-to-full-ms m))
                         "last input at 35, Full at 143: settle + set_view_end + drain")
                   (t/is (= [wasm/FRAME_TYPE_FULL] (mapv :frame-type (:slices m)))
                         "frame is full")
                   (done)))
          (.catch (fn [cause]
                    (t/is false (str "must resolve, threw: " cause))
                    (done)))))))

(t/deftest camera-session-chains-promises-and-records-authored-sleep
  (t/async done
    (let [clock   (fake-clock 0)
          views   (atom [])
          events  (atom [])
          hooks   (assoc (drain-hooks {:clock clock :script [2] :calls (atom [])})
                         :frame (fn []
                                  (swap! events conj :frame)
                                  ((:advance! clock) 16)
                                  (js/Promise.resolve ((:now clock))))
                         :set-view (fn [view]
                                     (swap! views conj view)
                                     ((:advance! clock) 2))
                         :render-from-cache (fn []
                                              (swap! events conj :cached)
                                              ((:advance! clock) 3))
                         :set-view-end (fn []
                                         (swap! events conj :end)
                                         ((:advance! clock) 5)))
          base    {:scale 1 :x 0 :y 0}
          target  {:scale 2 :x 100 :y 40}]
      (-> (protocol/start-camera! {:hooks hooks :view base} {:settle-ms 100})
          (protocol/animate-view! {:to target :steps 3})
          (protocol/sleep! 25)
          (protocol/finish-camera!)
          (.then (fn [m]
                   (t/is (apply < (map :x @views))
                         "intermediate camera views advance toward the target")
                   (t/is (= target (last @views)))
                   (t/is (= [:frame :cached :frame :cached :frame :cached :end] @events))
                   (t/is (= 3 (count (:cached-slices m))))
                   (t/is (= [5 5 5] (mapv :view-and-preview-ms (:cached-slices m)))
                         "call span also includes the view update")
                   (t/is (= [3 3 3] (mapv :duration-ms (:cached-slices m))))
                   (t/is (= [{:requested-ms 25 :actual-ms 25}] (:authored-sleeps m)))
                   (t/is (= 88 (:active-ms m))
                         "three rAF waits, view/cache calls and authored pause")
                   (t/is (= 100 (:settling-actual-ms m)))
                   (t/is (= 193 (:interact-ms m))
                         "active, settle and camera end are one span")
                   (done)))
          (.catch (fn [cause]
                    (t/is false (str "must resolve, threw: " cause))
                    (done)))))))

(t/deftest camera-session-rejects-hook-errors
  (t/async done
    (let [clock (fake-clock 0)
          hooks (assoc (drain-hooks {:clock clock :script [2] :calls (atom [])})
                       :render-from-cache (fn [] (throw (ex-info "cache failed" {:phase "preview"}))))]
      (-> (protocol/start-camera! {:hooks hooks :view {:scale 1 :x 0 :y 0}}
                                  {:settle-ms 100})
          (protocol/preview-view! {:scale 1 :x 10 :y 0})
          (protocol/finish-camera!)
          (.then (fn [_]
                   (t/is false "preview error must reject")
                   (done)))
          (.catch (fn [cause]
                    (t/is (= "cache failed" (ex-message cause)))
                    (done)))))))
