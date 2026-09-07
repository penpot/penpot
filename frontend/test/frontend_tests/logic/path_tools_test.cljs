;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC Sucursal en España SL


(ns frontend-tests.logic.path-tools-test
  (:require
   [app.common.geom.point :as gpt]
   [app.common.geom.rect :as grc]
   [app.common.types.path :as path]
   [app.main.data.workspace.path.drawing :as path.drawing]
   [app.main.data.workspace.path.edition :as path.edition]
   [app.main.data.workspace.path.helpers :as path.helpers]
   [app.main.data.workspace.path.selection :as path.selection]
   [app.main.data.workspace.path.state :as path.state]
   [app.main.data.workspace.path.tools :as path.tools]
   [beicon.v2.core :as rx]
   [cljs.test :as t :include-macros true]
   [frontend-tests.logic.path-test-helpers :as pth]
   [potok.v2.core :as ptk]))

(t/deftest mixed-node-conversions-only-change-opposite-node-type
  (let [id             (random-uuid)
        content        (pth/mixed-corner-curve-content)
        points         (path/get-points content)
        corner-point   (first points)
        curve-point    (second points)
        state          (pth/selectable-path-state
                        id content
                        {:nodes #{0 1} :segments #{} :handlers #{}})
        curved-state   (ptk/update (path.tools/make-curve) state)
        cornered-state (ptk/update (path.tools/make-corner) state)
        curved-content (path.state/get-path curved-state :content)
        corner-content (path.state/get-path cornered-state :content)]
    (t/is (path/is-curve-point? curved-content corner-point))
    (t/is (= (nth content 2) (nth curved-content 2)))
    (t/is (not (path/is-curve-point? corner-content corner-point)))
    (t/is (not (path/is-curve-point? corner-content curve-point)))))

(t/deftest plain-and-shift-selection-work-across-path-element-types
  (let [id      (random-uuid)
        content (pth/selectable-path-content)
        state   (pth/selectable-path-state
                 id content
                 {:nodes #{0}
                  :segments #{1}
                  :handlers #{[1 :c1]}})
        state'  (ptk/update (path.selection/select-handler 1 :c2 false) state)
        state'' (ptk/update (path.selection/select-segment 2 true) state')
        state''' (ptk/update (path.selection/select-handler 1 :c2 true) state'')
        state'''' (ptk/update (path.selection/select-handler 2 :c1 true) state''')]
    (t/is (= {:nodes #{}
              :segments #{}
              :handlers #{[1 :c2]}}
             (get-in state' [:workspace-local :edit-path id :selection])))
    (t/is (= {:nodes #{}
              :segments #{2}
              :handlers #{[1 :c2]}}
             (get-in state'' [:workspace-local :edit-path id :selection])))
    (t/is (= {:nodes #{}
              :segments #{2}
              :handlers #{[2 :c1]}}
             (get-in state'''' [:workspace-local :edit-path id :selection])))))

(t/deftest path-area-selection-prioritizes-nodes-over-segments-over-handlers
  (let [id        (random-uuid)
        content   (pth/selectable-path-content)
        selection path.helpers/empty-selection
        state     (pth/selectable-path-state id content selection)
        ;; Contains segment 1 and handler [1 :c1] but no node
        rect      (grc/make-rect 1 -2 3 4)
        state'    (ptk/update (path.selection/select-path-area
                               rect selection false)
                              state)
        ;; Contains node 0, segment 1 and handler [1 :c1]
        node-rect (grc/make-rect -1 -1 4 2)
        state''   (ptk/update (path.selection/select-path-area
                               node-rect selection false)
                              state)]
    (t/is (= {:nodes #{}
              :segments #{1}
              :handlers #{}}
             (get-in state' [:workspace-local :edit-path id :selection])))
    (t/is (= {:nodes #{0}
              :segments #{}
              :handlers #{}}
             (get-in state'' [:workspace-local :edit-path id :selection])))))

(t/deftest path-area-selection-picks-handlers-only-when-nothing-else-is-inside
  (let [id        (random-uuid)
        ;; Curve bulging up to y 7.5 with both handlers on y 10, away
        ;; from the curve itself
        content   (path/content
                   [{:command :move-to
                     :params {:x 0 :y 0}}
                    {:command :curve-to
                     :params {:c1x 0 :c1y 10
                              :c2x 10 :c2y 10
                              :x 10 :y 0}}])
        selection path.helpers/empty-selection
        state     (pth/selectable-path-state id content selection)
        ;; Contains only the [1 :c1] handler control point
        handler-rect (grc/make-rect -1 9 2 2)
        state'    (ptk/update (path.selection/select-path-area
                               handler-rect selection false)
                              state)
        ;; Contains both handlers and the top of the curve
        mixed-rect (grc/make-rect -1 5 12 7)
        state''   (ptk/update (path.selection/select-path-area
                               mixed-rect selection false)
                              state)]
    (t/is (= {:nodes #{}
              :segments #{}
              :handlers #{[1 :c1]}}
             (get-in state' [:workspace-local :edit-path id :selection])))
    (t/is (= {:nodes #{}
              :segments #{1}
              :handlers #{}}
             (get-in state'' [:workspace-local :edit-path id :selection])))))

(t/deftest path-area-selection-ignores-empty-buffer-emissions
  (let [id        (random-uuid)
        content   (pth/selectable-path-content)
        selection path.helpers/empty-selection
        state     (pth/selectable-path-state id content selection)]
    (t/is (= state
             (ptk/update (path.selection/select-path-area
                          nil selection false)
                         state)))))

(t/deftest selected-segments-resolve-to-unique-endpoint-nodes
  (let [content (pth/selectable-path-content)]
    (t/is (= #{0 1}
             (path.helpers/segment-node-indices content #{1})))
    (t/is (= #{0 1 2}
             (path.helpers/segment-node-indices content #{1 2})))))

(t/deftest moving-selected-segments-translates-endpoints-and-handlers
  (let [id        (random-uuid)
        content   (pth/selectable-path-content)
        selection {:nodes #{}
                   :segments #{1}
                   :handlers #{}}
        state     (pth/selectable-path-state id content selection)
        event     (path.edition/move-selected-path-segment
                   (gpt/point 5 0)
                   (gpt/point 8 4))
        state'    (ptk/update event state)
        modifiers (get-in state' [:workspace-local :edit-path id :content-modifiers])
        content'  (path/apply-content-modifiers content modifiers)]
    (t/is (= (gpt/point 3 4) (path.helpers/node-position content' 0)))
    (t/is (= (gpt/point 13 4) (path.helpers/node-position content' 1)))
    (t/is (= (gpt/point 5 4) (path/get-handler-point content' 1 :c1)))
    (t/is (= (gpt/point 11 4) (path/get-handler-point content' 1 :c2)))
    (t/is (= (gpt/point 15 4) (path/get-handler-point content' 2 :c1)))))

(t/deftest moving-a-segment-between-selected-nodes-moves-the-node-selection
  ;; A segment between selected nodes moves with the node selection.
  (let [id        (random-uuid)
        content   (pth/selectable-path-content)
        selection {:nodes #{0 1} :segments #{} :handlers #{}}
        state     (pth/selectable-path-state id content selection)
        event     (path.edition/move-selected-path-segment
                   (gpt/point 5 0)
                   (gpt/point 8 4))
        state'    (ptk/update event state)
        modifiers (get-in state' [:workspace-local :edit-path id :content-modifiers])
        content'  (path/apply-content-modifiers content modifiers)]
    ;; both selected nodes translate by (+3,+4); the unselected node stays put
    (t/is (= (gpt/point 3 4) (path.helpers/node-position content' 0)))
    (t/is (= (gpt/point 13 4) (path.helpers/node-position content' 1)))
    (t/is (= (gpt/point 20 0) (path.helpers/node-position content' 2)))))

(t/deftest moving-selected-opposite-handlers-translates-both
  (let [id        (random-uuid)
        content   (pth/selectable-path-content)
        selection {:nodes #{}
                   :segments #{}
                   :handlers #{[1 :c2] [2 :c1]}}
        state     (pth/selectable-path-state id content selection)
        event     (path.edition/modify-selected-handlers
                   id [1 :c2] {} 3 4 :smart true)
        state'    (ptk/update event state)
        modifiers (get-in state' [:workspace-local :edit-path id :content-modifiers])
        content'  (path/apply-content-modifiers content modifiers)]
    (t/is (= {:c2x 3 :c2y 4} (get modifiers 1)))
    (t/is (= {:c1x 3 :c1y 4} (get modifiers 2)))
    (t/is (= (gpt/point 11 4) (path/get-handler-point content' 1 :c2)))
    (t/is (= (gpt/point 15 4) (path/get-handler-point content' 2 :c1)))))

(t/deftest moving-selected-handlers-honours-each-explicit-node-mode
  (let [id        (random-uuid)
        content   (path/content
                   [{:command :move-to :params {:x 0 :y 0}}
                    {:command :curve-to
                     :params {:c1x 2 :c1y 0 :c2x 8 :c2y 0 :x 10 :y 0}}
                    {:command :curve-to
                     :params {:c1x 12 :c1y 0 :c2x 18 :c2y 0 :x 20 :y 0}}
                    {:command :curve-to
                     :params {:c1x 22 :c1y 0 :c2x 28 :c2y 0 :x 30 :y 0}}])
        selection {:nodes #{}
                   :segments #{}
                   :handlers #{[2 :c1] [3 :c1]}}
        state     (-> (pth/selectable-path-state id content selection)
                      (assoc-in [:workspace-local :edit-path id :handler-types]
                                {1 :independent 2 :mirror}))
        event     (path.edition/modify-selected-handlers
                   id [2 :c1] {} 3 4 :independent true)
        state'    (ptk/update event state)
        modifiers (get-in state' [:workspace-local :edit-path id :content-modifiers])
        content'  (path/apply-content-modifiers content modifiers)]
    ;; Selected handlers receive the same drag delta.
    (t/is (= (gpt/point 15 4) (path/get-handler-point content' 2 :c1)))
    (t/is (= (gpt/point 25 4) (path/get-handler-point content' 3 :c1)))
    ;; Each node applies its own mode to the opposite handle.
    (t/is (= (gpt/point 8 0) (path/get-handler-point content' 1 :c2)))
    (t/is (= (gpt/point 15 -4) (path/get-handler-point content' 2 :c2)))))

(t/deftest arrow-move-nudges-selected-handlers
  ;; Arrow keys nudge selected handlers.
  (let [id       (random-uuid)
        content  (pth/selectable-path-content)
        state    (pth/selectable-path-state id content
                                            {:nodes #{} :segments #{} :handlers #{[2 :c1]}})
        state'   (ptk/update (path.edition/set-move-modifier [] #{[2 :c1]} (gpt/point 0 5))
                             state)
        mods     (get-in state' [:workspace-local :edit-path id :content-modifiers])
        content' (path/apply-content-modifiers content mods)]
    (t/is (= {:c1x 0 :c1y 5} (get mods 2)))
    ;; [2 :c1] base (12,0) -> (12,5); node 1 and its opposite handle stay put
    (t/is (= (gpt/point 12 5) (path/get-handler-point content' 2 :c1)))
    (t/is (= (gpt/point 10 0) (path.helpers/node-position content' 1)))
    (t/is (= (gpt/point 8 0) (path/get-handler-point content' 1 :c2)))))

(t/deftest arrow-move-nudges-selected-segment-endpoints
  ;; Arrow keys nudge segment endpoints and their handles.
  (let [id       (random-uuid)
        content  (pth/selectable-path-content)
        state    (pth/selectable-path-state id content
                                            {:nodes #{} :segments #{1} :handlers #{}})
        node-idx (path.helpers/segment-node-indices content #{1})
        points   (path.helpers/node-positions content node-idx)
        state'   (ptk/update (path.edition/set-move-modifier points #{} (gpt/point 0 5))
                             state)
        mods     (get-in state' [:workspace-local :edit-path id :content-modifiers])
        content' (path/apply-content-modifiers content mods)]
    ;; segment 1 connects node 0 (0,0) and node 1 (10,0); both move by (0,5)
    (t/is (= (gpt/point 0 5) (path.helpers/node-position content' 0)))
    (t/is (= (gpt/point 10 5) (path.helpers/node-position content' 1)))
    ;; the endpoint nodes' handles move rigidly with them
    (t/is (= (gpt/point 2 5) (path/get-handler-point content' 1 :c1)))
    (t/is (= (gpt/point 12 5) (path/get-handler-point content' 2 :c1)))))

(t/deftest align-nodes-aligns-selected-nodes-to-an-edge
  ;; Aligning nodes updates the drawing content.
  (let [id       (random-uuid)
        content  (path/content
                  [{:command :move-to :params {:x 0 :y 0}}
                   {:command :line-to :params {:x 10 :y 4}}
                   {:command :line-to :params {:x 4 :y 20}}])
        state    (pth/selectable-path-state id content
                                            {:nodes #{0 1 2} :segments #{} :handlers #{}})
        state'   (ptk/update (path.tools/align-nodes :hleft) state)
        content' (get-in state' [:workspace-drawing :object :content])]
    ;; every selected node's x becomes the min x (0), y is untouched
    (t/is (= (gpt/point 0 0)  (path.helpers/node-position content' 0)))
    (t/is (= (gpt/point 0 4)  (path.helpers/node-position content' 1)))
    (t/is (= (gpt/point 0 20) (path.helpers/node-position content' 2)))))

(t/deftest distribute-nodes-spaces-selected-nodes-evenly
  (let [id       (random-uuid)
        content  (path/content
                  [{:command :move-to :params {:x 0 :y 0}}
                   {:command :line-to :params {:x 3 :y 5}}
                   {:command :line-to :params {:x 10 :y 9}}])
        state    (pth/selectable-path-state id content
                                            {:nodes #{0 1 2} :segments #{} :handlers #{}})
        state'   (ptk/update (path.tools/distribute-nodes :horizontal) state)
        content' (get-in state' [:workspace-drawing :object :content])]
    ;; the middle node is centered on x between the two extremes (0 and 10)
    (t/is (= (gpt/point 5 5)  (path.helpers/node-position content' 1)))
    ;; the extreme nodes stay put
    (t/is (= (gpt/point 0 0)  (path.helpers/node-position content' 0)))
    (t/is (= (gpt/point 10 9) (path.helpers/node-position content' 2)))))

(t/deftest set-selection-coordinate-moves-selected-points
  ;; Coordinate edits move selected nodes and handlers.
  (let [id      (random-uuid)
        content (path/content
                 [{:command :move-to :params {:x 0 :y 0}}
                  {:command :line-to :params {:x 10 :y 0}}
                  {:command :line-to :params {:x 20 :y 0}}])]
    ;; single node: only that node moves
    (let [state    (pth/selectable-path-state id content
                                              {:nodes #{1} :segments #{} :handlers #{}})
          state'   (ptk/update (path.tools/set-selection-coordinate :y 7) state)
          content' (get-in state' [:workspace-drawing :object :content])]
      (t/is (= (gpt/point 10 7) (path.helpers/node-position content' 1)))
      (t/is (= (gpt/point 0 0)  (path.helpers/node-position content' 0)))
      (t/is (= (gpt/point 20 0) (path.helpers/node-position content' 2))))
    ;; multi node: every selected node's coordinate is set to the value
    (let [state    (pth/selectable-path-state id content
                                              {:nodes #{0 2} :segments #{} :handlers #{}})
          state'   (ptk/update (path.tools/set-selection-coordinate :x 5) state)
          content' (get-in state' [:workspace-drawing :object :content])]
      (t/is (= (gpt/point 5 0)  (path.helpers/node-position content' 0)))
      (t/is (= (gpt/point 5 0)  (path.helpers/node-position content' 2)))
      (t/is (= (gpt/point 10 0) (path.helpers/node-position content' 1)))))
  ;; a coincident closed-seam node moves as one logical node
  (let [id      (random-uuid)
        content (path/content
                 [{:command :move-to :params {:x 0 :y 0}}
                  {:command :line-to :params {:x 10 :y 0}}
                  {:command :line-to :params {:x 0 :y 0}}])
        state   (pth/selectable-path-state id content
                                           {:nodes #{0} :segments #{} :handlers #{}})
        state'  (ptk/update (path.tools/set-selection-coordinate :y 7) state)
        content' (get-in state' [:workspace-drawing :object :content])]
    (t/is (= (gpt/point 0 7) (path.helpers/node-position content' 0)))
    (t/is (= (gpt/point 0 7) (path.helpers/node-position content' 2))))
  ;; a selected handler on an independent node moves only its own control point
  (let [id      (random-uuid)
        content (path/content
                 [{:command :move-to :params {:x 0 :y 0}}
                  {:command :curve-to :params {:c1x 2 :c1y 2 :c2x 8 :c2y 2 :x 10 :y 0}}])
        state   (pth/selectable-path-state id content
                                           {:nodes #{} :segments #{} :handlers #{[1 :c1]}})
        state'  (ptk/update (path.tools/set-selection-coordinate :x 4) state)
        curve   (nth (get-in state' [:workspace-drawing :object :content]) 1)]
    ;; c1 x set to 4; c1y, c2 and the anchor untouched
    (t/is (= 4 (get-in curve [:params :c1x])))
    (t/is (= 2 (get-in curve [:params :c1y])))
    (t/is (= 8 (get-in curve [:params :c2x])))
    (t/is (= 10 (get-in curve [:params :x])))))

(t/deftest set-selection-coordinate-mirrors-opposite-handler
  ;; Moving a mirrored handler updates its opposite.
  (let [id      (random-uuid)
        ;; node 1 (10,0) has collinear equal handles: c2 of cmd1 at (8,-2) and
        ;; c1 of cmd2 at (12,2) — a mirror node by geometry
        content (path/content
                 [{:command :move-to :params {:x 0 :y 0}}
                  {:command :curve-to :params {:c1x 2 :c1y 0 :c2x 8 :c2y -2 :x 10 :y 0}}
                  {:command :curve-to :params {:c1x 12 :c1y 2 :c2x 18 :c2y 0 :x 20 :y 0}}])
        state   (pth/selectable-path-state id content
                                           {:nodes #{} :segments #{} :handlers #{[1 :c2]}})
        state'  (ptk/update (path.tools/set-selection-coordinate :x 6) state)
        content' (get-in state' [:workspace-drawing :object :content])]
    ;; dragged handle c2 of cmd1 -> x=6 (keeps y=-2)
    (t/is (= (gpt/point 6 -2) (path/get-handler-point content' 1 :c2)))
    ;; opposite (c1 of cmd2) mirrors it about the node (10,0): 2*10-6=14, 2*0-(-2)=2
    (t/is (= (gpt/point 14 2) (path/get-handler-point content' 2 :c1)))))

(t/deftest change-to-draw-mode-starts-a-line-from-the-selected-node
  (let [id      (random-uuid)
        content (path/content
                 [{:command :move-to :params {:x 0 :y 0}}
                  {:command :line-to :params {:x 10 :y 0}}
                  {:command :line-to :params {:x 20 :y 0}}])]
    ;; a middle node: opens a new subpath (move-to) at the node and makes it the
    ;; pending origin, so the next click draws a line from it
    (let [state    (pth/selectable-path-state id content
                                              {:nodes #{1} :segments #{} :handlers #{}})
          state'   (ptk/update (path.drawing/change-edit-mode :draw) state)
          content' (get-in state' [:workspace-drawing :object :content])]
      (t/is (= (gpt/point 10 0)
               (get-in state' [:workspace-local :edit-path id :last-point])))
      (t/is (= 4 (count content')))
      (t/is (= :move-to (:command (nth content' 3))))
      (t/is (= (gpt/point 10 0) (path.helpers/node-position content' 3))))
    ;; the drawing tip: just becomes the pending origin (extends), no new subpath
    (let [state    (pth/selectable-path-state id content
                                              {:nodes #{2} :segments #{} :handlers #{}})
          state'   (ptk/update (path.drawing/change-edit-mode :draw) state)
          content' (get-in state' [:workspace-drawing :object :content])]
      (t/is (= (gpt/point 20 0)
               (get-in state' [:workspace-local :edit-path id :last-point])))
      (t/is (= 3 (count content'))))
    ;; nothing selected: no pending line
    (let [state    (pth/selectable-path-state id content
                                              {:nodes #{} :segments #{} :handlers #{}})
          state'   (ptk/update (path.drawing/change-edit-mode :draw) state)]
      (t/is (nil? (get-in state' [:workspace-local :edit-path id :last-point]))))))

(t/deftest set-selection-coordinate-translates-segments
  ;; Coordinate edits translate selected segments by their bounds.
  (let [id      (random-uuid)
        content (path/content
                 [{:command :move-to :params {:x 0 :y 0}}
                  {:command :line-to :params {:x 10 :y 0}}
                  {:command :line-to :params {:x 10 :y 10}}])
        ;; select segment index 2 (the vertical line from (10,0) to (10,10));
        ;; its surrounding rect top-left x is 10
        state   (pth/selectable-path-state id content
                                           {:nodes #{} :segments #{2} :handlers #{}})
        state'  (ptk/update (path.tools/set-selection-coordinate :x 30) state)
        content' (get-in state' [:workspace-drawing :object :content])]
    ;; the segment's endpoint nodes (1 and 2) move +20 in x; node 0 stays
    (t/is (= (gpt/point 0 0)  (path.helpers/node-position content' 0)))
    (t/is (= (gpt/point 30 0) (path.helpers/node-position content' 1)))
    (t/is (= (gpt/point 30 10) (path.helpers/node-position content' 2))))
  ;; Moving a segment attached to a closed seam keeps both seam commands together.
  (let [id       (random-uuid)
        content  (path/content
                  [{:command :move-to :params {:x 0 :y 0}}
                   {:command :line-to :params {:x 10 :y 0}}
                   {:command :line-to :params {:x 10 :y 10}}
                   {:command :line-to :params {:x 0 :y 10}}
                   {:command :line-to :params {:x 0 :y 0}}])
        state    (pth/selectable-path-state id content
                                            {:nodes #{} :segments #{4} :handlers #{}})
        state'   (ptk/update (path.tools/set-selection-coordinate :x 20) state)
        content' (get-in state' [:workspace-drawing :object :content])]
    (t/is (= (gpt/point 20 0) (path.helpers/node-position content' 0)))
    (t/is (= (gpt/point 20 10) (path.helpers/node-position content' 3)))
    (t/is (= (gpt/point 20 0) (path.helpers/node-position content' 4)))))

(t/deftest set-selection-coordinate-translates-mixed-segment-and-node-selection
  ;; Selected segments and nodes translate as one group.
  (let [id       (random-uuid)
        content  (path/content
                  [{:command :move-to :params {:x 0 :y 0}}
                   {:command :line-to :params {:x 10 :y 0}}
                   {:command :line-to :params {:x 20 :y 0}}
                   {:command :line-to :params {:x 30 :y 0}}])
        ;; The combined bounds start at x=0.
        state    (pth/selectable-path-state id content
                                            {:nodes #{0} :segments #{3} :handlers #{}})
        state'   (ptk/update (path.tools/set-selection-coordinate :x 10) state)
        content' (get-in state' [:workspace-drawing :object :content])]
    (t/is (= (gpt/point 10 0) (path.helpers/node-position content' 0)))
    (t/is (= (gpt/point 10 0) (path.helpers/node-position content' 1)))
    (t/is (= (gpt/point 30 0) (path.helpers/node-position content' 2)))
    (t/is (= (gpt/point 40 0) (path.helpers/node-position content' 3)))))

(t/deftest set-selection-coordinate-translates-mixed-segment-and-handler-selection
  ;; Standalone selected handlers translate with the group.
  (let [id       (random-uuid)
        content  (path/content
                  [{:command :move-to :params {:x 0 :y 0}}
                   {:command :curve-to
                    :params {:c1x 2 :c1y 2 :c2x 8 :c2y 2 :x 10 :y 0}}
                   {:command :line-to :params {:x 20 :y 0}}
                   {:command :line-to :params {:x 30 :y 0}}])
        ;; The standalone handler makes the bounds start at x=2.
        state    (pth/selectable-path-state id content
                                            {:nodes #{}
                                             :segments #{3}
                                             :handlers #{[1 :c1]}})
        state'   (ptk/update (path.tools/set-selection-coordinate :x 12) state)
        content' (get-in state' [:workspace-drawing :object :content])]
    (t/is (= (gpt/point 12 2) (path/get-handler-point content' 1 :c1)))
    (t/is (= (gpt/point 0 0) (path.helpers/node-position content' 0)))
    (t/is (= (gpt/point 10 0) (path.helpers/node-position content' 1)))
    (t/is (= (gpt/point 30 0) (path.helpers/node-position content' 2)))
    (t/is (= (gpt/point 40 0) (path.helpers/node-position content' 3)))))

(t/deftest flip-nodes-includes-selected-segment-endpoints
  (let [id       (random-uuid)
        content  (pth/selectable-path-content)
        state    (pth/selectable-path-state id content
                                            {:nodes #{} :segments #{1} :handlers #{}})
        state'   (ptk/update (path.tools/flip-nodes :horizontal) state)
        content' (get-in state' [:workspace-drawing :object :content])]
    ;; segment 1's endpoints (nodes 0 and 1) mirror across their bbox centre (x=5)
    (t/is (= (gpt/point 10 0) (path.helpers/node-position content' 0)))
    (t/is (= (gpt/point 0 0)  (path.helpers/node-position content' 1)))
    ;; node 2 is not an endpoint of segment 1, so it stays put
    (t/is (= (gpt/point 20 0) (path.helpers/node-position content' 2)))))

(t/deftest merge-nodes-includes-selected-segment-endpoints
  (let [id       (random-uuid)
        content  (pth/selectable-path-content)
        state    (pth/selectable-path-state id content
                                            {:nodes #{} :segments #{1} :handlers #{}})
        state'   (ptk/update (path.tools/merge-nodes) state)
        content' (get-in state' [:workspace-drawing :object :content])
        pts      (path/get-points content')]
    ;; segment 1's endpoints (0,0) and (10,0) merge to their midpoint (5,0)
    (t/is (some #(= (gpt/point 5 0) %) pts))
    (t/is (< (count pts) 3))))

(t/deftest delete-selected-opens-segments-else-removes-nodes
  (let [id       (random-uuid)
        content  (path/content
                  [{:command :move-to :params {:x 0 :y 0}}
                   {:command :line-to :params {:x 10 :y 0}}
                   {:command :line-to :params {:x 20 :y 0}}
                   {:command :line-to :params {:x 30 :y 0}}])
        run      (fn [selection]
                   (let [state  (pth/selectable-path-state id content selection)
                         events (atom [])]
                     (->> (ptk/watch (path.tools/delete-selected) state nil)
                          (rx/subs! #(swap! events conj %)))
                     ;; delete-selected emits a single node-tool event; apply it
                     (ptk/update (first @events) state)))
        move-tos (fn [st] (->> (get-in st [:workspace-drawing :object :content])
                               vec
                               (filter #(= :move-to (:command %)))
                               count))
        nodes    (fn [st] (count (path/get-points
                                  (get-in st [:workspace-drawing :object :content]))))]
    ;; deleting the middle segment (index 2) opens the path into two subpaths
    (t/is (> (move-tos (run {:nodes #{} :segments #{2} :handlers #{}})) 1))
    ;; Deleting a node leaves fewer than four nodes.
    (t/is (< (nodes (run {:nodes #{1} :segments #{} :handlers #{}})) 4))
    ;; Mixed node and segment deletion heals the selected node.
    (let [mixed (run {:nodes #{1} :segments #{2} :handlers #{}})]
      (t/is (< (nodes mixed) 4))
      (t/is (= 1 (move-tos mixed))))))

(t/deftest deleting-a-closed-seam-node-heals-its-adjacent-segments
  (let [id       (random-uuid)
        content  (path/content
                  [{:command :move-to :params {:x 0 :y 0}}
                   {:command :line-to :params {:x 10 :y 0}}
                   {:command :line-to :params {:x 10 :y 10}}
                   {:command :line-to :params {:x 0 :y 10}}
                   {:command :line-to :params {:x 0 :y 0}}])
        state    (pth/selectable-path-state id content
                                            {:nodes #{0} :segments #{} :handlers #{}})
        events   (atom [])
        _        (->> (ptk/watch (path.tools/delete-selected) state nil)
                      (rx/subs! #(swap! events conj %)))
        state'   (ptk/update (first @events) state)
        content' (vec (get-in state' [:workspace-drawing :object :content]))]
    (t/is (= [:move-to :line-to :line-to :curve-to]
             (mapv :command content')))
    (t/is (= (gpt/point 10 0) (path.helpers/node-position content' 0)))
    (t/is (= (gpt/point 10 0) (path.helpers/node-position content' 3)))))

(t/deftest deleting-a-touching-subpath-seam-heals-before-exiting-edition
  (let [id       (random-uuid)
        content  (path/content
                  [{:command :move-to :params {:x 0 :y 10}}
                   {:command :line-to :params {:x -10 :y 7}}
                   {:command :line-to :params {:x -10 :y 3}}
                   {:command :line-to :params {:x 0 :y 0}}
                   {:command :move-to :params {:x 0 :y 0}}
                   {:command :line-to :params {:x 10 :y 3}}
                   {:command :line-to :params {:x 10 :y 7}}
                   {:command :line-to :params {:x 0 :y 10}}])
        state    (pth/selectable-path-state id content
                                            {:nodes #{0 7} :segments #{} :handlers #{}})
        events   (atom [])
        _        (->> (ptk/watch (path.tools/delete-selected) state nil)
                      (rx/subs! #(swap! events conj %)))
        state'   (ptk/update (first @events) state)
        content' (vec (get-in state' [:workspace-drawing :object :content]))]
    (t/is (= [:move-to :line-to :line-to :line-to :line-to :curve-to]
             (mapv :command content')))
    (t/is (= (gpt/point -10 7) (path.helpers/node-position content' 0)))
    (t/is (= (gpt/point -10 7) (path.helpers/node-position content' 5)))))

(t/deftest delete-selected-with-segments-opens-a-gap-around-the-node
  (let [id       (random-uuid)
        content  (path/content
                  [{:command :move-to :params {:x 0 :y 0}}
                   {:command :line-to :params {:x 10 :y 0}}
                   {:command :line-to :params {:x 20 :y 0}}
                   {:command :line-to :params {:x 30 :y 0}}
                   {:command :line-to :params {:x 40 :y 0}}])
        state    (pth/selectable-path-state id content {:nodes #{2} :segments #{} :handlers #{}})
        events   (atom [])
        _        (->> (ptk/watch (path.tools/delete-selected-with-segments) state nil)
                      (rx/subs! #(swap! events conj %)))
        state'   (ptk/update (first @events) state)
        content' (get-in state' [:workspace-drawing :object :content])
        move-tos (->> content' vec (filter #(= :move-to (:command %))) count)
        nodes    (count (path/get-points content'))]
    ;; Removing incident segments opens a gap around the node.
    (t/is (= 4 nodes))
    (t/is (= 2 move-tos))))

(t/deftest group-handler-drag-ignores-stale-handler-identities
  (let [id        (random-uuid)
        content   (path/content
                   [{:command :move-to
                     :params {:x 0 :y 0}}
                    {:command :line-to
                     :params {:x 10 :y 0}}
                    {:command :curve-to
                     :params {:c1x 12 :c1y 0
                              :c2x 18 :c2y 0
                              :x 20 :y 0}}])
        selection {:nodes #{}
                   :segments #{}
                   ;; [1 :c1] points to a line-to and [9 :c2] is out of range
                   :handlers #{[1 :c1] [2 :c1] [9 :c2]}}
        state     (pth/selectable-path-state id content selection)
        event     (path.edition/modify-selected-handlers
                   id [2 :c1] {} 3 4 :smart true)
        state'    (ptk/update event state)
        modifiers (get-in state' [:workspace-local :edit-path id :content-modifiers])]
    (t/is (= {:c1x 3 :c1y 4} (get modifiers 2)))
    (t/is (nil? (get modifiers 1)))
    (t/is (nil? (get modifiers 9)))))

(t/deftest handler-drag-smart-keeps-a-smooth-node-smooth
  (let [id        (random-uuid)
        content   (pth/selectable-path-content)
        state     (pth/selectable-path-state id content {:nodes #{} :segments #{}
                                                         :handlers #{[2 :c1]}})
        ;; Smart mode keeps the handles aligned.
        event     (path.edition/modify-selected-handlers id [2 :c1] {} 0 4 :smart true)
        state'    (ptk/update event state)
        modifiers (get-in state' [:workspace-local :edit-path id :content-modifiers])
        content'  (path/apply-content-modifiers content modifiers)]
    (t/is (= (gpt/point 12 4) (path/get-handler-point content' 2 :c1)))
    ;; the opposite rotated to stay collinear -> still a smooth node
    (t/is (not= (gpt/point 8 0) (path/get-handler-point content' 1 :c2)))
    (t/is (path.helpers/handlers-joined? content' 2 :c1))))

(t/deftest handler-drag-independent-breaks-a-smooth-node
  (let [id        (random-uuid)
        content   (pth/selectable-path-content)
        state     (pth/selectable-path-state id content {:nodes #{} :segments #{}
                                                         :handlers #{[2 :c1]}})
        ;; Independent mode leaves the opposite handle in place.
        event     (path.edition/modify-selected-handlers id [2 :c1] {} 0 4 :independent false)
        state'    (ptk/update event state)
        modifiers (get-in state' [:workspace-local :edit-path id :content-modifiers])
        content'  (path/apply-content-modifiers content modifiers)]
    (t/is (= (gpt/point 12 4) (path/get-handler-point content' 2 :c1)))
    (t/is (= (gpt/point 8 0) (path/get-handler-point content' 1 :c2)))
    (t/is (not (path.helpers/handlers-joined? content' 2 :c1)))))

(t/deftest handler-drag-mirror-rejoins-a-corner-node
  (let [id        (random-uuid)
        content   (pth/corner-path-content)
        state     (pth/selectable-path-state id content {:nodes #{} :segments #{}
                                                         :handlers #{[2 :c1]}})
        ;; Mirror mode matches the opposite handle's angle and length.
        event     (path.edition/modify-selected-handlers id [2 :c1] {} 2 -6 :mirror false)
        state'    (ptk/update event state)
        modifiers (get-in state' [:workspace-local :edit-path id :content-modifiers])
        content'  (path/apply-content-modifiers content modifiers)]
    (t/is (= (gpt/point 14 0) (path/get-handler-point content' 2 :c1)))
    (t/is (= (gpt/point 6 0) (path/get-handler-point content' 1 :c2)))
    (t/is (path.helpers/handlers-joined? content' 2 :c1))))

(t/deftest handler-drag-aligned-mirrors-angle-keeping-length
  (let [id        (random-uuid)
        content   (pth/corner-path-content)
        state     (pth/selectable-path-state id content {:nodes #{} :segments #{}
                                                         :handlers #{}})
        ;; Aligned mode matches the angle and keeps the opposite length.
        event     (path.edition/modify-selected-handlers id [2 :c1] {} -2 -2 :aligned false)
        state'    (ptk/update event state)
        modifiers (get-in state' [:workspace-local :edit-path id :content-modifiers])
        content'  (path/apply-content-modifiers content modifiers)]
    (t/is (= (gpt/point 10 4) (path/get-handler-point content' 2 :c1)))
    (t/is (= (gpt/point 10 -2) (path/get-handler-point content' 1 :c2)))
    (t/is (path.helpers/handlers-joined? content' 2 :c1))))

(t/deftest remove-handler-collapses-the-clicked-handler
  (let [id       (random-uuid)
        content  (pth/selectable-path-content)
        state    (pth/selectable-path-state id content
                                            {:nodes #{} :segments #{}
                                             :handlers #{[1 :c2]}})
        state'   (ptk/update (path.tools/remove-handler 1 :c2) state)
        content' (get-in state' [:workspace-drawing :object :content])]
    ;; the clicked handler rests on its node (10,0); the others are untouched
    (t/is (= (gpt/point 10 0) (path/get-handler-point content' 1 :c2)))
    (t/is (= (gpt/point 2 0) (path/get-handler-point content' 1 :c1)))
    (t/is (= (gpt/point 12 0) (path/get-handler-point content' 2 :c1)))))

(t/deftest toggle-segment-curve-switches-line-and-curve
  (let [id        (random-uuid)
        content   (path/content
                   [{:command :move-to :params {:x 0 :y 0}}
                    {:command :line-to :params {:x 30 :y 0}}])
        state     (pth/selectable-path-state id content path.helpers/empty-selection)
        state'    (ptk/update (path.tools/toggle-segment-curve 1) state)
        content'  (get-in state' [:workspace-drawing :object :content])
        state''   (ptk/update (path.tools/toggle-segment-curve 1) state')
        content'' (get-in state'' [:workspace-drawing :object :content])]
    (t/is (= :curve-to (:command (nth content' 1))))
    ;; handles a third along, offset perpendicular (0.25 * length) into a bow
    (t/is (= (gpt/point 10 7.5) (path/get-handler-point content' 1 :c1)))
    (t/is (= (gpt/point 20 7.5) (path/get-handler-point content' 1 :c2)))
    (t/is (= :line-to (:command (nth content'' 1))))))

(t/deftest remove-segment-opens-the-path-keeping-nodes
  (let [id       (random-uuid)
        content  (path/content
                  [{:command :move-to :params {:x 0 :y 0}}
                   {:command :line-to :params {:x 10 :y 0}}
                   {:command :line-to :params {:x 20 :y 0}}
                   {:command :line-to :params {:x 30 :y 0}}])
        state    (pth/selectable-path-state id content
                                            {:nodes #{} :segments #{2} :handlers #{}})
        state'   (ptk/update (path.tools/remove-segment 2) state)
        content' (get-in state' [:workspace-drawing :object :content])]
    (t/is (= [[:move-to {:x 0 :y 0}] [:line-to {:x 10 :y 0}]
              [:move-to {:x 20 :y 0}] [:line-to {:x 30 :y 0}]]
             (mapv (juxt :command :params) content')))
    ;; the removed segment's now-stale selection is pruned
    (t/is (= #{} (get-in state' [:workspace-local :edit-path id :selection :segments])))))

(t/deftest remove-segment-remaps-handler-types-when-node-indices-shift
  (let [id      (random-uuid)
        content (path/content
                 [{:command :move-to :params {:x 0 :y 0}}
                  {:command :line-to :params {:x 10 :y 0}}
                  {:command :curve-to
                   :params {:c1x 12 :c1y 4 :c2x 18 :c2y 4 :x 20 :y 0}}
                  {:command :curve-to
                   :params {:c1x 22 :c1y -4 :c2x 28 :c2y -4 :x 30 :y 0}}])
        state   (-> (pth/selectable-path-state
                     id content {:nodes #{2} :segments #{} :handlers #{}})
                    (assoc-in [:workspace-local :edit-path id :handler-types]
                              {2 :aligned}))
        state'  (ptk/update (path.tools/remove-segment 1) state)]
    ;; Remap the selected node after dropping the dangling start.
    (t/is (= 3 (count (get-in state' [:workspace-drawing :object :content]))))
    (t/is (= #{1} (get-in state' [:workspace-local :edit-path id :selection :nodes])))
    ;; Keep the mode attached to the surviving node.
    (t/is (= {1 :aligned}
             (get-in state' [:workspace-local :edit-path id :handler-types])))))

(t/deftest removing-an-earlier-node-preserves-a-surviving-mirror-mode
  (let [id      (random-uuid)
        content (path/content
                 [{:command :move-to :params {:x 0 :y 0}}
                  {:command :curve-to
                   :params {:c1x 3 :c1y 0 :c2x 7 :c2y 0 :x 10 :y 0}}
                  {:command :curve-to
                   :params {:c1x 13 :c1y 0 :c2x 17 :c2y 0 :x 20 :y 0}}
                  {:command :curve-to
                   :params {:c1x 23 :c1y 0 :c2x 28 :c2y 0 :x 30 :y 0}}
                  {:command :curve-to
                   :params {:c1x 34 :c1y 0 :c2x 37 :c2y 0 :x 40 :y 0}}
                  {:command :curve-to
                   :params {:c1x 43 :c1y 0 :c2x 47 :c2y 0 :x 50 :y 0}}])
        state   (-> (pth/selectable-path-state
                     id content {:nodes #{0} :segments #{} :handlers #{}})
                    (assoc-in [:workspace-local :edit-path id :handler-types]
                              {3 :mirror}))
        state'  (ptk/update (path.tools/remove-node) state)]
    ;; Geometry alone derives the fourth node as aligned.
    (t/is (= :aligned (path.helpers/derive-handler-type content 3)))
    ;; Remap the explicit mode with the surviving node.
    (t/is (= {2 :mirror}
             (get-in state' [:workspace-local :edit-path id :handler-types])))))

(t/deftest remove-node-with-segments-opens-a-gap
  (let [id       (random-uuid)
        content  (path/content
                  [{:command :move-to :params {:x 0 :y 0}}
                   {:command :line-to :params {:x 10 :y 0}}
                   {:command :line-to :params {:x 20 :y 0}}
                   {:command :line-to :params {:x 30 :y 0}}])
        state    (pth/selectable-path-state id content path.helpers/empty-selection)
        emitted  (atom [])
        _        (->> (ptk/watch (path.tools/remove-node-with-segments 1) state nil)
                      (rx/subs! #(swap! emitted conj %)))
        state'   (reduce #(ptk/update %2 %1) state @emitted)
        content' (get-in state' [:workspace-drawing :object :content])]
    ;; node (10,0) and both incident segments are gone; the (0,0) start is
    ;; left dangling and dropped too, the rest of the path survives
    (t/is (= [[:move-to {:x 20 :y 0}] [:line-to {:x 30 :y 0}]]
             (mapv (juxt :command :params) content')))))

(t/deftest dragging-a-node-or-segment-onto-another-merges-them
  (let [id      (random-uuid)
        content (path/content
                 [{:command :move-to :params {:x 0 :y 0}}
                  {:command :line-to :params {:x 10 :y 0}}
                  {:command :move-to :params {:x 12 :y 0}}
                  {:command :line-to :params {:x 30 :y 0}}])
        mk      (fn [selection]
                  (-> (pth/selectable-path-state id content selection)
                      (assoc-in [:workspace-local :zoom] 1)))
        emit-of (fn [state]
                  (let [out (atom [])]
                    (->> (ptk/watch (path.edition/merge-dragged-on-drop)
                                    state (rx/subject))
                         (rx/subs! #(swap! out conj %)))
                    @out))
        welded  [{:x 0 :y 0} {:x 11 :y 0} {:x 30 :y 0}]]
    (t/testing "a single node dropped within range of another node merges them"
      (let [state    (mk {:nodes #{1} :segments #{} :handlers #{}})
            events   (emit-of state)
            content' (vec (get-in (ptk/update (first events) state)
                                  [:workspace-drawing :object :content]))]
        (t/is (= 1 (count events)))
        ;; Dropped subpath endpoints weld at their midpoint.
        (t/is (= welded (mapv :params content')))))
    (t/testing "a dragged segment whose endpoint lands on a node merges too"
      ;; segment 1 (nodes (0,0)-(10,0)); its (10,0) end is within range of (12,0)
      (let [state    (mk {:nodes #{} :segments #{1} :handlers #{}})
            events   (emit-of state)
            content' (vec (get-in (ptk/update (first events) state)
                                  [:workspace-drawing :object :content]))]
        (t/is (= 1 (count events)))
        (t/is (= welded (mapv :params content')))))
    (t/testing "a node dropped with no neighbour in range does not merge"
      (t/is (empty? (emit-of (mk {:nodes #{3} :segments #{} :handlers #{}})))))))

;; Path-local undo and redo events use a seeded local stack.
