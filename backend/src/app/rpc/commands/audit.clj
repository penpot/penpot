;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC

(ns app.rpc.commands.audit
  "Audit Log related RPC methods"
  (:require
   [app.common.data :as d]
   [app.common.logging :as l]
   [app.common.schema :as sm]
   [app.common.uuid :as uuid]
   [app.config :as cf]
   [app.db :as db]
   [app.http :as-alias http]
   [app.loggers.audit :as audit]
   [app.rpc :as-alias rpc]
   [app.rpc.climit :as-alias climit]
   [app.rpc.doc :as-alias doc]
   [app.rpc.helpers :as rph]
   [app.util.services :as sv]))

(defn- event->row [event]
  [(uuid/next)
   (:name event)
   (:source event)
   (:type event)
   (:timestamp event)
   (:profile-id event)
   (db/inet (:ip-addr event))
   (db/tjson (:props event))
   (db/tjson (d/without-nils (:context event)))])

(def ^:private event-columns
  [:id :name :source :type :tracked-at
   :profile-id :ip-addr :props :context])

(defn- handle-events
  [{:keys [::db/pool]} {:keys [::rpc/profile-id events] :as params}]
  (let [request (-> params meta ::http/request)
        ip-addr (audit/parse-client-ip request)
        xform   (comp
                 (map #(assoc % :profile-id profile-id))
                 (map #(assoc % :ip-addr ip-addr))
                 (map #(assoc % :source "frontend"))
                 (filter :profile-id)
                 (map event->row))
        events  (sequence xform events)]
    (when (seq events)
      (db/insert-multi! pool :audit-log event-columns events))))

(def schema:event
  [:map {:title "Event"}
   [:name [:string {:max 250}]]
   [:type [:string {:max 250}]]
   [:props
    [:map-of :keyword :any]]
   [:context {:optional true}
    [:map-of :keyword :any]]])

(def schema:push-audit-events
  [:map {:title "push-audit-events"}
   [:events [:vector schema:event]]])

(sv/defmethod ::push-audit-events
  {::climit/id :submit-audit-events-by-profile
   ::climit/key-fn ::rpc/profile-id
   ::sm/params schema:push-audit-events
   ::audit/skip true
   ::doc/added "1.17"}
  [{:keys [::db/pool] :as cfg} params]
  (if (or (db/read-only? pool)
          (not (contains? cf/flags :audit-log)))
    (do
      (l/warn :hint "audit: http handler disabled or db is read-only")
      (rph/wrap nil))

    (do
      (try
        (handle-events cfg params)
        (catch Throwable cause
          (l/error :hint "unexpected error on persisting audit events from frontend"
                   :cause cause)))

      (rph/wrap nil))))
