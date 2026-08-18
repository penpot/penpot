;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC Sucursal en España SL

(ns app.util.session-state
  "Helpers for persisting transient state in browser session storage
  so it survives cross-origin redirects."
  (:require
   [app.util.storage :as storage]))

(def ^:private pending-actions-key ::pending-actions)

(defn save-pending-action!
  "Persist an action map under `id` in the browser session."
  [id data]
  (binding [storage/*sync* true]
    (swap! storage/session update pending-actions-key assoc id data)))

(defn consume-pending-action!
  "Read and remove the pending action stored under `id`.
  Returns nil if not found."
  [id]
  (let [action (get-in @storage/session [pending-actions-key id])]
    (when (some? action)
      (binding [storage/*sync* true]
        (swap! storage/session update pending-actions-key dissoc id)))
    action))
