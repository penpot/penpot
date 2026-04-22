;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC

(ns common-tests.geom-grid-layout-test
  (:require
   ;; Requiring modifiers triggers the side-effect that wires
   ;; -child-min-width / -child-min-height into grid layout-data.
   [app.common.geom.modifiers]
   [app.common.geom.rect :as grc]
   [app.common.geom.shapes.grid-layout.layout-data :as gld]
   [app.common.math :as mth]
   [app.common.types.shape :as cts]
   [clojure.test :as t]))

;; ---------------------------------------------------------------------------
;; Shared test-data builders
;; ---------------------------------------------------------------------------

(defn- make-grid-frame
  "Minimal grid-layout frame with two fixed columns of 50.0 px
  and one fixed row. Width and height are explicit, no padding.
  Track values are floats to avoid JVM integer-divide-by-zero when
  there are no flex tracks (column-frs = 0)."
  [& {:as opts}]
  (cts/setup-shape
   (merge {:type :frame
           :layout :grid
           :layout-grid-dir :row
           :layout-grid-columns [{:type :fixed :value 50.0}
                                 {:type :fixed :value 50.0}]
           :layout-grid-rows [{:type :fixed :value 100.0}]
           :layout-grid-cells {}
           :layout-padding-type :multiple
           :layout-padding {:p1 0 :p2 0 :p3 0 :p4 0}
           :layout-gap {:column-gap 0 :row-gap 0}
           :x 0 :y 0 :width 200 :height 100}
          opts)))

(defn- bounds-for
  "Return the 4-point layout-bounds for the frame."
  [frame]
  (grc/rect->points (grc/make-rect (:x frame) (:y frame) (:width frame) (:height frame))))

;; Build a simple non-fill child shape with explicit width/height.
;; No layout-item-margin → child-width-margin = 0.
(defn- make-child
  [w h]
  (cts/setup-shape {:type :rect :width w :height h :x 0 :y 0}))

;; Build the 4-point bounds vector for a child with the given dimensions.
(defn- child-bounds
  [w h]
  (grc/rect->points (grc/make-rect 0 0 w h)))

