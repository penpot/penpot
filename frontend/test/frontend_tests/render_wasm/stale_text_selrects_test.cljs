;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS SUBSIDIARY SL

(ns frontend-tests.render-wasm.stale-text-selrects-test
  "A page load must check every auto-grow text whose stored size no longer
  matches its WASM layout, not only the texts that waited for a font. A copy
  synced while its page was closed keeps the main's size otherwise."
  (:require
   [app.common.render-wasm.serialize-shape :as serialize-shape]
   [app.common.render-wasm.wasm :as wasm]
   [app.common.uuid :as uuid]
   [app.main.store :as st]
   [app.render-wasm.api :as wasm.api]
   [app.render-wasm.api.fonts :as wasm.fonts]
   [app.util.timers :as timers]
   [beicon.v2.core :as rx]
   [cljs.test :as t :include-macros true]
   [frontend-tests.helpers.async :as async]
   [frontend-tests.helpers.mock :as mock]
   [potok.v2.core :as ptk]
   [promesa.core :as p]))

(defn- text-shape
  ([grow-type width height]
   (text-shape grow-type width height nil))
  ([grow-type width height attrs]
   (merge {:id (uuid/next)
           :type :text
           :parent-id uuid/zero
           :grow-type grow-type
           :width width
           :height height
           :selrect {:x 0 :y 0 :width width :height height}}
          attrs)))

(defn- rect-shape
  []
  {:id (uuid/next) :type :rect :parent-id uuid/zero})

(defn- page-objects
  [& shapes]
  (into {uuid/zero {:id uuid/zero
                    :type :frame
                    :parent-id uuid/zero
                    :shapes (mapv :id shapes)}}
        (map (juxt :id identity))
        shapes))

