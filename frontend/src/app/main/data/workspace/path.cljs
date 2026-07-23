;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC Sucursal en España SL

(ns app.main.data.workspace.path
  (:require
   [app.common.data.macros :as dm]
   [app.main.data.workspace.path.clipboard :as clipboard]
   [app.main.data.workspace.path.drawing :as drawing]
   [app.main.data.workspace.path.edition :as edition]
   [app.main.data.workspace.path.selection :as selection]
   [app.main.data.workspace.path.tools :as tools]
   [app.main.data.workspace.path.undo :as undo]))

;; Drawing
(dm/export drawing/handle-drawing)
(dm/export drawing/start-path-from-point)
(dm/export drawing/close-path-drag-start)
(dm/export drawing/change-edit-mode)
(dm/export drawing/reset-last-handler)
(dm/export drawing/on-draw-node-pointer-down)
(dm/export drawing/on-draw-segment-pointer-down)
(dm/export drawing/start-move-prev-handler)

;; Edition
(dm/export edition/start-move-handler)
(dm/export edition/start-move-path-point)
(dm/export edition/start-move-path-segment)
(dm/export edition/start-path-edit)
(dm/export edition/create-node-at-position)
(dm/export edition/move-selected)

;; Clipboard
(dm/export clipboard/copy-selected-nodes)
(dm/export clipboard/cut-selected-nodes)
(dm/export clipboard/paste-nodes)
(dm/export clipboard/duplicate-selected)

;; Selection
(dm/export selection/handle-area-selection)
(dm/export selection/select-node)
(dm/export selection/select-segment)
(dm/export selection/select-handler)
(dm/export selection/path-handler-enter)
(dm/export selection/path-handler-leave)
(dm/export selection/path-segment-enter)
(dm/export selection/path-segment-leave)
(dm/export selection/path-pointer-enter)
(dm/export selection/path-pointer-leave)
(dm/export selection/select-all-nodes)
(dm/export selection/deselect-all)

;; Path tools
(dm/export tools/make-curve)
(dm/export tools/make-corner)
(dm/export tools/add-node)
(dm/export tools/remove-node)
(dm/export tools/delete-selected)
(dm/export tools/delete-selected-with-segments)
(dm/export tools/merge-nodes)
(dm/export tools/join-nodes)
(dm/export tools/separate-nodes)
(dm/export tools/toggle-snap)
(dm/export tools/set-handler-type)
(dm/export tools/toggle-node-curve)
(dm/export tools/toggle-segment-curve)
(dm/export tools/remove-segment)
(dm/export tools/remove-node-with-segments)
(dm/export tools/remove-handler)
(dm/export tools/flip-nodes)
(dm/export tools/align-nodes)
(dm/export tools/distribute-nodes)
(dm/export tools/set-selection-coordinate)

;; Undo/redo
(dm/export undo/undo-path)
(dm/export undo/redo-path)
(dm/export undo/merge-head)
