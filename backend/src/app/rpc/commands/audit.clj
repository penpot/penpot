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
   [app.common.spec :as us]
   [app.common.uuid :as uuid]
   [app.config :as cf]
   [app.db :as db]
   [app.http :as-alias http]
   [app.loggers.audit :as audit]
   [app.rpc :as-alias rpc]
   [app.rpc.climit :as-alias climit]
   [app.rpc.doc :as-alias doc]
   [app.rpc.helpers :as rph]
   [app.util.services :as sv]
   [app.util.time :as dt]
   [clojure.spec.alpha :as s]))

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

(s/def ::name ::us/string)
(s/def ::type ::us/string)
(s/def ::props (s/map-of ::us/keyword any?))
(s/def ::timestamp dt/instant?)
(s/def ::context (s/map-of ::us/keyword any?))

(s/def ::event
  (s/keys :req-un [::type ::name ::props ::timestamp]
          :opt-un [::context]))

(s/def ::events (s/every ::event))

(s/def ::push-audit-events
  (s/keys :req [::rpc/profile-id]
          :req-un [::events]))

(sv/defmethod ::push-audit-events
  {::climit/id :submit-audit-events-by-profile
   ::climit/key-fn ::rpc/profile-id
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
