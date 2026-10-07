;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS SUBSIDIARY SL

(ns app.http.websocket
  "A penpot notification service for file cooperative edition."
  (:require
   [app.binfile.common :as bfc]
   [app.common.data.macros :as dm]
   [app.common.exceptions :as ex]
   [app.common.logging :as l]
   [app.common.pprint :as pp]
   [app.common.schema :as sm]
   [app.common.time :as ct]
   [app.common.uuid :as uuid]
   [app.config :as cf]
   [app.db :as db]
   [app.http.session :as session]
   [app.metrics :as mtx]
   [app.msgbus :as mbus]
   [app.nitrate :as nitrate]
   [app.rpc.commands.files :as files]
   [app.rpc.commands.teams :as teams]
   [app.rpc.notifications :as notifications]
   [app.util.websocket :as ws]
   [integrant.core :as ig]
   [promesa.exec :as px]
   [promesa.exec.csp :as sp]
   [yetti.websocket :as yws]))

(def recv-labels
  (into-array String ["recv"]))

(def send-labels
  (into-array String ["send"]))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; WEBSOCKET HOOKS
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(def state
  "Registry of the websocket connections held by this backend instance.

  Holds `:connections` (connection id -> connection data) and
  `:by-profile` (profile id -> set of connection ids). Both live in the
  same atom so a single `swap!` keeps them consistent, and so the
  profile index never has to be rebuilt by scanning every connection.

  NOTE: the registry is local to one backend instance, unlike the
  message bus, which is shared. Code that must reach connections
  regardless of which instance owns them has to go through the bus."
  (atom {:connections {}
         :by-profile {}}))

