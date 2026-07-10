;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC Sucursal en España SL

(ns frontend-tests.render-wasm.calculate-bool-test
  "Unit tests for wasm.api/calculate-bool (issue #10647).

   calculate-bool wraps the WASM boolean calculation in a temporary
   shapes pool (_start_temp_objects / _end_temp_objects). Two invariants
   are pinned here:

   1. The temporary pool must ALWAYS be discarded, even when the body
      throws. If it leaks, every subsequent boolean recalculation panics
      in _start_temp_objects ('previous have not been restored') and the
      workspace shows the internal-error screen.

   2. WASM heap views (module.HEAPU32) are replaced by Emscripten when
      linear memory grows — any view captured before an allocating call
      goes stale. calculate-bool* must (re)fetch the heap AFTER
      _alloc_bytes (for the UUID writes) and AFTER _calculate_bool (for
      reading the result), because both can grow memory."
  (:require
   [app.common.uuid :as uuid]
   [app.render-wasm.api :as wasm.api]
   [app.render-wasm.wasm :as wasm]
   [cljs.test :as t :include-macros true]))

;; ---------------------------------------------------------------------------
;; Fake WASM module
;; ---------------------------------------------------------------------------

(def ^:private HEAP-U32-LEN 4096)

(defn- make-fake-module
  "Builds a fake Emscripten module implementing the entry points that
   calculate-bool touches. State is tracked in the returned map's atoms.

   Options:
   - :grow-on-alloc? — _alloc_bytes replaces HEAPU32 with a fresh view
     (simulates memory growth detaching previously captured views).
   - :grow-on-calc?  — _calculate_bool replaces HEAPU32 with a fresh view
     that carries the result; the old view holds garbage at the result
     offset (what a stale view would read after growth)."
  [{:keys [grow-on-alloc? grow-on-calc?]}]
  (let [temp-open?   (atom false)
        temp-starts  (atom 0)
        temp-ends    (atom 0)
        received-u32 (atom nil)
        module       #js {}
        result-off32 128]

    (unchecked-set module "HEAPU32" (js/Uint32Array. HEAP-U32-LEN))

    (unchecked-set module "_read_error_code" (fn [] 2))

    (unchecked-set module "_start_temp_objects"
                   (fn []
                     (swap! temp-starts inc)
                     (when @temp-open?
                       (throw (js/Error. "Tried to start a temp objects while the previous have not been restored")))
                     (reset! temp-open? true)
                     js/undefined))

    (unchecked-set module "_end_temp_objects"
                   (fn []
                     (swap! temp-ends inc)
                     (when-not @temp-open?
                       (throw (js/Error. "Tried to end temp objects but not content to be restored is present")))
                     (reset! temp-open? false)
                     js/undefined))

    (unchecked-set module "_init_shapes_pool" (fn [_n] js/undefined))
    (unchecked-set module "_free_bytes" (fn [] js/undefined))

    (unchecked-set module "_alloc_bytes"
                   (fn [_size]
                     (when grow-on-alloc?
                       ;; Emscripten reassigns module.HEAPU32 to a view over
                       ;; the new (larger) buffer; old views go stale.
                       (unchecked-set module "HEAPU32" (js/Uint32Array. HEAP-U32-LEN)))
                     ;; byte offset where the caller must write its input
                     0))

    (unchecked-set module "_calculate_bool"
                   (fn [_bool-type]
                     ;; capture what the caller actually wrote into the
                     ;; CURRENT heap view (the wasm side reads this memory)
                     (let [heap (unchecked-get module "HEAPU32")]
                       (reset! received-u32 (vec (.slice ^js heap 0 8))))
                     (let [old-heap (unchecked-get module "HEAPU32")]
                       (when grow-on-calc?
                         ;; poison the old view at the result offset, then
                         ;; grow: the result only exists in the new view
                         (aset old-heap result-off32 1234567)
                         (unchecked-set module "HEAPU32" (js/Uint32Array. HEAP-U32-LEN)))
                       (let [heap (unchecked-get module "HEAPU32")]
                         ;; result: [length=0] → empty path content
                         (aset heap result-off32 0)))
                     ;; byte offset of the result
                     (* 4 result-off32)))

    {:module       module
     :temp-open?   temp-open?
     :temp-starts  temp-starts
     :temp-ends    temp-ends
     :received-u32 received-u32}))

