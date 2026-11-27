;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS SUBSIDIARY SL

(ns frontend-tests.render-wasm.set-objects-async-test
  "The async page loader must stop once its renderer is torn down, so a page
  left mid-load does not write into the next page's renderer."
  (:require
   [app.common.render-wasm.serialize-shape :as serialize-shape]
   [app.common.render-wasm.wasm :as wasm]
   [app.render-wasm.api :as wasm.api]
   [cljs.test :as t :include-macros true]
   [frontend-tests.helpers.async :as thasync]
   [frontend-tests.helpers.mock :as mock]))

;; Two chunks: the loader processes 512 shapes, yields, then the rest.
(def ^:private shape-count 600)

(defn- make-objects
  []
  (into {}
        (map (fn [_]
               (let [id (random-uuid)]
                 [id {:id id :type :rect}])))
        (range shape-count)))

(defn- fake-module
  "Records the loading calls. It has no `_init`, so `clear-canvas` skips the
  GPU and `_clean_up` steps that need a real WASM binary."
  [calls]
  #js {"_begin_loading"   (fn [] (swap! calls conj :begin-loading))
       "_end_loading"     (fn [] (swap! calls conj :end-loading))
       "_set_view_end"    (fn [] (swap! calls conj :set-view-end))
       "_read_error_code" (fn [] 0)})

(defn- install-live-module
  [calls]
  (set! wasm/internal-module (fake-module calls))
  (set! wasm/context-initialized? true)
  (reset! wasm/context-lost? false)
  (reset! wasm/reloading? false))

(defn- restore-module
  [module context-initialized?]
  (set! wasm/internal-module module)
  (set! wasm/context-initialized? context-initialized?)
  (reset! wasm.api/shapes-loading? false))

(t/deftest ^:async uninterrupted-load-finishes
  (let [module      wasm/internal-module
        initialized wasm/context-initialized?
        calls       (atom [])
        uploaded    (atom 0)
        ready       (atom 0)]
    (install-live-module calls)
    (await
     (mock/with-mocks*
       {serialize-shape/serialize-shapes-batch
        (fn [shapes _opts]
          (swap! uploaded + (count shapes))
          (vec shapes))}

       (wasm.api/set-objects (make-objects) (fn [] nil) #(swap! ready inc) false)
       (await (thasync/wait-for #(pos? @ready) "on-shapes-ready"))
       (await (thasync/settle))

       (t/is (= shape-count @uploaded))
       (t/is (= 1 @ready))
       (t/is (= [:begin-loading :end-loading :set-view-end] @calls))
       (t/is (false? @wasm.api/shapes-loading?))))
    (restore-module module initialized)))

(t/deftest ^:async teardown-stops-a-load-in-progress
  (let [module      wasm/internal-module
        initialized wasm/context-initialized?
        old-calls   (atom [])
        new-calls   (atom [])
        uploaded    (atom 0)
        old-ready   (atom 0)
        new-ready   (atom 0)]
    (install-live-module old-calls)
    (await
     (mock/with-mocks*
       {serialize-shape/serialize-shapes-batch
        (fn [shapes _opts]
          (swap! uploaded + (count shapes))
          (vec shapes))}

       ;; The first chunk runs synchronously; the rest waits for a yield.
       (wasm.api/set-objects (make-objects) (fn [] nil) #(swap! old-ready inc) false)
       (t/is (= 512 @uploaded))

       (wasm.api/clear-canvas)
       ;; The next page loads on a fresh renderer, sharing the yield channel.
       (install-live-module new-calls)
       (wasm.api/set-objects (make-objects) (fn [] nil) #(swap! new-ready inc) false)

       (await (thasync/wait-for #(pos? @new-ready) "next page on-shapes-ready"))
       (await (thasync/settle))
       (await (thasync/settle))

       (t/is (= (+ 512 shape-count) @uploaded)
             "the old load uploads no chunk after teardown")
       (t/is (zero? @old-ready) "the old load does not call on-shapes-ready")
       (t/is (= [:begin-loading] @old-calls) "the old load does not end loading")
       (t/is (= 1 @new-ready))
       (t/is (= [:begin-loading :end-loading :set-view-end] @new-calls))
       (t/is (false? @wasm.api/shapes-loading?))))
    (restore-module module initialized)))
