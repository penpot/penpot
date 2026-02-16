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
   [app.common.time :as ct]
   [app.common.uuid :as uuid]
   [app.config :as cf]
   [app.db :as db]
   [app.http :as-alias http]
   [app.loggers.audit :as-alias audit]
   [app.loggers.database :as loggers.db]
   [app.loggers.mattermost :as loggers.mm]
   [app.rpc :as-alias rpc]
   [app.rpc.climit :as-alias climit]
   [app.rpc.doc :as-alias doc]
   [app.rpc.helpers :as rph]
   [app.util.inet :as inet]
   [app.util.services :as sv]))

(def ^:private event-columns
  [:id
   :name
   :source
   :type
   :tracked-at
   :created-at
   :profile-id
   :ip-addr
   :props
   :context])

(defn- event->row [event]
  [(::audit/id event)
   (::audit/name event)
   (::audit/source event)
   (::audit/type event)
   (::audit/tracked-at event)
   (::audit/created-at event)
   (::audit/profile-id event)
   (db/inet (::audit/ip-addr event))
   (db/tjson (::audit/props event))
   (db/tjson (d/without-nils (::audit/context event)))])

(defn- adjust-timestamp
  [{:keys [::audit/tracked-at ::audit/created-at] :as event}]
  (let [margin (inst-ms (ct/diff tracked-at created-at))]
    (if (or (neg? margin)
            (> margin 3600000))
      ;; If event is in future or lags more than 1 hour, we reasign
      ;; tracked-at to the server creation date
      (-> event
          (assoc ::audit/tracked-at created-at)
          (update ::audit/context assoc :original-tracked-at tracked-at))
      event)))

(defn- exception-event?
  [{:keys [::audit/type ::audit/name] :as ev}]
  (and (= "action" type)
       (or (= "unhandled-exception" name)
           (= "exception-page" name))))

(def ^:private xf:map-event-row
  (comp
   (map adjust-timestamp)
   (map event->row)))

(defn- get-events
  [{:keys [::rpc/request-at ::rpc/profile-id events] :as params}]
  (let [request (-> params meta ::http/request)
        ip-addr (inet/parse-request request)

        xform   (map (fn [event]
                       {::audit/id (uuid/next)
                        ::audit/type (:type event)
                        ::audit/name (:name event)
                        ::audit/props (:props event)
                        ::audit/context (:context event)
                        ::audit/profile-id profile-id
                        ::audit/ip-addr ip-addr
                        ::audit/source "frontend"
                        ::audit/tracked-at (:timestamp event)
                        ::audit/created-at request-at}))]

    (sequence xform events)))

(defn- handle-events
  [{:keys [::db/pool] :as cfg} params]
  (let [events (get-events params)]

    ;; Look for error reports and save them on internal reports table
    (when-let [events (->> events
                           (sequence (filter exception-event?))
                           (not-empty))]
      (run! (partial loggers.db/emit cfg) events)
      (run! (partial loggers.mm/emit cfg) events))

    ;; Process and save events
    (when (seq events)
      (let [rows (sequence xf:map-event-row events)]
        (db/insert-many! pool :audit-log event-columns rows)))))

(def ^:private valid-event-types
  #{"action" "identify" "trigger"})

(def ^:private schema:frontend-event
  [:map {:title "Event"}
   [:name
    [:and {:gen/elements ["update-file", "get-profile"]}
     [:string {:max 250}]
     [:re #"[\d\w-]{1,50}"]]]
   [:type
    [:and {:gen/elements valid-event-types}
     [:string {:max 250}]
     [::sm/one-of {:format "string"} valid-event-types]]]
   [:props
    [:map-of :keyword ::sm/any]]
   [:timestamp ::ct/inst]
   [:context {:optional true}
    [:map-of :keyword ::sm/any]]])

(def ^:private schema:push-audit-events
  [:map {:title "push-audit-events"}
   [:events [:vector schema:frontend-event]]])

(sv/defmethod ::push-audit-events
  {::climit/id :submit-audit-events/by-profile
   ::climit/key-fn ::rpc/profile-id
   ::sm/params schema:push-audit-events
   ::audit/skip true
   ::doc/skip true
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