(defn- with-fake-wasm*
  "Installs `module` as the active WASM module and `set-object-fn` as
   wasm.api/set-object, runs (thunk), then restores the originals."
  [module set-object-fn thunk]
  (let [orig-module     wasm/internal-module
        orig-set-object wasm.api/set-object]
    (set! wasm/internal-module module)
    (set! wasm.api/set-object set-object-fn)
    (try
      (thunk)
      (finally
        (set! wasm/internal-module orig-module)
        (set! wasm.api/set-object orig-set-object)))))

(defn- make-bool-fixture
  []
  (let [child-a (uuid/next)
        child-b (uuid/next)
        bool-id (uuid/next)]
    {:child-a child-a
     :child-b child-b
     :shape   {:id bool-id :type :bool :bool-type :union :shapes [child-a child-b]}
     :objects {child-a {:id child-a :type :path}
               child-b {:id child-b :type :path}}}))

;; ---------------------------------------------------------------------------
;; Tests
;; ---------------------------------------------------------------------------

(t/deftest temp-objects-closed-when-body-throws
  "If anything inside calculate-bool throws (serialization, allocation,
   the boolean op itself), the temporary pool must still be discarded so
   the next boolean recalculation does not panic in _start_temp_objects."
  (let [{:keys [shape objects]} (make-bool-fixture)
        {:keys [module temp-open? temp-ends]} (make-fake-module {})
        boom (fn [_shape] (throw (js/Error. "set-object failed")))]
    (with-fake-wasm* module boom
      (fn []
        (t/is (thrown? js/Error (wasm.api/calculate-bool shape objects))
              "the original failure must propagate")
        (t/is (= 1 @temp-ends) "_end_temp_objects called despite the throw")
        (t/is (false? @temp-open?) "temporary pool discarded")))))

(t/deftest calculate-bool-recovers-after-previous-failure
  "A failed calculate-bool must not poison subsequent calls (issue #10647:
   the reported panic is the SECOND call finding the temp pool still open)."
  (let [{:keys [shape objects]} (make-bool-fixture)
        {:keys [module temp-open?]} (make-fake-module {})
        calls (atom 0)
        ;; first call throws mid-body, later calls succeed
        flaky (fn [_shape]
                (when (= 1 (swap! calls inc))
                  (throw (js/Error. "transient failure"))))]
    (with-fake-wasm* module flaky
      (fn []
        (t/is (thrown? js/Error (wasm.api/calculate-bool shape objects)))
        (let [content (wasm.api/calculate-bool shape objects)]
          (t/is (some? content) "second call succeeds after a failed one"))
        (t/is (false? @temp-open?))))))

(t/deftest uuid-writes-survive-memory-growth-on-alloc
  "_alloc_bytes can grow WASM memory, detaching any heap view captured
   before it. The child UUIDs must land in the CURRENT heap view or the
   wasm side reads zeros (→ 'Invalid UUID' error → leaked temp pool)."
  (let [{:keys [shape objects child-a child-b]} (make-bool-fixture)
        {:keys [module received-u32]} (make-fake-module {:grow-on-alloc? true})
        ;; calculate-bool* writes the ids in reverse order (rseq)
        expected (into (vec (uuid/get-u32 child-b))
                       (vec (uuid/get-u32 child-a)))]
    (with-fake-wasm* module (fn [_shape] nil)
      (fn []
        (wasm.api/calculate-bool shape objects)
        (t/is (= expected @received-u32)
              "wasm reads the ids from the heap that is current after alloc")))))

(t/deftest result-read-from-current-heap-after-calculate
  "_calculate_bool allocates the result buffer, which can also grow
   memory. The result must be read through a heap view fetched AFTER the
   call; a stale view sees garbage at the result offset."
  (let [{:keys [shape objects]} (make-bool-fixture)
        {:keys [module temp-open?]} (make-fake-module {:grow-on-calc? true})]
    (with-fake-wasm* module (fn [_shape] nil)
      (fn []
        (let [content (wasm.api/calculate-bool shape objects)]
          (t/is (zero? (count content))
                "empty result parsed from the post-growth heap view"))
        (t/is (false? @temp-open?))))))
