;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC Sucursal en España SL

(ns frontend-tests.render-wasm.calculate-bool-test
  "Unit tests for wasm.api/calculate-bool (issue #10647).

   calculate-bool wraps the WASM boolean calculation in a temporary
   shapes pool (_start_temp_objects / _end_temp_objects) and a staging
   buffer (_alloc_bytes / consuming call / _free_bytes). The invariants
   pinned here:

   1. The temporary pool must ALWAYS be discarded, even when the body
      throws. If it leaks, every subsequent boolean recalculation panics
      in _start_temp_objects ('previous have not been restored') and the
      workspace shows the internal-error screen.

   2. The Rust staging buffer must never be left allocated: a leaked
      buffer makes the NEXT _alloc_bytes anywhere in the app fail with
      'Bytes already allocated' — a delayed panic misattributed to an
      unrelated operation.

   3. WASM heap views (module.HEAPU32) are replaced by Emscripten when
      linear memory grows — any view captured before an allocating call
      goes stale. calculate-bool* must (re)fetch the heap AFTER
      _alloc_bytes (for the UUID writes) and AFTER _calculate_bool (for
      reading the result), because both can grow memory.

   4. Operand ids are normalized like the CLJS fallback
      (calc-bool-content* in app.common.types.path): missing ids,
      hidden children and svg-raw children do not participate. Without
      this, wasm and the fallback produce different persisted geometry
      for the same document, and a missing base child silently empties
      the whole bool.

   5. When the calculation fails, the surfaced error must be the
      ORIGINAL failure, not a follow-up error from cleanup calls on a
      dead module.

   6. When the wasm render context is not ready, calculate-bool must
      compute through the CLJS fallback instead of crashing on an empty
      module."
  (:require
   [app.common.types.path :as path]
   [app.common.types.path.impl :as path.impl]
   [app.common.uuid :as uuid]
   [app.render-wasm.api :as wasm.api]
   [app.render-wasm.wasm :as wasm]
   [cljs.test :as t :include-macros true]))

;; ---------------------------------------------------------------------------
;; Fake WASM module
;; ---------------------------------------------------------------------------

(def ^:private HEAP-U32-LEN 4096)

(defn- path-data->u32
  "Return the raw u32 words of a PathData instance."
  [pd]
  (let [^js dview (.-buffer pd)]
    (js/Uint32Array. (.-buffer dview)
                     (.-byteOffset dview)
                     (/ (.-byteLength dview) 4))))

(defn- make-fake-module
  "Builds a fake Emscripten module implementing the entry points that
   calculate-bool touches. State is tracked in the returned map's atoms.

   The staging buffer semantics mirror render-wasm/src/wasm/mem.rs:
   _alloc_bytes fails when a buffer is already staged; the consuming
   call (_calculate_bool) takes it; _free_bytes discards it.

   Options:
   - :grow-on-alloc? — _alloc_bytes replaces HEAPU32 with a fresh view
     (simulates memory growth detaching previously captured views).
   - :grow-on-calc?  — _calculate_bool replaces HEAPU32 with a fresh view
     that carries the result; the old view holds garbage at the result
     offset (what a stale view would read after growth).
   - :die-on-calc?   — _calculate_bool throws after consuming the staged
     buffer (models a wasm-side failure).
   - :dead-cleanup?  — _free_bytes and _end_temp_objects throw (models a
     module that cannot service further calls after a fatal failure).
   - :result-segments — PathData written as the _calculate_bool result
     (defaults to an empty result)."
  [{:keys [grow-on-alloc? grow-on-calc? die-on-calc? dead-cleanup? result-segments]}]
  (let [temp-open?   (atom false)
        temp-starts  (atom 0)
        temp-ends    (atom 0)
        staged?      (atom false)
        alloc-sizes  (atom [])
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
                     (when dead-cleanup?
                       (throw (js/Error. "end_temp_objects failed (dead module)")))
                     (when-not @temp-open?
                       (throw (js/Error. "Tried to end temp objects but not content to be restored is present")))
                     (reset! temp-open? false)
                     js/undefined))

    (unchecked-set module "_init_shapes_pool" (fn [_n] js/undefined))

    (unchecked-set module "_free_bytes"
                   (fn []
                     (when dead-cleanup?
                       (throw (js/Error. "free_bytes failed (dead module)")))
                     (reset! staged? false)
                     js/undefined))

    (unchecked-set module "_alloc_bytes"
                   (fn [size]
                     (when @staged?
                       (throw (js/Error. "Bytes already allocated")))
                     (reset! staged? true)
                     (swap! alloc-sizes conj size)
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
                     (let [heap  (unchecked-get module "HEAPU32")
                           words (/ (or (peek @alloc-sizes) 0) 4)]
                       (reset! received-u32 (vec (.slice ^js heap 0 words))))
                     ;; the staged input is consumed (bytes_or_empty)
                     (reset! staged? false)
                     (when die-on-calc?
                       (throw (js/Error. "original failure")))
                     (let [old-heap (unchecked-get module "HEAPU32")]
                       (when grow-on-calc?
                         ;; poison the old view at the result offset, then
                         ;; grow: the result only exists in the new view
                         (aset old-heap result-off32 1234567)
                         (unchecked-set module "HEAPU32" (js/Uint32Array. HEAP-U32-LEN)))
                       (let [heap   (unchecked-get module "HEAPU32")
                             result (some-> result-segments path-data->u32)
                             length (if result
                                      (/ (.-length ^js result) path.impl/SEGMENT-U32-SIZE)
                                      0)]
                         (aset heap result-off32 length)
                         (when result
                           (.set ^js heap result (inc result-off32)))))
                     ;; byte offset of the result
                     (* 4 result-off32)))

    {:module       module
     :temp-open?   temp-open?
     :temp-starts  temp-starts
     :temp-ends    temp-ends
     :staged?      staged?
     :alloc-sizes  alloc-sizes
     :received-u32 received-u32}))

