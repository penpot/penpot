;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS SUBSIDIARY SL

(ns frontend-tests.render-wasm.serialization-test
  "Routing tests for `serialize-shape!` and `serialize-shapes-batch!`.

   A shared `tail-cases` table states, per shape, whether the svg-attrs/path
   tail must fire. The pure `needs-shape-tail?` predicate is tested against
   the table with no stubs; the two thin routing tests drive both serializers
   from the same table with the FFI boundary stubbed. Guard drift between the
   single and batch paths fails loudly. These tests assert routing only;
   render output below the FFI line needs headed or exporter pixel runs."
  (:require
   [app.common.render-wasm.api.props :as props]
   [app.common.render-wasm.api.upload :as upload]
   [app.common.render-wasm.serialize-shape :as serialize-shape]
   [cljs.test :as t :include-macros true]))

(defn- with-ffi-stubs*
  "Stubs the single-arity FFI boundary fns (`flush-shapes-batch!`,
  `set-shape-svg-attrs`, `set-shape-path-content`). `set-shape-upload!` is
  deliberately left real: it is a trivial one-liner over the stubbed flush,
  so the single test exercises its actual delegation and default opts. A
  plain fn `set!` onto a multi-arity var breaks under the `:esm` test build
  (call sites dispatch via `cljs$core$IFn$_invoke$arity$N`; see `mock/stub`)."
  [stubs thunk]
  (let [orig-flush     upload/flush-shapes-batch!
        orig-svg-attrs props/set-shape-svg-attrs
        orig-path      props/set-shape-path-content]
    (set! upload/flush-shapes-batch! (:flush stubs))
    (set! props/set-shape-svg-attrs (:svg-attrs stubs))
    (set! props/set-shape-path-content (:path stubs))
    (try
      (thunk)
      (finally
        (set! upload/flush-shapes-batch! orig-flush)
        (set! props/set-shape-svg-attrs orig-svg-attrs)
        (set! props/set-shape-path-content orig-path)))))

(def ^:private tail-cases
  "Shared spec for the svg-attrs/path tail. Each entry states whether the
  tail must fire (`:tail?`) and which writes it must perform (`:svg?`,
  `:path?`). Both routing tests derive their expectations from this table,
  so single/batch guard drift fails."
  [{:shape {:id (random-uuid) :type :frame} :tail? false :svg? false :path? false}
   {:shape {:id (random-uuid) :type :rect} :tail? false :svg? false :path? false}
   {:shape {:id (random-uuid) :type :rect :svg-attrs {:fill "blue"}}
    :tail? true :svg? true :path? false}
   {:shape {:id (random-uuid) :type :group :svg-attrs {:fill "blue"}}
    :tail? true :svg? true :path? false}
   {:shape {:id (random-uuid) :type :text :svg-attrs {:fill "blue"}}
    :tail? true :svg? true :path? false}
   {:shape {:id (random-uuid) :type :path :content {:type :path-content}}
    :tail? true :svg? false :path? true}
   {:shape {:id (random-uuid) :type :path}
    :tail? false :svg? false :path? false}
   {:shape {:id (random-uuid) :type :path
            :content {:type :path-content} :svg-attrs {:fill "red"}}
    :tail? true :svg? true :path? true}
   {:shape {:id (random-uuid) :type :bool :content {:type :bool-content}}
    :tail? true :svg? false :path? true}
   {:shape {:id (random-uuid) :type :bool}
    :tail? false :svg? false :path? false}
   {:shape {:id (random-uuid) :type :text :content {:type :text-content}}
    :tail? false :svg? false :path? false}
   ;; svg-attrs fires the tail, but the inner type guard must still withhold
   ;; the path write from text content.
   {:shape {:id (random-uuid) :type :text
            :svg-attrs {:fill "blue"} :content {:type :text-content}}
    :tail? true :svg? true :path? false}
   ;; svg-attrs alone fires the tail for path shapes; the svg write must not
   ;; hide under the content check.
   {:shape {:id (random-uuid) :type :path :svg-attrs {:fill "red"}}
    :tail? true :svg? true :path? false}])

(defn- expected-events
  [cases]
  (into []
        (mapcat (fn [{:keys [shape tail? svg? path?]}]
                  (let [id (:id shape)]
                    (cond-> []
                      tail? (conj [:select id])
                      svg?  (conj [:svg-attrs id (:svg-attrs shape)])
                      path? (conj [:path id (:content shape)])))))
        cases))