;; Build an auto track at its initial size (0.01) with infinite max.
(defn- auto-track [] {:type :auto :size 0.01 :max-size ##Inf})

;; Build a fixed track with the given size.
(defn- fixed-track [v]
  {:type :fixed :value v :size (double v) :max-size (double v)})

;; Build a flex track (value = number of fr units) at initial size 0.01.
(defn- flex-track [fr]
  {:type :flex :value fr :size 0.01 :max-size ##Inf})

;; Build a parent frame for column testing with given column-gap.
(defn- auto-col-parent
  ([] (auto-col-parent 0))
  ([column-gap]
   (cts/setup-shape
    {:type :frame
     :layout :grid
     :layout-grid-dir :row
     :layout-padding-type :multiple
     :layout-padding {:p1 0 :p2 0 :p3 0 :p4 0}
     :layout-gap {:column-gap column-gap :row-gap 0}
     :x 0 :y 0 :width 500 :height 500})))

;; Build a parent frame for row type testing with given row-gap.
(defn- auto-row-parent
  ([] (auto-row-parent 0))
  ([row-gap]
   (cts/setup-shape
    {:type :frame
     :layout :grid
     :layout-grid-dir :row
     :layout-padding-type :multiple
     :layout-padding {:p1 0 :p2 0 :p3 0 :p4 0}
     :layout-gap {:column-gap 0 :row-gap row-gap}
     :x 0 :y 0 :width 500 :height 500})))

;; Generic frame-bounds (large enough not to interfere).
(def ^:private frame-bounds
  (grc/rect->points (grc/make-rect 0 0 500 500)))

;; Build a cell map for a single shape occupying column/row at given span.
;; col and row are 1-based.
(defn- make-cell
  [shape-id col row col-span row-span]
  {:shapes [shape-id]
   :column col :column-span col-span
   :row row    :row-span    row-span})

;; ---------------------------------------------------------------------------
;; Note on set-auto-multi-span indexing
;; ---------------------------------------------------------------------------
;;
;; Inside set-auto-multi-span, indexed-tracks is computed as:
;;   from-idx = clamp(col - 1, 0, count-1)
;;   to-idx   = clamp((col - 1) + col-span, 0, count-1)
;;   indexed-tracks = subvec(enumerate(tracks), from-idx, to-idx)
;;
;; Because to-idx is clamped to (dec count), the LAST track of the span is
;; always excluded unless there is at least one extra track beyond the span.
;;
;; Practical implication for tests: to cover N spanned tracks, provide a
;; track-list with at least N+1 tracks (the extra track acts as a sentinel
;; that absorbs the off-by-one from the clamp).
;;
;; Example: col=1, span=2, 3 total tracks:
;;   to-idx = clamp(0+2, 0, 2) = 2  →  subvec(v, 0, 2) = [track0, track1] ✓
;;
;; Tests that deliberately check boundary behavior (flex exclusion,
;; non-spanned tracks) use 2 total tracks so only track 0 is covered.

;; ---------------------------------------------------------------------------
;; Tests: column-gap with justify-content  (case → cond fix)
;; ---------------------------------------------------------------------------
;;
;; In get-cell-data, column-gap and row-gap were computed with (case ...)
;; using boolean locals as dispatch values.  case compares compile-time
;; constants, so those branches never matched at runtime.  Fixed with cond.

(t/deftest grid-column-gap-space-evenly
  (t/testing "justify-content :space-evenly increases column-gap correctly"
    ;; 2 fixed cols × 50 px = 100 px occupied; bound-width = 200; free = 100
    ;; formula: free / (num-cols + 1) = 100/3 ≈ 33.33
    (let [frame  (make-grid-frame :layout-justify-content :space-evenly
                                  :layout-gap {:column-gap 0 :row-gap 0})
          bounds (bounds-for frame)
          result (gld/calc-layout-data frame bounds [] {} {})
          col-gap (:column-gap result)]
      (t/is (mth/close? (/ 100.0 3.0) col-gap 0.01)))))

(t/deftest grid-column-gap-space-around
  (t/testing "justify-content :space-around increases column-gap correctly"
    ;; free = 100; formula: 100 / num-cols = 100/2 = 50
    (let [frame  (make-grid-frame :layout-justify-content :space-around
                                  :layout-gap {:column-gap 0 :row-gap 0})
          bounds (bounds-for frame)
          result (gld/calc-layout-data frame bounds [] {} {})
          col-gap (:column-gap result)]
      (t/is (mth/close? 50.0 col-gap 0.01)))))

(t/deftest grid-column-gap-space-between
  (t/testing "justify-content :space-between increases column-gap correctly"
    ;; free = 100; num-cols = 2; formula: 100 / (2-1) = 100
    (let [frame  (make-grid-frame :layout-justify-content :space-between
                                  :layout-gap {:column-gap 0 :row-gap 0})
          bounds (bounds-for frame)
          result (gld/calc-layout-data frame bounds [] {} {})
          col-gap (:column-gap result)]
      (t/is (mth/close? 100.0 col-gap 0.01)))))

(t/deftest grid-column-gap-auto-width-bypasses-justify-content
  (t/testing "auto-width? bypasses justify-content gap recalc → gap stays as initial"
    (let [frame  (make-grid-frame :layout-justify-content :space-evenly
                                  :layout-gap {:column-gap 5 :row-gap 0}
                                  :layout-item-h-sizing :auto)
          bounds (bounds-for frame)
          result (gld/calc-layout-data frame bounds [] {} {})
          col-gap (:column-gap result)]
      (t/is (mth/close? 5.0 col-gap 0.01)))))

;; ---------------------------------------------------------------------------
;; Tests: set-auto-multi-span
;; ---------------------------------------------------------------------------
;;
;; set-auto-multi-span grows auto tracks to accommodate children whose cell
;; spans more than one track column (or row), but only for spans that contain
;; no flex tracks (those are handled by set-flex-multi-span).
;;
;; The function signature:
;;   (set-auto-multi-span parent track-list children-map shape-cells
;;                        bounds objects type)
;;   type  – :column or :row
;;   children-map – {shape-id [child-bounds child-shape]}
;;   shape-cells  – {cell-id cell-map}

(t/deftest set-auto-multi-span-span-1-cells-ignored
  (t/testing "span=1 cells are filtered out; track-list is unchanged"
    (let [sid    (random-uuid)
          child  (make-child 200 100)
          ;; 2 tracks + 1 sentinel (so the span would cover tracks 0-1 if span were 2)
          tracks [(auto-track) (auto-track) (auto-track)]
          cells  {:c1 (make-cell sid 1 1 1 1)}  ; span = 1 → ignored
          cmap   {sid [(child-bounds 200 100) child]}
          result (gld/set-auto-multi-span (auto-col-parent) tracks cmap cells frame-bounds {} :column)]
      (t/is (mth/close? 0.01 (:size (nth result 0)) 0.001))
      (t/is (mth/close? 0.01 (:size (nth result 1)) 0.001))
      (t/is (mth/close? 0.01 (:size (nth result 2)) 0.001)))))

(t/deftest set-auto-multi-span-empty-cells
  (t/testing "empty shape-cells → track-list unchanged"
    (let [tracks [(auto-track) (auto-track)]
          result (gld/set-auto-multi-span (auto-col-parent) tracks {} {} frame-bounds {} :column)]
      (t/is (mth/close? 0.01 (:size (nth result 0)) 0.001))
      (t/is (mth/close? 0.01 (:size (nth result 1)) 0.001)))))

(t/deftest set-auto-multi-span-two-auto-tracks-split-evenly
  (t/testing "child spanning 2 auto tracks (with sentinel): budget split between the 2 covered tracks"
    ;; 3 tracks total (sentinel at index 2 keeps to-idx from being clamped).
    ;; col=1, span=2:
    ;;   from-idx = clamp(0, 0, 2) = 0
    ;;   to-idx   = clamp(2, 0, 2) = 2
    ;;   subvec(enumerate, 0, 2) = [[0, auto0], [1, auto1]]
    ;; size-to-allocate = 200 (child width, no gap)
    ;; allocate-auto-tracks pass 1 (non-assigned = both):
    ;;   idx0: max(0.01, 200/2, 0.01) = 100; rem = 100
    ;;   idx1: max(0.01, 100/1, 0.01) = 100; rem = 0
    ;; pass 2 (to-allocate=0): no change → both 100
    ;; sentinel track 2 is never spanned → stays at 0.01.
    (let [sid    (random-uuid)
          child  (make-child 200 100)
          tracks [(auto-track) (auto-track) (auto-track)]   ; sentinel at [2]
          cells  {:c1 (make-cell sid 1 1 2 1)}
          cmap   {sid [(child-bounds 200 100) child]}
          result (gld/set-auto-multi-span (auto-col-parent) tracks cmap cells frame-bounds {} :column)]
      (t/is (mth/close? 100.0 (:size (nth result 0)) 0.001))
      (t/is (mth/close? 100.0 (:size (nth result 1)) 0.001))
      ;; sentinel unaffected
      (t/is (mth/close? 0.01  (:size (nth result 2)) 0.001)))))

(t/deftest set-auto-multi-span-gap-deducted-from-budget
  (t/testing "column-gap is subtracted once per extra span track from size-to-allocate"
    ;; child width = 210, column-gap = 10, span = 2
    ;; size-to-allocate = child-min-width - gap*(span-1) = 210 - 10*1 = 200
    ;; 3 tracks (sentinel at [2]) → indexed = [[0,auto],[1,auto]]
    ;; each auto track gets 100
    (let [sid    (random-uuid)
          child  (make-child 210 100)
          tracks [(auto-track) (auto-track) (auto-track)]
          cells  {:c1 (make-cell sid 1 1 2 1)}
          cmap   {sid [(child-bounds 210 100) child]}
          result (gld/set-auto-multi-span (auto-col-parent 10) tracks cmap cells frame-bounds {} :column)]
      (t/is (mth/close? 100.0 (:size (nth result 0)) 0.001))
      (t/is (mth/close? 100.0 (:size (nth result 1)) 0.001))
      (t/is (mth/close? 0.01  (:size (nth result 2)) 0.001)))))

(t/deftest set-auto-multi-span-fixed-track-reduces-budget
  (t/testing "fixed track in span is deducted from budget; only the auto track grows"
    ;; tracks: [fixed 60, auto 0.01, auto-sentinel]  (sentinel at [2])
    ;; col=1, span=2 → indexed = [[0, fixed60], [1, auto]]
    ;; find-auto-allocations: fixed→subtract 60; auto→keep
    ;;   to-allocate after fixed = 200 - 60 = 140; indexed-auto = [[1, auto]]
    ;; pass 1: idx1: max(0.01, 140/1, 0.01) = 140
    ;; apply: track0 = max(60, 0) = 60; track1 = max(0.01, 140) = 140
    (let [sid    (random-uuid)
          child  (make-child 200 100)
          tracks [(fixed-track 60) (auto-track) (auto-track)]
          cells  {:c1 (make-cell sid 1 1 2 1)}
          cmap   {sid [(child-bounds 200 100) child]}
          result (gld/set-auto-multi-span (auto-col-parent) tracks cmap cells frame-bounds {} :column)]
      (t/is (mth/close? 60.0  (:size (nth result 0)) 0.001))
      (t/is (mth/close? 140.0 (:size (nth result 1)) 0.001))
      (t/is (mth/close? 0.01  (:size (nth result 2)) 0.001)))))

(t/deftest set-auto-multi-span-child-smaller-than-existing-tracks
  (t/testing "when child is smaller than the existing track sizes, tracks are not shrunk"
    ;; tracks: [auto 80, auto 80, auto-sentinel]
    ;; child width = 50; size-to-allocate = 50
    ;; indexed = [[0, auto80], [1, auto80]]
    ;; pass 1 (non-assigned, to-alloc=50):
    ;;   idx0: max(0.01, 50/2, 80) = 80; rem = 50-80 = -30
    ;;   idx1: max(0.01, max(-30,0)/1, 80) = 80
    ;; pass 2 (to-alloc=max(-30,0)=0): same max, no change
    ;; both tracks stay at 80
    (let [sid    (random-uuid)
          child  (make-child 50 100)
          tracks [{:type :auto :size 80.0 :max-size ##Inf}
                  {:type :auto :size 80.0 :max-size ##Inf}
                  (auto-track)]
          cells  {:c1 (make-cell sid 1 1 2 1)}
          cmap   {sid [(child-bounds 50 100) child]}
          result (gld/set-auto-multi-span (auto-col-parent) tracks cmap cells frame-bounds {} :column)]
      (t/is (mth/close? 80.0 (:size (nth result 0)) 0.001))
      (t/is (mth/close? 80.0 (:size (nth result 1)) 0.001)))))

(t/deftest set-auto-multi-span-flex-track-in-span-excluded
  (t/testing "cells whose span contains a flex track are skipped (handled by set-flex-multi-span)"
    ;; tracks: [flex 1fr, auto]   col=1, span=2 → has-flex-track? = true → cell excluded
    ;; 2 tracks total (no sentinel needed since the cell is excluded before indexing)
    (let [sid    (random-uuid)
          child  (make-child 300 100)
          tracks [(flex-track 1) (auto-track)]
          cells  {:c1 (make-cell sid 1 1 2 1)}
          cmap   {sid [(child-bounds 300 100) child]}
          result (gld/set-auto-multi-span (auto-col-parent) tracks cmap cells frame-bounds {} :column)]
      (t/is (mth/close? 0.01 (:size (nth result 0)) 0.001))
      (t/is (mth/close? 0.01 (:size (nth result 1)) 0.001)))))

(t/deftest set-auto-multi-span-non-spanned-track-unaffected
  (t/testing "tracks outside the span keep their size – tests (get allocated %1 0) default"
    ;; 4 tracks; child at col=2 span=2 → indexed covers tracks 1 and 2 (sentinel [3]).
    ;; Track 0 (before the span) and track 3 (sentinel) are never allocated.
    ;; from-idx = clamp(2-1, 0, 3) = 1
    ;; to-idx   = clamp((2-1)+2, 0, 3) = 3
    ;; subvec(enumerate, 1, 3) = [[1,auto],[2,auto]]
    ;; size-to-allocate = 200 → both indexed tracks get 100
    ;; apply: track0 = max(0.01, get({},0,0)) = max(0.01,0) = 0.01  ← uses default 0
    ;;        track1 = max(0.01, 100) = 100
    ;;        track2 = max(0.01, 100) = 100
    ;;        track3 = max(0.01, get({},3,0)) = 0.01 (sentinel)
    (let [sid    (random-uuid)
          child  (make-child 200 100)
          tracks [(auto-track) (auto-track) (auto-track) (auto-track)]
          cells  {:c1 (make-cell sid 2 1 2 1)}
          cmap   {sid [(child-bounds 200 100) child]}
          result (gld/set-auto-multi-span (auto-col-parent) tracks cmap cells frame-bounds {} :column)]
      ;; track before span: size stays at 0.01 (default 0 from missing allocation entry)
      (t/is (mth/close? 0.01  (:size (nth result 0)) 0.001))
      ;; spanned tracks grow
      (t/is (mth/close? 100.0 (:size (nth result 1)) 0.001))
      (t/is (mth/close? 100.0 (:size (nth result 2)) 0.001))
      ;; sentinel after span also unaffected
      (t/is (mth/close? 0.01  (:size (nth result 3)) 0.001)))))

(t/deftest set-auto-multi-span-row-type
  (t/testing ":row type uses :row/:row-span and grows row tracks by child height"
    ;; child height = 200, row-gap = 0, row=1 span=2, 3 row tracks (sentinel at [2])
    ;; from-idx=0, to-idx=clamp(2,0,2)=2 → [[0,auto],[1,auto]]
    ;; size-to-allocate = 200 → each row track gets 100
    (let [sid    (random-uuid)
          child  (make-child 100 200)
          tracks [(auto-track) (auto-track) (auto-track)]
          cells  {:c1 (make-cell sid 1 1 1 2)}
          cmap   {sid [(child-bounds 100 200) child]}
          result (gld/set-auto-multi-span (auto-row-parent) tracks cmap cells frame-bounds {} :row)]
      (t/is (mth/close? 100.0 (:size (nth result 0)) 0.001))
      (t/is (mth/close? 100.0 (:size (nth result 1)) 0.001))
      (t/is (mth/close? 0.01  (:size (nth result 2)) 0.001)))))

(t/deftest set-auto-multi-span-row-gap-deducted
  (t/testing "row-gap is deducted from budget for :row type"
    ;; child height = 210, row-gap = 10, row-span = 2
    ;; size-to-allocate = 210 - 10*1 = 200 → each track gets 100
    (let [sid    (random-uuid)
          child  (make-child 100 210)
          tracks [(auto-track) (auto-track) (auto-track)]
          cells  {:c1 (make-cell sid 1 1 1 2)}
          cmap   {sid [(child-bounds 100 210) child]}
          result (gld/set-auto-multi-span (auto-row-parent 10) tracks cmap cells frame-bounds {} :row)]
      (t/is (mth/close? 100.0 (:size (nth result 0)) 0.001))
      (t/is (mth/close? 100.0 (:size (nth result 1)) 0.001))
      (t/is (mth/close? 0.01  (:size (nth result 2)) 0.001)))))

(t/deftest set-auto-multi-span-smaller-span-processed-first
  (t/testing "cells are sorted by span ascending (sort-by span -): smaller span allocates first"
    ;; NOTE: (sort-by prop-span -) uses `-` as a comparator; this yields ascending
    ;; order (smaller span first), not descending as the code comment implies.
    ;;
    ;; 4 tracks (sentinel at [3]):
    ;;   cell-B: col=1 span=2 (covers indexed [0,1])  – processed first (span=2)
    ;;   cell-A: col=1 span=3 (covers indexed [0,1,2]) – processed second (span=3)
    ;;
    ;; cell-B: child=100px, to-allocate=100.
    ;;   non-assigned=[0,1]; pass1: idx0→max(0.01,50,0.01)=50; idx1→max(0.01,50,0.01)=50
    ;;   allocated = {0:50, 1:50}
    ;;
    ;; cell-A: child=300px, to-allocate=300.
    ;;   indexed=[0,1,2]; non-assigned=[2] (tracks 0,1 already allocated)
    ;;   pass1 (non-assigned only): idx2→max(0.01,300/1,0.01)=300 ; rem=0
    ;;   pass2 (to-alloc=0): max preserves existing values → no change
    ;;   allocated = {0:50, 1:50, 2:300}
    ;;
    ;; Final: track0=50, track1=50, track2=300, track3(sentinel)=0.01
    (let [sid-a  (random-uuid)
          sid-b  (random-uuid)
          child-a (make-child 300 100)
          child-b (make-child 100 100)
          tracks  [(auto-track) (auto-track) (auto-track) (auto-track)]  ; sentinel at [3]
          cells   {:ca (make-cell sid-a 1 1 3 1)
                   :cb (make-cell sid-b 1 1 2 1)}
          cmap    {sid-a [(child-bounds 300 100) child-a]
                   sid-b [(child-bounds 100 100) child-b]}
          result  (gld/set-auto-multi-span (auto-col-parent) tracks cmap cells frame-bounds {} :column)]
      (t/is (mth/close? 50.0  (:size (nth result 0)) 0.001))
      (t/is (mth/close? 50.0  (:size (nth result 1)) 0.001))
      (t/is (mth/close? 300.0 (:size (nth result 2)) 0.001))
      (t/is (mth/close? 0.01  (:size (nth result 3)) 0.001)))))

(t/deftest set-auto-multi-span-all-fixed-tracks-in-span
  (t/testing "when all spanned tracks are fixed, no auto allocation occurs; fixed tracks unchanged"
    ;; tracks: [fixed 100, fixed 100, auto-sentinel]
    ;; col=1, span=2 → indexed = [[0,fixed100],[1,fixed100]]
    ;; find-auto-allocations: both fixed → auto-indexed-tracks = []
    ;; allocate-auto-tracks on empty list → no entries in allocated map
    ;; apply: track0 = max(100, get({},0,0)) = max(100,0) = 100 (unchanged)
    ;;        track1 = max(100, get({},1,0)) = max(100,0) = 100 (unchanged)
    (let [sid    (random-uuid)
          child  (make-child 50 100)
          tracks [(fixed-track 100) (fixed-track 100) (auto-track)]
          cells  {:c1 (make-cell sid 1 1 2 1)}
          cmap   {sid [(child-bounds 50 100) child]}
          result (gld/set-auto-multi-span (auto-col-parent) tracks cmap cells frame-bounds {} :column)]
      (t/is (mth/close? 100.0 (:size (nth result 0)) 0.001))
      (t/is (mth/close? 100.0 (:size (nth result 1)) 0.001)))))
