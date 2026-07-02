;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC Sucursal en España SL

(ns frontend-tests.render-wasm.process-objects-test
  "Unit tests for wasm.api/process-objects.

   The key invariant: process-objects must call set-object for every shape
   and forward ALL shapes to a single process-pending invocation.

   Without this batching the async font-load callback only covers the shape
   that triggered the font fetch. Subsequent text shapes that share the same
   font URL get no callback (fetch-font returns nil when the URL is already
   in :fetching) and are permanently stuck with fallback-font layout metrics."
  (:require
   [app.render-wasm.api :as wasm.api]
   [app.render-wasm.mem :as mem]
   [app.render-wasm.wasm :as wasm]
   [beicon.v2.core :as rx]
   [cljs.test :as t :include-macros true]))

;; ---------------------------------------------------------------------------
;; Helpers
;; ---------------------------------------------------------------------------

(defn- make-shape [type]
  {:id (random-uuid) :type type})

(defn- with-mocks*
  "Temporarily replaces wasm.api/set-object and wasm.api/process-pending,
   calls (thunk), then restores the originals."
  [mock-set-object mock-process-pending thunk]
  (let [orig-set-object    wasm.api/set-object
        orig-process-pending wasm.api/process-pending]
    (set! wasm.api/set-object     mock-set-object)
    (set! wasm.api/process-pending mock-process-pending)
    (try
      (thunk)
      (finally
        (set! wasm.api/set-object     orig-set-object)
        (set! wasm.api/process-pending orig-process-pending)))))

;; ---------------------------------------------------------------------------
;; Tests
;; ---------------------------------------------------------------------------

(t/deftest process-objects-calls-set-object-for-every-shape
  "Each shape in the input must go through set-object exactly once."
  (let [shapes      [(make-shape :text) (make-shape :text) (make-shape :rect)]
        visited-ids (atom [])
        mock-set    (fn [s] (swap! visited-ids conj (:id s)) {:thumbnails [] :full []})
        mock-pend   (fn [_sh _t _f _fp _cb] nil)]

    (with-mocks* mock-set mock-pend #(wasm.api/process-objects shapes))

    (t/is (= (mapv :id shapes) @visited-ids)
          "set-object called once per shape in order")))

(t/deftest process-objects-calls-process-pending-once-with-all-shapes
  "process-pending must receive ALL shapes in a single call.
   This is the invariant that makes the async font-load callback update text
   layouts for every text shape, not just the first one that triggered the
   font fetch."
  (let [shapes    [(make-shape :text) (make-shape :text)]
        captured  (atom nil)
        mock-set  (fn [_s] {:thumbnails [] :full []})
        mock-pend (fn [sh t f _fp cb] (reset! captured {:shapes sh :thumbnails t :full f :cb cb}))]

    (with-mocks* mock-set mock-pend #(wasm.api/process-objects shapes))

    (t/is (some? @captured)
          "process-pending was called")
    (t/is (= 2 (count (:shapes @captured)))
          "process-pending received all shapes")
    (t/is (= (set (map :id shapes))
             (set (map :id (:shapes @captured))))
          "process-pending received the correct shape objects")))

(t/deftest process-objects-accumulates-callbacks-across-shapes
  "Pending font/image callbacks from all shapes must be merged into the single
   process-pending call. This covers the deduplication scenario: when a second
   text shape shares the same font (fetch-font returns nil because the URL is
   already in :fetching), the callback from the first shape still covers the
   second shape because both are in the :shapes list passed to process-pending."
  (let [shape-a   (make-shape :text)
        shape-b   (make-shape :text)
        shapes    [shape-a shape-b]
        font-cb   (fn [] (rx/of true))
        captured  (atom nil)

        ;; Simulate deduplication: shape-a triggers a real font fetch,
        ;; shape-b gets an empty pending list (same URL already in :fetching).
        mock-set
        (fn [s]
          (if (= (:id s) (:id shape-a))
            {:thumbnails [{:key "font-url" :callback font-cb}] :full []}
            {:thumbnails [] :full []}))

        mock-pend
        (fn [sh t f _fp _cb] (reset! captured {:shapes sh :thumbnails t :full f}))]

    (with-mocks* mock-set mock-pend #(wasm.api/process-objects shapes))

    ;; The single font callback from shape-a is present...
    (t/is (= 1 (count (:thumbnails @captured)))
          "The font callback from shape-a is forwarded")
    ;; ...and BOTH shapes are in the list, so when the font loads and
    ;; process-pending fires update-text-layouts, it covers shape-b too.
    (t/is (= 2 (count (:shapes @captured)))
          "Both shapes are in process-pending so font-load covers all of them")))

(t/deftest empty-grid-tracks-do-not-allocate-zero-bytes
  (let [calls  (atom [])
        ;; `h/call` is a macro that resolves the wasm function off the module
        ;; via `unchecked-get`, so it cannot be redefined. Mock the module
        ;; itself with recording stubs and let the real macro expansion run.
        module #js {"_set_grid_rows"    (fn [& _] (swap! calls conj [:call "_set_grid_rows"]) nil)
                    "_set_grid_columns" (fn [& _] (swap! calls conj [:call "_set_grid_columns"]) nil)}]
    (with-redefs [mem/alloc (fn [size]
                              (swap! calls conj [:alloc size])
                              0)
                  wasm/internal-module module]
      (wasm.api/set-grid-layout-rows [])
      (wasm.api/set-grid-layout-columns []))
    (t/is (not-any? #(= :alloc (first %)) @calls))
    (t/is (= [[:call "_set_grid_rows"]
              [:call "_set_grid_columns"]]
             @calls))))
