;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS SUBSIDIARY SL

(ns app.loggers.audit.consumers.team-storage
  "The team-storage transport of the audit log consumer.

  It reads the stream from the team-storage cursor and marks dirty the
  teams whose media storage may have changed, so the recompute jobs of
  `app.tasks.team-storage` refresh the cached counter the
  media-storage-bytes-per-team quota reads.

  The component starts the consumer loop at boot because it is always on. The
  mechanics (the tick mutex, the cursor, the frontier of the pass, the GC)
  live in `app.loggers.audit.consumers`."
  (:require
   [app.common.exceptions :as ex]
   [app.common.logging :as l]
   [app.db :as db]
   [app.loggers.audit :as audit]
   [app.loggers.audit.consumers :as consumers]
   [app.metrics :as mtx]
   [integrant.core :as ig]))

(def ^:private default-team-keys
  "Key map of the events that carry a single :team-id in props."
  {:paths [[:team-id]]
   :keys  [:team-id]})

(def ^:private team-keys-by-name
  "Single source of the storage-usage contract: event name -> where its
  team ids live. `:paths` are the prop paths `team-ids-of-event` reads;
  `:keys` are the top-level props `carries-team-keys?` checks with
  `some?`, so an explicit empty collection counts as a fact and a nil
  value as a producer that failed to resolve its team. Adding a name here
  also adds it to `event-names`, so the two cannot drift."
  {"upload-file-media-object"          default-team-keys
   "create-file-media-object-from-url" default-team-keys
   "clone-file-media-object"           default-team-keys
   "assemble-file-media-object"        default-team-keys
   ;; Files created in bulk (import, clone, duplicate) may carry media,
   ;; so they can change the total even though create-file itself adds
   ;; no bytes: do not drop it, and do not copy it to cheaper events.
   "create-file"                       default-team-keys
   "create-font-variant"               default-team-keys
   "delete-font"                       default-team-keys
   "delete-font-variant"               default-team-keys
   "delete-file"                       default-team-keys
   "delete-object-with-cascade"        {:paths [[:team-ids] [:cascade :team-ids]]
                                        :keys  [:team-ids :cascade]}
   "move-files"                        {:paths [[:source-team-id] [:dest-team-id]]
                                        :keys  [:source-team-id :dest-team-id]}
   "move-project"                      {:paths [[:source-team-id] [:dest-team-id]]
                                        :keys  [:source-team-id :dest-team-id]}
   "permanently-delete-team-files"     default-team-keys
   "restore-deleted-team-files"        default-team-keys
   "restore-file"                      default-team-keys
   "restore-profile"                   {:paths [[:team-ids] [:cascade :team-ids]]
                                        :keys  [:team-ids :cascade]}
   "restore-project"                   default-team-keys
   "restore-team"                      default-team-keys
   "file-gc"                           default-team-keys})

(def event-names
  "Audit event names that can change per-team media storage. Only these
  mark the team dirty. Derived from `team-keys-by-name` so the list and
  the key map cannot disagree. Purges (`hard-delete-objects`) are
  excluded on purpose: the physical delete only removes rows the live
  SUM already ignores (everything with `deleted_at` set), so marking
  them would recompute the same total — and purged teams would violate
  the usage table foreign key."
  (set (keys team-keys-by-name)))

(defn- team-ids-of-event
  "Team ids of one storage-usage event, read through its key map (see
  team-keys-by-name). Throws on a present non-UUID: a producer bug,
  never coerced; the caller logs the row and skips that event."
  [{:keys [name props]}]
  (let [{:keys [paths]} (get team-keys-by-name name default-team-keys)
        ;; A path holds either one id (:team-id) or a collection
        ;; (:team-ids): normalize both to a seq.
        ids (mapcat (fn [path]
                      (let [v (get-in props path)]
                        (cond (nil? v) nil
                              (coll? v) v
                              :else     [v])))
                    paths)]
    (doseq [id ids :when (some? id)]
      (when-not (uuid? id)
        (ex/raise :type :validation
                  :code :non-uuid-team-id
                  :hint "non-uuid team id in storage-usage event"
                  :event name)))
    ids))