(defn- with-fake-wasm*
  "Installs `module` as the active WASM module, `set-object-fn` as
   wasm.api/set-object and forces wasm.api/initialized? to true, runs
   (thunk), then restores the originals."
  [module set-object-fn thunk]
  (let [orig-module       wasm/internal-module
        orig-set-object   wasm.api/set-object
        orig-initialized? wasm.api/initialized?]
    (set! wasm/internal-module module)
    (set! wasm.api/set-object set-object-fn)
    (set! wasm.api/initialized? (constantly true))
    (try
      (thunk)
      (finally
        (set! wasm/internal-module orig-module)
        (set! wasm.api/set-object orig-set-object)
        (set! wasm.api/initialized? orig-initialized?)))))

(defn- square-content
  "A simple closed square path as PathData."
  [x y size]
  (path.impl/from-plain
   [{:command :move-to :params {:x x :y y}}
    {:command :line-to :params {:x (+ x size) :y y}}
    {:command :line-to :params {:x (+ x size) :y (+ y size)}}
    {:command :line-to :params {:x x :y (+ y size)}}
    {:command :close-path :params {}}]))

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
;; Temp pool and staging buffer lifecycle
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

(t/deftest staging-buffer-released-when-calculation-fails
  "A wasm-side failure in _calculate_bool must leave no staged bytes
   behind: a leaked staging buffer makes the next _alloc_bytes anywhere
   in the app fail with 'Bytes already allocated'."
  (let [{:keys [shape objects]} (make-bool-fixture)
        {:keys [module staged? temp-open?]} (make-fake-module {:die-on-calc? true})]
    (with-fake-wasm* module (fn [_shape] nil)
      (fn []
        (t/is (thrown? js/Error (wasm.api/calculate-bool shape objects)))
        (t/is (false? @staged?) "staging buffer released after the failure")
        (t/is (false? @temp-open?) "temporary pool discarded")))
    ;; a fresh, healthy module must now work: nothing global was poisoned
    (let [{:keys [module]} (make-fake-module {})]
      (with-fake-wasm* module (fn [_shape] nil)
        (fn []
          (t/is (some? (wasm.api/calculate-bool shape objects))
                "subsequent calculations succeed"))))))

(t/deftest original-error-not-masked-by-failing-cleanup
  "When the module cannot service the cleanup calls after a failure
   (_free_bytes/_end_temp_objects also throw), the error surfaced to the
   caller must still be the ORIGINAL failure from _calculate_bool, not a
   misattributed cleanup error."
  (let [{:keys [shape objects]} (make-bool-fixture)
        {:keys [module]} (make-fake-module {:die-on-calc? true :dead-cleanup? true})]
    (with-fake-wasm* module (fn [_shape] nil)
      (fn []
        (let [err (try
                    (wasm.api/calculate-bool shape objects)
                    nil
                    (catch :default cause cause))]
          (t/is (some? err) "the failure propagates")
          (t/is (= "_calculate_bool" (:fn (ex-data err)))
                "the surfaced error is the original one, not a cleanup error"))))))