(t/deftest tail-cases-flags-are-consistent
  (doseq [{:keys [shape tail? svg? path?]} tail-cases]
    (t/is (boolean? tail?) "tail? is a boolean")
    (t/is (= tail? (boolean (or svg? path?))) "tail? matches the write flags")
    (t/is (= svg? (some? (:svg-attrs shape))) "svg? matches the shape")
    (t/is (= path? (boolean (and (contains? #{:path :bool} (:type shape))
                                 (some? (:content shape)))))
          "path? matches the shape")))

(t/deftest needs-shape-tail-matches-spec-table
  (doseq [{:keys [shape tail?]} tail-cases]
    (t/is (= tail? (serialize-shape/needs-shape-tail? shape))
          (str "predicate matches spec for " (:type shape)
               " svg? " (some? (:svg-attrs shape))
               " content? " (some? (:content shape))))))

(t/deftest serialize-shapes-batch-routes-flush-and-tail
  (let [shapes (mapv :shape tail-cases)
        opts {:include-layout? true :include-fills-strokes? true}
        flush-calls (atom [])
        events (atom [])
        current (atom nil)
        select-fn (fn [id]
                    (reset! current id)
                    (swap! events conj [:select id]))
        stubs {:flush (fn [s o] (swap! flush-calls conj {:shapes s :opts o}) nil)
               :svg-attrs (fn [attrs] (swap! events conj [:svg-attrs @current attrs]) nil)
               :path (fn [content] (swap! events conj [:path @current content]) nil)}
        result (with-ffi-stubs* stubs
                 #(serialize-shape/serialize-shapes-batch! shapes opts select-fn))
        flushed (first @flush-calls)]
    (t/is (= 1 (count @flush-calls)) "exactly one flush")
    (t/is (= opts (:opts flushed)) "flush carries passed opts")
    (t/is (= (count shapes) (count (:shapes flushed))) "flush covers all shapes")
    (t/is (= (mapv :id shapes) (mapv :id (:shapes flushed))) "order preserved")
    (t/is (= (expected-events tail-cases) @events)
          "select precedes each tail write, tails hit the right ids")
    (t/is (= (mapv :id shapes) (mapv :id result)) "returns prepared in order")))

(t/deftest serialize-shape-routes-single-upload-and-tail
  (doseq [{:keys [shape tail? svg? path?]} tail-cases]
    (let [flush-calls (atom [])
          svg-applied (atom [])
          path-applied (atom [])
          stubs {:flush (fn [s o] (swap! flush-calls conj {:shapes s :opts o}) nil)
                 :svg-attrs (fn [attrs] (swap! svg-applied conj attrs) nil)
                 :path (fn [content] (swap! path-applied conj content) nil)}]
      (with-ffi-stubs* stubs #(serialize-shape/serialize-shape! shape))
      ;; Deliberate Step 2 tripwire: moving derivation inside
      ;; `serialize-shape!` assocs `:fills`/`:blur`/`:shadow`, so this strict
      ;; equality must break that day and gain a derivation assertion.
      (t/is (= [{:shapes [shape] :opts {:include-layout? false}}] @flush-calls)
            (str "single structural upload without layout for " (:type shape)))
      (t/is (= (if svg? [(:svg-attrs shape)] []) @svg-applied)
            (str "svg-attrs write iff spec for " (:type shape)))
      (t/is (= (if path? [(:content shape)] []) @path-applied)
            (str "path write iff spec for " (:type shape)))
      (t/is (= tail? (boolean (or (seq @svg-applied) (seq @path-applied))))
            (str "tail fires iff spec for " (:type shape))))))

(t/deftest serialize-shapes-batch-empty-batch-is-noop
  (let [opts {:include-layout? true :include-fills-strokes? true}
        flush-calls (atom [])
        selected (atom [])
        stubs {:flush (fn [s o] (swap! flush-calls conj {:shapes s :opts o}) nil)
               :svg-attrs (fn [_] (t/is false "no svg-attrs write on empty batch") nil)
               :path (fn [_] (t/is false "no path write on empty batch") nil)}
        result (with-ffi-stubs* stubs
                 #(serialize-shape/serialize-shapes-batch!
                   []
                   opts
                   (fn [id] (swap! selected conj id))))]
    (t/is (= [] result) "returns empty prepared")
    (t/is (empty? @flush-calls) "no flush on empty batch")
    (t/is (empty? @selected) "no select on empty batch")))