(defn- carries-team-keys?
  "True when the event carries any of the team keys its key map names,
  even an empty collection: an explicit empty list (a profile that owns
  no team) is a fact, not a producer bug."
  [{:keys [name props]}]
  (let [{:keys [keys]} (get team-keys-by-name name default-team-keys)]
    ;; `some?` on the value, not `contains?`: an explicit empty
    ;; collection counts (a fact), a nil value does not (the producer
    ;; failed to resolve its team).
    (boolean (some #(some? (get props %)) keys))))

(defn- collect-team-ids
  "Team ids whose media storage may have changed in these events, and how
  many events carried an invalid one. Read straight from props: producers
  key their events by team so the drain never joins production tables. The
  callers pass only events whose name is in `event-names`; the
  `default-team-keys` fallback exists for a direct call, not for the
  drain. A present non-UUID is a producer bug (see team-ids-of-event,
  never coerced): that event is logged with its row id and skipped, so one
  bad row cannot wedge the queue, and it is reported as `:invalid` so the
  chunk can count it as skipped."
  [events]
  (reduce (fn [acc {:keys [id] :as event}]
            (try
              (update acc :team-ids into (filter some? (team-ids-of-event event)))
              (catch Throwable cause
                (l/wrn :hint "skipping storage-usage event with invalid team ids"
                       :audit-log-id (str id)
                       :event (:name event)
                       :cause cause)
                (update acc :invalid inc))))
          {:team-ids #{} :invalid 0}
          events))

(def ^:private sql:mark-team-storage-dirty
  "INSERT INTO team_storage_usage AS tsu (team_id, dirty_at)
   SELECT id, now() FROM unnest(?::uuid[]) AS u(id)
   WHERE EXISTS (SELECT 1 FROM team AS t WHERE t.id = u.id)
   ON CONFLICT (team_id) DO UPDATE
      SET dirty_at = GREATEST(tsu.dirty_at, EXCLUDED.dirty_at)")

(defn- mark-dirty
  "Mark the teams dirty, keeping the newest mark. A mark that lands while
  a recompute runs is newer than its read, so the upsert of the recompute
  keeps it (see `app.tasks.team-storage`) and the next tick recomputes the
  team. Cost, accepted: the scheduler reads dirty teams oldest-first, so a
  team that keeps changing moves to the end of the queue; it only matters
  when the backlog passes the tick limit."
  [conn team-ids]
  (when (seq team-ids)
    (db/exec-one! conn [sql:mark-team-storage-dirty
                        (db/create-array conn "uuid" team-ids)])))

(defn- process-chunk
  "Mark dirty the teams of the events of one chunk that concern the
  storage usage. Returns {:processed :skipped}: the events outside
  `event-names` and the telemetry shadows count as skipped,
  so the consumer cursor advances over them and they are not retried.

  The events arrive already decoded (props as maps) and keyed by team in
  their props, so marking them never joins production tables."
  [cfg rows]
  (let [total  (count rows)
        events (into []
                     (comp
                      (filter (fn [row]
                                (and (contains? event-names (:name row))
                                     (not (audit/telemetry-shadow-source? (:source row))))))
                      (map (fn [row]
                             (assoc (consumers/row->event (consumers/decode-row row))
                                    :id (:id row)))))
                     rows)
        {:keys [team-ids invalid]} (collect-team-ids events)
        processed (- (count events) invalid)]
    (mark-dirty (::db/conn cfg) team-ids)
    (l/trc :hint "storage-usage teams marked dirty"
           :events (count events)
           :teams (count team-ids))
    ;; No relevant event carries any team key: a producer stopped keying
    ;; its events. An explicit empty collection does not count as a missing
    ;; key (see carries-team-keys?), so it does not warn.
    (when (and (seq events)
               (not-any? carries-team-keys? events))
      (l/wrn :hint "storage-usage chunk resolved no team ids"
             :events (count events)
             :ids (mapv (comp str :id) events)))
    {:processed processed
     :skipped   (- total processed)}))

(def consumer
  "The always-on consumer marks teams whose storage counter needs recomputing."
  {:id      consumers/team-storage
   :always? true
   :label   "team-storage"
   :process process-chunk})

(defn run-pass
  "One pass of the team-storage consumer: read the stream from its cursor
  up to the frontier of the pass and mark the teams of the storage-usage
  events dirty. The loop of `::consumer` calls it once per pass; direct
  callers (REPL, tests) get the same behavior."
  [cfg]
  (consumers/run-pass cfg consumer))

(defmethod ig/assert-key ::consumer
  [_ cfg]
  (assert (db/pool? (::db/pool cfg)) "expected a valid database pool")
  (assert (mtx/metrics? (::mtx/metrics cfg)) "expected a valid metrics instance"))

(defn- prepare
  [cfg]
  (l/dbg :hint "audit log consumer initialized" :consumer "team-storage")
  cfg)

(defmethod ig/init-key ::consumer
  [_ cfg]
  (consumers/init cfg (assoc consumer :prepare prepare)))

(defmethod ig/halt-key! ::consumer
  [_ thread]
  (consumers/halt thread))