;; ---------------------------------------------------------------------------
;; Heap views across memory growth
;; ---------------------------------------------------------------------------

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

(t/deftest non-empty-result-read-across-memory-growth
  "Same as above but with real segments: the segment payload (not just
   the length word) must be read from the post-growth heap view."
  (let [{:keys [shape objects]} (make-bool-fixture)
        square (square-content 0 0 10)
        {:keys [module]} (make-fake-module {:grow-on-calc? true
                                            :result-segments square})]
    (with-fake-wasm* module (fn [_shape] nil)
      (fn []
        (let [content (wasm.api/calculate-bool shape objects)]
          (t/is (= 5 (count content)) "all segments read")
          (t/is (= square content) "segment payload identical to what wasm wrote"))))))

;; ---------------------------------------------------------------------------
;; Operand normalization (parity with the CLJS fallback)
;; ---------------------------------------------------------------------------

(t/deftest operand-ids-normalized-like-the-cljs-fallback
  "Hidden children, svg-raw children, missing ids and junk entries must
   not participate in the boolean operation — same filtering as
   calc-bool-content* in app.common.types.path. Without this a hidden
   child still shapes the wasm geometry, and junk/missing entries crash
   or silently change the result."
  (let [visible  (uuid/next)
        hidden   (uuid/next)
        svg-raw  (uuid/next)
        missing  (uuid/next)
        bool-id  (uuid/next)
        shape    {:id bool-id :type :bool :bool-type :union
                  :shapes [visible hidden svg-raw missing nil]}
        objects  {visible {:id visible :type :path}
                  hidden  {:id hidden :type :path :hidden true}
                  svg-raw {:id svg-raw :type :svg-raw}}
        {:keys [module received-u32 temp-starts temp-ends]} (make-fake-module {})]
    (with-fake-wasm* module (fn [_shape] nil)
      (fn []
        (let [content (wasm.api/calculate-bool shape objects)]
          (t/is (some? content) "the calculation succeeds")
          (t/is (= (vec (uuid/get-u32 visible)) @received-u32)
                "only the visible, existing, non-svg-raw child participates")
          (t/is (= 1 @temp-starts))
          (t/is (= 1 @temp-ends)))))))

(t/deftest degenerate-operand-lists-short-circuit
  "A bool with no effective operands (empty :shapes, nil :shapes, or all
   children filtered out) must return empty content without touching the
   wasm module at all: no temp pool churn, no zero-byte allocations."
  (let [hidden  (uuid/next)
        objects {hidden {:id hidden :type :path :hidden true}}]
    (doseq [shapes [[] nil [hidden]]]
      (let [shape {:id (uuid/next) :type :bool :bool-type :union :shapes shapes}
            {:keys [module temp-starts alloc-sizes]} (make-fake-module {})]
        (with-fake-wasm* module (fn [_shape] nil)
          (fn []
            (let [content (wasm.api/calculate-bool shape objects)]
              (t/is (some? content) (str "returns content for :shapes " (pr-str shapes)))
              (t/is (zero? (count content)) "content is empty")
              (t/is (zero? @temp-starts) "no temporary pool created")
              (t/is (empty? @alloc-sizes) "no wasm allocation performed"))))))))

;; ---------------------------------------------------------------------------
;; Module readiness
;; ---------------------------------------------------------------------------

(t/deftest falls-back-to-cljs-when-context-not-ready
  "The wasm:calc-bool-content hook is installed at boot, before the async
   module load finishes. In that window calculate-bool must compute the
   content through the CLJS fallback instead of crashing on the empty
   module object."
  (let [child-a (uuid/next)
        child-b (uuid/next)
        bool-id (uuid/next)
        objects {child-a {:id child-a :type :path :content (square-content 0 0 10)}
                 child-b {:id child-b :type :path :content (square-content 5 5 10)}}
        shape   {:id bool-id :type :bool :bool-type :union :shapes [child-a child-b]}
        orig-module       wasm/internal-module
        orig-initialized? wasm.api/initialized?]
    ;; the exact boot-time state: empty module, context not initialized
    (set! wasm/internal-module #js {})
    (set! wasm.api/initialized? (constantly false))
    (try
      (let [content  (wasm.api/calculate-bool shape objects)
            expected (path/calc-bool-content shape objects)]
        (t/is (some? content) "no crash while the module is not ready")
        (t/is (= expected content) "identical to the CLJS fallback result"))
      (finally
        (set! wasm/internal-module orig-module)
        (set! wasm.api/initialized? orig-initialized?)))))
