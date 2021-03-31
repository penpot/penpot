;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; This Source Code Form is "Incompatible With Secondary Licenses", as
;; defined by the Mozilla Public License, v. 2.0.
;;
;; Copyright (c) UXBOX Labs SL

(ns app.main.data.workspace.path
  (:require
   [app.common.data :as d]
   [app.main.data.workspace.path.drawing :as drawing]
   [app.main.data.workspace.path.edition :as edition]
   [app.main.data.workspace.path.selection :as selection]
   [app.main.data.workspace.path.tools :as tools]))

;; Drawing
(d/export drawing/handle-new-shape)
(d/export drawing/start-path-from-point)
(d/export drawing/close-path-drag-start)
(d/export drawing/change-edit-mode)

;; Edition
(d/export edition/start-move-handler)
(d/export edition/start-move-path-point)
(d/export edition/start-path-edit)

;; Selection
(d/export selection/select-handler)
(d/export selection/handle-selection)
(d/export selection/path-handler-enter)
(d/export selection/path-handler-leave)
(d/export selection/path-pointer-enter)
(d/export selection/path-pointer-leave)

;; Path tools
(d/export tools/make-curve)
(d/export tools/make-corner)
