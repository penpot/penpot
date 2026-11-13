;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS SUBSIDIARY SL

(ns app.rpc.notifications
  (:require
   [app.common.uuid :as uuid]
   [app.db :as db]
   [app.msgbus :as mbus]))

(defn notify-team-change
  [cfg team notification]
  (let [msgbus (::mbus/msgbus cfg)
        team-id (:id team)]
    (mbus/pub! msgbus
               :topic team-id
               :message {:type :team-organization-change
                         :team team
                         :notification notification})))


(defn notify-user-organization-change
  [cfg profile-id organization-id organization-name notification]
  (let [msgbus (::mbus/msgbus cfg)]
    (mbus/pub! msgbus
               :topic profile-id
               :message {:type :user-organization-change
                         :topic profile-id
                         :organization-id organization-id
                         :organization-name organization-name
                         :notification notification})))


(defn notify-organization-deletion
  [cfg organization-id organization-name teams deleted-teams]
  (let [msgbus (::mbus/msgbus cfg)]
    (mbus/pub! msgbus
               :topic organization-id
               :message {:type :organization-deleted
                         :organization-id organization-id
                         :organization-name organization-name
                         :teams teams
                         :deleted-teams deleted-teams})))

(defn notify-organization-change-sso
  [cfg organization-id]
  (let [msgbus (::mbus/msgbus cfg)]
    (mbus/pub! msgbus
               :topic organization-id
               :message {:type :organization-change-sso
                         :organization-id organization-id})))

;; --- Subscription revocation

;; A websocket subscription is authorized once, when it is opened, and
;; that single decision is trusted for as long as the connection lives.
;; A mutation that can take access away announces it here, and every
;; backend re-verifies the subscriptions it owns for that profile.
;;
;; The announcement travels over the message bus precisely because the
;; connection registry is local to one backend instance, while the RPC
;; that revokes access runs on whichever instance received the request,
;; which is usually not the one holding the socket.

(def internal-revocation-topic
  "Message-bus topic carrying backend-internal revocation events.

  A fixed uuid that is never a real resource id, so it cannot collide
  with a file, team or profile topic — and deliberately not the
  `uuid/zero` system topic, which is piped straight into the client
  output channel, so anything published there would reach every
  connected client.

  No client can subscribe here. `:subscribe-file` and `:subscribe-team`
  only accept ids that first pass a permission check, so a client can
  never make the bus deliver this topic to it."
  (uuid/uuid "ffffffff-ffff-ffff-ffff-ffffffffffff"))

(defn notify-permissions-changed
  "Asks every backend to re-verify the websocket subscriptions currently
  held by `profile-id`.

  Deferred past the commit: the watcher decides from a fresh permission
  check on another connection, so announcing before the transaction
  commits would let it see the old state and close nothing. Outside a
  transaction the announcement goes out immediately.

  The watcher always decides from a fresh permission check, so an extra
  announcement is safe: it closes nothing whose access still holds."
  [cfg profile-id]
  (db/after-commit
   (fn []
     (let [msgbus (::mbus/msgbus cfg)]
       (mbus/pub! msgbus
                  :topic internal-revocation-topic
                  :message {:type :profile-permissions-changed
                            :profile-id profile-id})))))

(defn notify-team-permissions-changed
  "Asks every backend to re-verify the subscriptions of every member of
  `team-id`.

  Used when a change can move a resource out of the reach of a whole
  team (moving, deleting or soft-deleting files, projects and teams). One
  event per team: the watcher resolves the members itself, so a large
  team cannot overflow the bounded publish buffers on the way out.

  Like `notify-permissions-changed`, deferred past the commit."
  [cfg team-id]
  (db/after-commit
   (fn []
     (let [msgbus (::mbus/msgbus cfg)]
       (mbus/pub! msgbus
                  :topic internal-revocation-topic
                  :message {:type :team-permissions-changed
                            :team-id team-id})))))
