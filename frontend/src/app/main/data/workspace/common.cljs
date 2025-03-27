;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC

(ns app.main.data.workspace.common
  (:require
   [app.common.logging :as log]
   [app.main.data.profile :as du]
   [app.main.data.workspace.layout :as dwl]
   [beicon.v2.core :as rx]
   [potok.v2.core :as ptk]))

;; Change this to :info :debug or :trace to debug this module
(log/set-level! :warn)

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; HELPERS
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(defn initialized?
  "Check if the state is properly initialized in a workspace. This means
  it has the `:current-page-id` and `:current-file-id` properly set."
  [state]
  (and (uuid? (:current-file-id state))
       (uuid? (:current-page-id state))))

(defn interrupt?
  [e]
  (= e :interrupt))

(defn set-workspace-visited
  []
  (ptk/reify ::set-workspace-visited
    ptk/WatchEvent
    (watch [_ state _]
      (let [profile (:profile state)
            props   (get profile :props)]
        (when (not (:workspace-visited props))
          (rx/of (du/update-profile-props {:workspace-visited true})))))))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Toolbar
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(defn hide-toolbar
  []
  (ptk/reify ::hide-toolbar
    ptk/UpdateEvent
    (update [_ state]
      (assoc-in state [:workspace-local :hide-toolbar] true))))

(defn show-toolbar
  []
  (ptk/reify ::show-toolbar
    ptk/UpdateEvent
    (update [_ state]
      (assoc-in state [:workspace-local :hide-toolbar] false))))

(defn toggle-toolbar-visibility
  []
  (ptk/reify ::toggle-toolbar-visibility
    ptk/UpdateEvent
    (update [_ state]
      (update-in state [:workspace-local :hide-toolbar] not))))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Read only
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(defn set-workspace-read-only
  [read-only?]
  (ptk/reify ::set-workspace-read-only
    ptk/UpdateEvent
    (update [_ state]
      (update state :workspace-global assoc :read-only? read-only?))

    ptk/WatchEvent
    (watch [_ _ _]
      (if read-only?
        (rx/of :interrupt
               (dwl/remove-layout-flag :colorpalette)
               (dwl/remove-layout-flag :textpalette))
        (rx/empty)))))
