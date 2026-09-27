;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS SUBSIDIARY SL

(ns frontend-tests.benches.lifecycle-test
  "Boundary-fake coverage for the ticket-05 browser lifecycle.

  Full load/render runs headless in the pilot (real WASM + DOM). Here every
   DOM/FFI boundary is faked or avoided: bridge arg validation (seeds, views,
   viewports), viewport resolution, disposal state reset, epoch bumps,
   context-loss flags and bridge shape. No canvas, rAF or module import is
   exercised: partial-init rendering, live context loss and stale-callback
   races run headless in the pilot; exhaustive stale-callback unit coverage
   belongs to ticket 06's protocol-test with its injected clock."
  (:require
   [app.common.render-wasm.wasm :as wasm]
   [benches.render-wasm.browser :as browser]
   [benches.render-wasm.scenes.core :as core]
   [cljs.test :as t :include-macros true]))

(defn- expect-failed-args
  "Calls `load-rects` with `args` (never touching DOM/FFI: validation runs
  first) and asserts a failure map. Calls `done` when finished."
  [args check-phase? done]
  (-> (.then (browser/load-rects args)
             (fn [result]
               (let [m (js->clj result :keywordize-keys true)]
                 (t/is (= "failed" (:status m)))
                 (when check-phase?
                   (t/is (= "invalid-args" (:phase m))))
                 (done))))
      (.catch (fn [cause]
                (t/is false (str "must resolve, threw: " cause))
                (done)))))

(t/deftest invalid-seed-fails-before-timers
  (t/async done
    (expect-failed-args #js {"seed" -1} true done)))

(t/deftest missing-seed-fails-before-timers
  (t/async done
    (expect-failed-args #js {} true done)))

(t/deftest invalid-view-fails-before-timers
  (t/async done
    (expect-failed-args
     #js {"seed" 42
          "view" #js {"scale" -1 "x" 0 "y" 0}}
     true
     done)))

(t/deftest invalid-viewport-fails-before-timers
  (t/async done
    (expect-failed-args
     #js {"seed" 42
          "view" #js {"scale" 1 "x" 0 "y" 0
                      "viewport" #js {"width" -5}}}
     true
     done)))

(t/deftest viewport-defaults-apply-at-use
  (t/is (= {:width 1920 :height 1080 :dpr 2}
           (core/resolve-viewport {:scale 1 :x 0 :y 0})))
  (t/is (= {:width 800 :height 600 :dpr 2}
           (core/resolve-viewport {:scale 1 :x 0 :y 0
                                   :viewport {:width 800 :height 600}}))))

(t/deftest dispose-resets-lifecycle-state
  (set! wasm/internal-module #js {})
  (set! wasm/context-initialized? true)
  (reset! wasm/context-lost? true)
  (let [result (browser/dispose!)]
    (t/is (= "disposed" (unchecked-get result "status")))
    (t/is (nil? wasm/internal-module))
    (t/is (false? wasm/context-initialized?))
    (t/is (false? @wasm/context-lost?)))
  (t/testing "double dispose is idempotent"
    (let [result (browser/dispose!)]
      (t/is (= "disposed" (unchecked-get result "status")))
      (t/is (nil? wasm/internal-module))))
  (t/testing "dispose bumps the owner epoch"
    (let [before @@#'browser/owner-epoch*]
      (browser/dispose!)
      (t/is (< before @@#'browser/owner-epoch*)))))

(t/deftest bridge-exposes-pilot-entries
  (let [keys (js/Object.keys browser/bridge)]
    (t/is (some #{"ping"} keys))
    (t/is (some #{"loadRects"} keys))
    (t/is (some #{"dispose"} keys))))