(defn register-connection
  "Adds `wsp` to the registry under `id`, indexing it by its profile."
  [id wsp]
  (let [profile-id (::profile-id wsp)]
    (swap! state
           (fn [st]
             (-> st
                 (assoc-in [:connections id] wsp)
                 (cond-> profile-id
                   (update-in [:by-profile profile-id]
                              (fnil conj #{}) id)))))))

(defn unregister-connection
  "Removes the connection `id` from the registry and from the profile
  index."
  [id]
  (swap! state
         (fn [{:keys [connections] :as st}]
           (let [owner   (::profile-id (get connections id))
                 indexed (get-in st [:by-profile owner])
                 indexed (if (set? indexed) (disj indexed id) indexed)]
             (cond-> (assoc st :connections (dissoc connections id))
               (and owner (seq indexed))
               (assoc-in [:by-profile owner] indexed)
               (and owner (empty? indexed))
               (update :by-profile dissoc owner))))))

(defn get-connection
  "Returns the connection data registered under `id`, or nil."
  [id]
  (get-in @state [:connections id]))

(defn connections-for-profile
  "Returns the set of connection ids currently held by `profile-id`."
  [profile-id]
  (get-in @state [:by-profile profile-id] #{}))

;; REPL HELPERS

(defn repl-get-connections-for-file
  [file-id]
  (->> (vals (:connections @state))
       (filter #(= file-id (-> % ::ws/state deref ::file-subscription :file-id)))
       (map ::ws/id)))

(defn repl-get-connections-for-team
  [team-id]
  (->> (vals (:connections @state))
       (filter #(= team-id (-> % ::ws/state deref ::team-subscription :team-id)))
       (map ::ws/id)))

(defn repl-get-connections-for-profile
  [profile-id]
  (connections-for-profile profile-id))

(defn repl-close-connection
  [id]
  (when-let [{:keys [::ws/close-ch]} (get-connection id)]
    (sp/put! close-ch [8899 "closed from server"])
    (sp/close! close-ch)))

(defn repl-get-connection-info
  [id]
  (when-let [wsp (get-connection id)]
    (let [subs (some-> wsp ::ws/state deref)]
      {:id               id
       :created-at       (::created-at wsp)
       :profile-id       (::profile-id wsp)
       :session-id       (::session-id wsp)
       :user-agent       (::ws/user-agent wsp)
       :ip-addr          (::ws/remote-addr wsp)
       :last-activity-at (::ws/last-activity-at wsp)
       :subscribed-file  (-> subs ::file-subscription :file-id)
       :subscribed-team  (-> subs ::team-subscription :team-id)
       :subscribed-org   (-> subs ::team-subscription :organization-id)})))

(defn repl-print-connection-info
  [id]
  (some-> id repl-get-connection-info pp/pprint))

(defn repl-print-connection-info-for-file
  [file-id]
  (some->> (repl-get-connections-for-file file-id)
           (map repl-get-connection-info)
           (pp/pprint)))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; SUBSCRIPTIONS
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

;; A subscription is authorized once, when it is opened, and that
;; single decision is then trusted for as long as the connection
;; lives. Two things bound that: a mutation that changes access
;; announces it (see the revocation section below), and the relay
;; re-checks on an interval.

(def default-revalidation-interval
  "How long a subscription may rely on the authorization it was granted
  with before its relay loop re-checks it.

  This is the safety net for what the announcement cannot cover: an
  access change made outside a Penpot command. Nitrate transferring the
  ownership of an organization is an HTTP call to another service that
  fires no RPC here, and rows deleted straight over SQL fire nothing
  either. Both are rare, so the interval is generous; the cost is one
  permission query per subscription per interval."
  (ct/duration {:minutes 5}))

(defn revalidation-interval
  []
  (cf/get :subscription-revalidation-interval default-revalidation-interval))

(defn- still-authorized?
  "Evaluates the `pred` permission predicate, reporting instead of
  throwing when the lookup itself fails.

  A transient database error must not close a subscription that is still
  legitimate, and the next tick tries again. This is the one place that
  fails open, deliberately: the announcement path is the primary
  mechanism and stays strict."
  [pred]
  (try
    (boolean pred)
    (catch Throwable cause
      (l/error :hint "cannot re-check a websocket subscription"
               :cause cause)
      true)))

(defn close-file-subscription
  "Tears down the file subscription held by the connection `wsp`, if it
  is subscribed to `file-id`.

  Announces the departure so the remaining participants drop the
  presence of this session, closes the relay channel (which in turn
  stops the `:subscribe-file` go-loop, because `take!` on a closed
  channel returns nil) and removes the subscription from the bus.

  Does nothing when the connection is subscribed to a different file,
  so it is safe to call for a subscription that is already gone."
  [{:keys [::mbus/msgbus]} {:keys [::ws/state ::session-id ::profile-id]} file-id]
  (let [subs (::file-subscription @state)]
    (when (= (:file-id subs) file-id)
      (mbus/pub! msgbus
                 :topic file-id
                 :message {:type :leave-file
                           :file-id file-id
                           :session-id session-id
                           :profile-id profile-id})
      (let [ch (:channel subs)]
        (sp/close! ch)
        (mbus/purge! msgbus [ch])
        (swap! state dissoc ::file-subscription)))))

(defn close-team-subscription
  "Tears down the team subscription held by the connection `wsp`, if it
  is subscribed to `team-id`.

  Closing the channel is what stops the relay, so no further team or
  organization traffic reaches this connection.

  Does nothing when the connection is subscribed to a different team,
  so it is safe to call for a subscription that is already gone."
  [{:keys [::mbus/msgbus]} {:keys [::ws/state]} team-id]
  (let [subs (::team-subscription @state)]
    (when (= (:team-id subs) team-id)
      (let [ch (:channel subs)]
        (sp/close! ch)
        (mbus/purge! msgbus [ch])
        (swap! state dissoc ::team-subscription)))))


(defn- schedule-revalidation
  "Rearms `task` every `interval-ms` for as long as `running?` holds.

  Kept independent of the relay loop on purpose: a re-check driven by
  the same loop would be pushed away by a steady stream of messages, so
  a busy file would never be re-checked at all. A task whose `running?`
  turned false stops rearming."
  [running? interval-ms task]
  (when @running?
    (px/schedule interval-ms
                 (fn []
                   (when @running?
                     (task)
                     (schedule-revalidation running? interval-ms task))))))

(defn- start-relay
  "Forwards `channel` into the client output channel of `wsp`, and keeps
  a re-check of `authorized?` running on the interval. When access no
  longer holds, `close!` tears the subscription down, which closes the
  channel and ends the loop.

  `on-forward` runs for every forwarded message, which is how the file
  relay announces presence."
  [_ {:keys [::ws/output-ch] :as wsp} channel authorized? close! on-forward]
  (let [running? (atom true)]
    (schedule-revalidation running?
                           (inst-ms (revalidation-interval))
                           (fn []
                             (when-not (still-authorized? authorized?)
                               (l/info :hint
                                       "closing websocket subscription on re-check"
                                       :profile-id (::profile-id wsp))
                               (reset! running? false)
                               (close!))))
    (sp/go-loop []
      ;; nil means the channel was closed, which means the subscription
      ;; is gone and the relay ends.
      (when-let [message (sp/take! channel)]
        (sp/put! output-ch message)
        (when on-forward (on-forward message))
        (recur)))))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; WEBSOCKET HANDLER
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(defmulti handle-message
  (fn [_ _ message]
    (:type message)))

(defmethod handle-message :open
  [{:keys [::mbus/msgbus]} {:keys [::ws/id ::ws/output-ch ::ws/state ::profile-id ::session-id] :as wsp} _]
  (l/trace :fn "handle-message" :event "open" :conn-id id)
  (let [ch (sp/chan :buf (sp/dropping-buffer 16)
                    :xf  (remove #(= (:session-id %) session-id)))]

    ;; Subscribe to the profile channel and forward all messages to websocket output
    ;; channel (send them to the client).
    (swap! state assoc ::profile-subscription {:channel ch})

    ;; Forward the subscription messages directly to the websocket output channel
    (sp/pipe ch output-ch false)

    ;; Subscribe to the profile topic on msgbus/redis
    (mbus/sub! msgbus :topic profile-id :chan ch)

    ;; Subscribe to the system topic on msgbus/redis
    (mbus/sub! msgbus :topic (str uuid/zero) :chan ch)))

(defmethod handle-message :close
  [{:keys [::mbus/msgbus]} {:keys [::ws/id ::ws/state ::profile-id ::session-id]} _]
  (l/trace :fn "handle-message" :event "close" :conn-id id)
  (let [psub (::profile-subscription @state)
        fsub (::file-subscription @state)
        tsub (::team-subscription @state)
        msg  {:type :disconnect
              :profile-id profile-id
              :session-id session-id}]

    ;; Close profile subscription if exists
    (when-let [ch (:channel psub)]
      (sp/close! ch)
      (mbus/purge! msgbus [ch]))

    ;; Close team subscription if exists
    (when-let [ch (:channel tsub)]
      (sp/close! ch)
      (mbus/purge! msgbus [ch]))

    ;; Close file subscription if exists
    (when-let [{:keys [topic channel]} fsub]
      (sp/close! channel)
      (mbus/purge! msgbus [channel])
      (mbus/pub! msgbus :topic topic :message msg))))

(defn- get-team-organization-id
  "Returns the id of the organization that owns `team-id`, or nil when
  the team has no organization or nitrate cannot be reached."
  [cfg team-id]
  (try
    (-> (nitrate/call cfg :get-team-organization {:team-id team-id})
        (dm/get-in [:organization :id]))
    (catch Throwable cause
      (l/warn :hint "unable to resolve team organization"
              :team-id team-id
              :cause cause)
      nil)))

(defmethod handle-message :subscribe-team
  [cfg {:keys [::ws/id ::ws/state ::session-id ::profile-id] :as wsp} {:keys [team-id] :as params}]
  (l/trace :fn "handle-message" :event "subscribe-team" :team-id team-id :conn-id id)
  (teams/check-read-permissions! cfg profile-id team-id)
  (let [prev-subs       (::team-subscription @state)
        organization-id (get-team-organization-id cfg team-id)
        ;; Resolved server-side so a client only hears its readable team's org
        topics          (cond-> [team-id]
                          (some? organization-id)
                          (conj organization-id))
        channel         (sp/chan :buf (sp/dropping-buffer 64)
                                 :xf  (remove #(= (:session-id %) session-id)))]

    ;; The team topic also carries library change diffs, so a
    ;; subscription that outlives access to the team leaks content, not
    ;; just presence.
    (start-relay cfg wsp channel
                 #(teams/has-read-permissions? cfg profile-id team-id)
                 #(close-team-subscription cfg wsp team-id)
                 nil)

    (let [subs {:team-id team-id
                :organization-id organization-id
                :channel channel
                :topic team-id}]
      (swap! state assoc ::team-subscription subs))

    (mbus/sub! (::mbus/msgbus cfg) :topics topics :chan channel)

    ;; Close previous subscription if exists
    (when-let [ch (:channel prev-subs)]
      (sp/close! ch)
      (mbus/purge! (::mbus/msgbus cfg) [ch]))))

(defmethod handle-message :subscribe-file
  [cfg {:keys [::ws/id ::ws/state ::session-id ::profile-id] :as wsp} {:keys [file-id] :as params}]
  (l/trace :fn "handle-message" :event "subscribe-file" :file-id file-id :conn-id id)
  (bfc/check-file-exists cfg file-id)
  (files/check-read-permissions! cfg profile-id file-id)
  (let [psub (::file-subscription @state)
        fch  (sp/chan :buf (sp/dropping-buffer 64)
                      :xf  (remove #(= (:session-id %) session-id)))]

    (let [subs {:file-id file-id :channel fch :topic file-id}]
      (swap! state assoc ::file-subscription subs))

    (start-relay cfg wsp fch
                 #(files/has-read-permissions? cfg profile-id file-id)
                 #(close-file-subscription cfg wsp file-id)
                 (fn [message]
                   (when (contains? #{:join-file :leave-file :disconnect}
                                    (:type message))
                     (mbus/pub! (::mbus/msgbus cfg)
                                :topic file-id
                                :message {:type :presence
                                          :file-id file-id
                                          :session-id session-id
                                          :profile-id profile-id}))))

    ;; Close previous subscription if exists
    (when-let [ch (:channel psub)]
      (sp/close! ch)
      (mbus/purge! (::mbus/msgbus cfg) [ch]))

    ;; Subscribe to file topic
    (mbus/sub! (::mbus/msgbus cfg) :topic file-id :chan fch)

    ;; Notifify the rest of participants of the new connection.
    (mbus/pub! (::mbus/msgbus cfg)
               :topic file-id
               :message {:type :join-file
                         :file-id file-id
                         :session-id session-id
                         :profile-id profile-id})))

(defmethod handle-message :unsubscribe-file
  [cfg {:keys [::ws/id] :as wsp} {:keys [file-id] :as params}]
  (l/trace :fn "handle-message" :event "unsubscribe-file" :file-id file-id :conn-id id)
  (close-file-subscription cfg wsp file-id))

(defmethod handle-message :keepalive
  [_ _ _]
  (l/trace :fn "handle-message" :event :keepalive))

(defmethod handle-message :broadcast
  [{:keys [::mbus/msgbus]} {:keys [::ws/id ::session-id ::profile-id]} message]
  (l/trace :fn "handle-message" :event "broadcast" :conn-id id)
  (let [message (-> message
                    (assoc :subs-id profile-id)
                    (assoc :profile-id profile-id)
                    (assoc :session-id session-id))]
    (mbus/pub! msgbus :topic profile-id :message message)))

(defmethod handle-message :pointer-update
  [{:keys [::mbus/msgbus]} {:keys [::ws/state ::session-id ::profile-id]} {:keys [file-id] :as message}]
  (when-let [subs (::file-subscription @state)]
    (when (= file-id (:file-id subs))
      (let [message (-> message
                        (assoc :subs-id file-id)
                        (assoc :profile-id profile-id)
                        (assoc :session-id session-id))]
        (mbus/pub! msgbus :topic file-id :message message)))))

(defmethod handle-message :default
  [_ {:keys [::ws/id]} message]
  (l/warn :hint "received unexpected message"
          :message message
          :conn-id id))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; SUBSCRIPTION REVOCATION
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

;; A subscription is authorized once, when it is opened, and that single
;; decision is then trusted for as long as the connection lives. The
;; mutation that changes access announces it on
;; `app.rpc.notifications/internal-revocation-topic`, and every backend
;; re-verifies the subscriptions it owns for that profile.
;;
;; The announcement travels over the message bus precisely because this
;; registry is local: the RPC that revokes access runs on whichever
;; instance received the request, which is usually not the one holding
;; the socket.

(defn revalidate-profile-subscriptions
  "Re-checks every subscription held by the connections of `profile-id`
  and closes the ones that are no longer authorized.

  The event is the trigger, the fresh permission check is the decision.
  Reusing the same predicates that `:subscribe-file` and
  `:subscribe-team` authorize with means the watcher needs no second copy
  of the permission rules: a downgrade to viewer keeps read access and
  so keeps the subscription, and a non-member organization owner keeps
  the read-only access it is entitled to.

  Returns the number of subscriptions it closed."
  [cfg profile-id]
  (let [closed (volatile! 0)]
    (doseq [id (connections-for-profile profile-id)]
      (when-let [wsp (get-connection id)]
        (let [subs (some-> wsp ::ws/state deref)
              fsub (get subs ::file-subscription)
              tsub (get subs ::team-subscription)]
          (when (and fsub
                     (not (files/has-read-permissions? cfg profile-id
                                                       (:file-id fsub))))
            (close-file-subscription cfg wsp (:file-id fsub))
            (vswap! closed inc))
          (when (and tsub
                     (not (teams/has-read-permissions? cfg profile-id
                                                       (:team-id tsub))))
            (close-team-subscription cfg wsp (:team-id tsub))
            (vswap! closed inc)))))
    @closed))

(defn- watch-revocations
  "Consumes revocation events and applies them to the connections this
  instance owns."
  [{:keys [::mbus/msgbus] :as cfg}]
  (let [ch (sp/chan :buf (sp/dropping-buffer 64))]
    (mbus/sub! msgbus
               :topic notifications/internal-revocation-topic
               :chan ch)
    (sp/go-loop []
      (when-let [{:keys [type profile-id]} (sp/take! ch)]
        (when (= :profile-permissions-changed type)
          (try
            (when (pos? (revalidate-profile-subscriptions cfg profile-id))
              (l/debug :hint "revoked websocket subscriptions"
                       :profile-id profile-id))
            (catch Throwable cause
              (l/error :hint "cannot revalidate websocket subscriptions"
                       :profile-id profile-id
                       :cause cause))))
        (recur)))))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; INTEGRANT
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(def ^:private schema:revocation-watcher
  [:map
   ::mbus/msgbus
   ::db/pool
   :app.nitrate/client])

(defmethod ig/assert-key ::revocation-watcher
  [_ params]
  (assert (sm/valid? schema:revocation-watcher params)))

(defmethod ig/init-key ::revocation-watcher
  [_ cfg]
  (watch-revocations cfg))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; HTTP HANDLER
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(defn- on-connect
  [{:keys [::mtx/metrics]} {:keys [::ws/id] :as wsp}]
  (let [created-at (ct/now)]
    (l/trace :fn "on-connect" :conn-id id)
    (register-connection id wsp)
    (mtx/run! metrics
              :id :websocket-active-connections
              :inc 1)

    (assoc wsp ::ws/on-disconnect
           (fn []
             (l/trace :fn "on-disconnect" :conn-id id)
             (unregister-connection id)
             (mtx/run! metrics :id :websocket-active-connections :dec 1)
             (mtx/run! metrics
                       :id :websocket-session-timing
                       :val (/ (inst-ms (ct/diff created-at (ct/now))) 1000.0))))))

(defn- on-rcv-message
  [{:keys [::mtx/metrics ::profile-id ::session-id]} message]
  (mtx/run! metrics
            :id :websocket-messages-total
            :labels recv-labels
            :inc 1)
  (assoc message :profile-id profile-id :session-id session-id))

(defn- on-snd-message
  [{:keys [::mtx/metrics]} message]
  (mtx/run! metrics
            :id :websocket-messages-total
            :labels send-labels
            :inc 1)
  message)

(defn- http-handler
  [cfg {:keys [params ::session/profile-id] :as request}]
  (let [session-id (some-> params :session-id uuid/parse*)]
    (when-not (uuid? session-id)
      (ex/raise :type :validation
                :code :missing-session-id
                :hint "missing or invalid session-id found"))

    (cond
      (not profile-id)
      (ex/raise :type :authentication
                :hint "authentication required")

      ;; WORKAROUND: we use the adapter specific predicate for
      ;; performance reasons; for now, the ring default impl for
      ;; `upgrade-request?` parses all requests headers before perform
      ;; any checking.
      (not (yws/upgrade-request? request))
      (ex/raise :type :validation
                :code :websocket-request-expected
                :hint "this endpoint only accepts websocket connections")

      :else
      (do
        (l/trace :hint "websocket request" :profile-id profile-id :session-id session-id)
        {::yws/listener (ws/listener request
                                     ::ws/on-rcv-message (partial on-rcv-message cfg)
                                     ::ws/on-snd-message (partial on-snd-message cfg)
                                     ::ws/on-connect (partial on-connect cfg)
                                     ::ws/handler (partial handle-message cfg)
                                     ::profile-id profile-id
                                     ::session-id session-id)}))))


(def ^:private schema:routes-params
  [:map
   ::mbus/msgbus
   ::mtx/metrics
   ::db/pool
   ::session/manager])

(defmethod ig/assert-key ::routes
  [_ params]
  (assert (sm/valid? schema:routes-params params)))

(defmethod ig/init-key ::routes
  [_ cfg]
  ["/ws/notifications" {:middleware [[session/authz cfg]]
                        :handler (partial http-handler cfg)}])
