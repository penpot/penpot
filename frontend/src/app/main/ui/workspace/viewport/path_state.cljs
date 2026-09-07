;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC Sucursal en España SL

(ns app.main.ui.workspace.viewport.path-state
  (:require
   [app.common.types.path :as path]
   [app.main.data.workspace.path.state :as path.state]
   [app.main.ui.workspace.viewport.drawarea :as drawarea]))

(defn derive-path-state
  "Derives the shared path-editing view model used by classic and WASM viewports."
  [edit-path edition drawing-tool drawing-object objects]
  (let [edit-state    (path.state/current-edit-state edit-path edition)
        editing?      (path.state/editing? edit-path edition)
        drawing?      (path.state/drawing? edit-state edition drawing-tool drawing-object)
        editing-shape (when edition
                        (if editing?
                          drawing-object
                          (get objects edition)))
        editing-shape (if editing?
                        (path/convert-to-path editing-shape objects)
                        editing-shape)
        bar-state     (or edit-state
                          (when drawing?
                            (get edit-path (get drawing-object :id))))
        bar-shape     (or editing-shape drawing-object)
        drawing-shape (if (and editing? edition)
                        (drawarea/path-edit-shape drawing-object (get objects edition))
                        drawing-object)]
    {:edit-state edit-state
     :editing? editing?
     :drawing? drawing?
     :editing-shape editing-shape
     :bar-state bar-state
     :bar-shape bar-shape
     :drawing-shape drawing-shape}))
