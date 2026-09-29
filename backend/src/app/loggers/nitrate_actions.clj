;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS SUBSIDIARY SL

(ns app.loggers.nitrate-actions
  "React to Nitrate-pushed audit events by name allowlist."
  (:require
   [app.common.data :as d]
   [app.config :as cf]
   [app.nitrate :as nitrate]
   [app.worker :as wrk]
   [integrant.core :as ig]))

(def event-names-to-keep
  #{"accept-organization-invitation"
    "accept-team-invitation"
    "accept-team-invitation-from"
    "add-member-to-organization"
    "add-team-to-organization"
    "cancel-organization-invitation"
    "change-organization-advanced-permission"
    "create-file"
    "create-organization"
    "create-organization-invitation"
    "create-project"
    "create-team"
    "create-team-access-request"
    "create-team-invitation"
    "create-team-invitations"
    "create-webhook"
    "delete-font"
    "delete-organization"
    "delete-project"
    "delete-team"
    "delete-team-invitation"
    "delete-team-member"
    "leave-team"
    "move-project"
    "move-team-to-organization"
    "organization-sso-auth-failed"
    "organization-sso-auth-started"
    "organization-sso-auth-succeeded"
    "permanently-delete-team-files"
    "remove-organization-team"
    "remove-team-from-organization"
    "rename-organization"
    "rename-project"
    "restore-deleted-team-files"
    "update-organization-invitation"
    "update-organization-permissions"
    "update-team-invitation"
    "update-team-invitation-role"
    "update-team-member-role"
    "update-team-photo"
    "verify-token"})

(def ^:private task-param-keys
  [:name :type :profile-id :props :created-at :tracked-at
   :source :ip-addr :context])

(defn- event->task-params
  "Keep only fields needed for Admin Console ingest (explicit allowlist)."
  [event]
  (-> event
      (select-keys task-param-keys)
      (d/without-nils)))

(defn handle-event!
  "Enqueue a worker task when `:admin-console` is enabled and the
  event name is in `event-names-to-keep`.

  Uses the same pattern as webhooks: work runs off the audit
  transaction so handler failures cannot roll back audit_log writes."
  [cfg event]
  (when (and (contains? cf/flags :admin-console)
             (contains? event-names-to-keep (:name event)))
    (wrk/submit! (-> cfg
                     (assoc ::wrk/task :process-nitrate-action)
                     (assoc ::wrk/queue :admin-console)
                     (assoc ::wrk/max-retries 0)
                     (assoc ::wrk/params (event->task-params event))))))

(defmethod ig/assert-key ::handler
  [_ params]
  (assert (contains? params :app.nitrate/client) "expected nitrate client"))

(defmethod ig/init-key ::handler
  [_ cfg]
  (fn [{:keys [props] :as _task}]
    ;; Nil return from a successful AC 204 is OK; exceptions fail the task.
    (nitrate/call cfg :ingest-audit-log props)))