(defn- stale-selrect-ids
  "Ids carried by the stale-selrect events among `events`."
  [events]
  (into #{}
        (comp (filter #(= :app.render-wasm.api/stale-text-selrects (ptk/type %)))
              (mapcat (comp :ids deref)))
        events))

(defn- no-pending-fonts
  "Host attrs of a shape whose fonts are all loaded."
  [& _]
  {:thumbnails [] :full [] :font-face-keys #{} :pending-font-face-keys #{}})

(defn- run-with-wasm
  "Run `f` with the WASM boundaries mocked and resolve to the events emitted
  to the store. `dims` maps shape id to its measured layout size,
  `host-attrs` stands in for the per-shape upload, `failed-faces` holds the
  font faces that failed to load and `process-pending` replaces the pending
  fetches (a no-op unless given). `f` may return a promise, which is
  awaited."
  [{:keys [dims host-attrs failed-faces process-pending]} f]
  (let [emitted         (atom [])
        host-attrs      (or host-attrs no-pending-fonts)
        process-pending (or process-pending (mock/stub (constantly nil)))]
    (->> (mock/with-mocks*
           {wasm/live?                                  (mock/stub (constantly true))
            wasm/internal-module                        #js {"_set_view_end"                       (fn [& _] nil)
                                                             "_begin_loading"                      (fn [& _] nil)
                                                             "_end_loading"                        (fn [& _] nil)
                                                             "_update_shape_text_layout_for"       (fn [& _] nil)
                                                             "_force_update_shape_text_layout_for" (fn [& _] nil)}
            serialize-shape/serialize-shapes-batch      (mock/stub (fn [shapes _] shapes))
            ;; Private: the only seam that feeds the font state of the load.
            #_{:clj-kondo/ignore [:private-call]}
            wasm.api/set-object-host-attrs              host-attrs
            wasm.api/process-pending                    process-pending
            wasm.api/request-render                     (mock/stub (constantly nil))
            wasm.api/get-text-dimensions                (mock/stub (fn [id] (get dims id)))
            wasm.fonts/font-failed?                     (mock/stub #(contains? (or failed-faces #{}) %))
            wasm.api/listen-tiles-render-complete-once! (mock/stub (fn [f] (f)))
            timers/schedule-on-idle                     mock/schedule-on-idle-mock
            st/emit!                                    (mock/stub (fn [& events] (swap! emitted into events) nil))}
           (await (f))
           (await (async/settle)))
         (p/fmap (fn [_] @emitted)))))

(defn- load-page
  "Load `objects` through `wasm.api/set-objects`, forcing the sync path used
  by small pages."
  ([objects dims]
   (load-page objects dims nil))
  ([objects dims host-attrs]
   (load-page objects dims host-attrs #{}))
  ([objects dims host-attrs failed-faces]
   (run-with-wasm {:dims dims :host-attrs host-attrs :failed-faces failed-faces}
                  #(wasm.api/set-objects objects nil nil true))))

(defn- load-large-page
  "Load `objects` through `wasm.api/set-objects` without forcing, so a page
  with enough shapes takes the chunked async path. Resolves once the load
  has rendered."
  [objects dims]
  (run-with-wasm {:dims dims}
                 #(let [rendered (p/deferred)]
                    (wasm.api/set-objects objects (fn [] (p/resolve rendered nil)) nil false)
                    rendered)))

(t/deftest ^:async page-load-requests-resize-for-stale-auto-height-text
  (let [text   (text-shape :auto-height 100 17)
        events (await (load-page (page-objects text)
                                 {(:id text) {:width 100 :height 34}}))]
    (t/is (= #{(:id text)} (stale-selrect-ids events))
          "the text stored at the main's height is resized on page load")))

(t/deftest ^:async large-page-load-requests-resize-for-stale-text
  (let [text    (text-shape :auto-height 100 17)
        shapes  (conj (vec (repeatedly (dec wasm.api/ASYNC_THRESHOLD) rect-shape)) text)
        events  (await (load-large-page (apply page-objects shapes)
                                        {(:id text) {:width 100 :height 34}}))]
    (t/is (= #{(:id text)} (stale-selrect-ids events))
          "a page loaded in chunks checks its texts too")))

(t/deftest ^:async page-load-ignores-sub-pixel-gaps
  (let [text   (text-shape :auto-width 100 17)
        events (await (load-page (page-objects text)
                                 {(:id text) {:width 100.5 :height 17.5}}))]
    (t/is (empty? (stale-selrect-ids events))
          "rounding differences between renderers do not trigger a resize")))

(t/deftest ^:async page-load-skips-fixed-text
  (let [text   (text-shape :fixed 100 17)
        events (await (load-page (page-objects text)
                                 {(:id text) {:width 100 :height 34}}))]
    (t/is (empty? (stale-selrect-ids events))
          "fixed text boxes are never resized to their layout")))

(t/deftest ^:async page-load-skips-axes-that-fill-their-layout-parent
  (let [fill-width  (text-shape :auto-width 200 17 {:layout-item-h-sizing :fill})
        fill-height (text-shape :auto-height 100 40 {:layout-item-v-sizing :fill})
        events      (await (load-page (page-objects fill-width fill-height)
                                      {(:id fill-width)  {:width 44 :height 17}
                                       (:id fill-height) {:width 100 :height 17}}))]
    (t/is (empty? (stale-selrect-ids events))
          "a size set by the layout is not stale")))

(t/deftest ^:async page-load-checks-the-axis-that-does-not-fill
  (let [text   (text-shape :auto-width 200 17 {:layout-item-h-sizing :fill})
        events (await (load-page (page-objects text)
                                 {(:id text) {:width 44 :height 34}}))]
    (t/is (= #{(:id text)} (stale-selrect-ids events))
          "the height of a fill-width auto-width text still grows with its content")))

(t/deftest ^:async page-load-skips-texts-waiting-for-a-font
  (let [waiting (text-shape :auto-height 100 17)
        ready   (text-shape :auto-height 100 17)
        events  (await (load-page
                        (page-objects waiting ready)
                        {(:id waiting) {:width 100 :height 34}
                         (:id ready)   {:width 100 :height 34}}
                        ;; One of the two stale texts uses a face still loading.
                        (fn [shape & _]
                          (if (= (:id shape) (:id waiting))
                            {:thumbnails [] :full []
                             :font-face-keys #{"font-key"}
                             :pending-font-face-keys #{"font-key"}}
                            (no-pending-fonts)))))]
    (t/is (= #{(:id ready)} (stale-selrect-ids events))
          "a text waiting for a font is not checked when the page loads")))

(t/deftest ^:async page-load-skips-texts-using-a-failed-font
  (let [failed (text-shape :auto-height 100 17)
        ready  (text-shape :auto-height 100 17)
        events (await (load-page
                       (page-objects failed ready)
                       {(:id failed) {:width 100 :height 34}
                        (:id ready)  {:width 100 :height 34}}
                       ;; WASM counts the failed face as ready and lays the
                       ;; text out with a fallback font.
                       (fn [shape & _]
                         (if (= (:id shape) (:id failed))
                           {:thumbnails [] :full []
                            :font-face-keys #{"failed-key"}
                            :pending-font-face-keys #{}}
                           (no-pending-fonts)))
                       #{"failed-key"}))]
    (t/is (= #{(:id ready)} (stale-selrect-ids events))
          "a text measured with a fallback font keeps its stored size")))

(t/deftest ^:async pending-shape-updates-do-not-check-text-sizes
  (let [text   (text-shape :auto-height 100 17)
        events (await (run-with-wasm
                       {:dims            {(:id text) {:width 100 :height 34}}
                        :process-pending wasm.api/process-pending}
                       #(wasm.api/process-pending [text] [] [] {} nil)))]
    (t/is (empty? (stale-selrect-ids events))
          "shape updates while editing do not resize texts to their layout")))

(t/deftest ^:async font-load-skips-texts-whose-font-failed
  (let [failed     (text-shape :auto-height 100 17)
        loaded     (text-shape :auto-height 100 17)
        font-state {:font-index    {"failed-key" #{(:id failed)}
                                    "loaded-key" #{(:id loaded)}}
                    :pending-faces #{"failed-key" "loaded-key"}}
        ;; A pending fetch, so the font relayout runs once it completes.
        full       [{:key "font" :callback #(rx/of nil)}]
        events     (await (run-with-wasm
                           {:dims            {(:id failed) {:width 100 :height 34}
                                              (:id loaded) {:width 100 :height 34}}
                            :failed-faces    #{"failed-key"}
                            :process-pending wasm.api/process-pending}
                           #(wasm.api/process-pending [failed loaded] [] full font-state nil)))]
    (t/is (= #{(:id loaded)} (stale-selrect-ids events))
          "a text laid out with a fallback font after its face failed keeps its stored size")))
