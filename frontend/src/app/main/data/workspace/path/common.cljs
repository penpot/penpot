;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC

(ns app.main.data.workspace.path.common
  (:require
   [app.common.schema :as sm]
   [app.common.svg.path.subpath :as ups]
   [app.main.data.workspace.path.state :as st]
   [potok.v2.core :as ptk]))

(def valid-commands
  #{:move-to
    :line-to
    :line-to-horizontal
    :line-to-vertical
    :curve-to
    :smooth-curve-to
    :quadratic-bezier-curve-to
    :smooth-quadratic-bezier-curve-to
    :elliptical-arc
    :close-path})

;; FIXME: should this schema be defined on common.types ?

(def ^:private
  schema:path-content
  [:vector {:title "PathContent"}
   [:map {:title "PathContentEntry"}
    [:command [::sm/one-of valid-commands]]
    ;; FIXME: remove the `?` from prop name
    [:relative? {:optional true} :boolean]
    [:params {:optional true}
     [:map {:title "PathContentEntryParams"}
      [:x :double]
      [:y :double]
      [:c1x {:optional true} :double]
      [:c1y {:optional true} :double]
      [:c2x {:optional true} :double]
      [:c2y {:optional true} :double]]]]])

(def check-path-content!
  (sm/check-fn schema:path-content))

(defn init-path []
  (ptk/reify ::init-path))

(defn clean-edit-state
  [state]
  (dissoc state :last-point :prev-handler :drag-handler :preview))

(defn finish-path
  []
  (ptk/reify ::finish-path
    ptk/UpdateEvent
    (update [_ state]
      (let [id (st/get-path-id state)]
        (-> state
            (update-in [:workspace-local :edit-path id] clean-edit-state)
            (update-in (st/get-path-location state :content) ups/close-subpaths))))))
