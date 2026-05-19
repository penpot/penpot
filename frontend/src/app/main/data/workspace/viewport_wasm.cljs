;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC

(ns app.main.data.workspace.viewport-wasm
  (:require
   [app.main.features :as features]
   [app.render-wasm.api :as wasm.api]))

(defn render-context-lost?
  [state]
  (true? (get-in state [:render-state :lost])))

(defn maybe-sync-workspace-local-viewport!
  "When `render-wasm/v1` is active, pushes workspace zoom and vbox into WASM."
  [state]
  (when (and (features/active-feature? state "render-wasm/v1") (not (render-context-lost? state)))
    (wasm.api/sync-workspace-local-viewport! state)))

(defn maybe-view-interaction-start!
  [state]
  (when (and (features/active-feature? state "render-wasm/v1") (not (render-context-lost? state)))
    (wasm.api/view-interaction-start!)))

(defn maybe-view-interaction-end!
  [state]
  (when (and (features/active-feature? state "render-wasm/v1") (not (render-context-lost? state)))
    (wasm.api/view-interaction-end!)))