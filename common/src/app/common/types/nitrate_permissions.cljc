;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC

(ns app.common.types.nitrate-permissions)

(def ^:private defaults
  {:create-teams "any"
   :delete-teams "ownersAndAdmins"})

(def ^:private action-rules
  {:create-team {:permission-key  :create-teams
                 :allowed-values  #{"any"}
                 :requires-admin? false}
   :delete-team {:permission-key  :delete-teams
                 :allowed-values  #{"ownersAndAdmins"}
                 :requires-admin? true}})

(defn- normalize-org-permissions
  [org-perms]
  (merge defaults (or (:permissions org-perms) {})))

(defn- owner?
  [org-perms profile-id]
  (= profile-id (:owner-id org-perms)))

(defn allowed?
  "Returns true only for explicitly allowed actions (fail-closed)."
  [action {:keys [org-perms profile-id team-perms]}]
  (let [{:keys [permission-key allowed-values requires-admin?] :as rule}
        (get action-rules action)
        permissions (normalize-org-permissions org-perms)
        is-owner?   (owner? org-perms profile-id)
        is-admin?   (boolean (:is-admin team-perms))]
    (cond
      (nil? rule)                             false
      is-owner?                               true
      (and requires-admin? (not is-admin?))  false
      :else (contains? allowed-values (get permissions permission-key)))))
