;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC

(ns app.main.data.workspace.path
  (:require
   [app.common.data.macros :as dm]
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

;; Edition
(dm/export edition/start-move-handler)
(dm/export edition/start-move-path-point)
(dm/export edition/start-path-edit)
(dm/export edition/create-node-at-position)
(dm/export edition/move-selected)

;; Selection
(dm/export selection/handle-area-selection)
(dm/export selection/select-node)
(dm/export selection/path-handler-enter)
(dm/export selection/path-handler-leave)
(dm/export selection/path-pointer-enter)
(dm/export selection/path-pointer-leave)

;; Path tools
(dm/export tools/make-curve)
(dm/export tools/make-corner)
(dm/export tools/add-node)
(dm/export tools/remove-node)
(dm/export tools/merge-nodes)
(dm/export tools/join-nodes)
(dm/export tools/separate-nodes)
(dm/export tools/toggle-snap)

;; Undo/redo
(dm/export undo/undo-path)
(dm/export undo/redo-path)
(dm/export undo/merge-head)
