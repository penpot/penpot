;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS SUBSIDIARY SL

(ns app.rpc.notifications
  (:require
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

  Deliberately not the `uuid/zero` system topic: that one is piped
  straight into the client output channel, so anything published there
  would reach every connected client.

  No client can subscribe here. `:subscribe-file` and `:subscribe-team`
  only accept ids that first pass a permission check, so a client can
  never make the bus deliver this topic to it."
  "internal:subscription-revocation")

(defn notify-permissions-changed
  "Asks every backend to re-verify the websocket subscriptions currently
  held by `profile-id`.

  Fire-and-forget: `mbus/pub!` only enqueues, so a subscription can be
  revoked before the announcement reaches the instance that owns it. That
  errs on the safe side (cutting a still-live subscription), and the
  client re-subscribes on its own."
  [cfg profile-id]
  (let [msgbus (::mbus/msgbus cfg)]
    (mbus/pub! msgbus
               :topic internal-revocation-topic
               :message {:type :profile-permissions-changed
                         :profile-id profile-id})))

(defn notify-team-permissions-changed
  "Announces a possible access change to every member of `profiles`.

  Used when a change can move a resource out of the reach of a whole
  team (moving or deleting files and projects). We cannot know which
  members actually relied on it, and announcing too many is safe: the
  watcher re-checks permissions and only closes what genuinely failed."
  [cfg profiles]
  (run! (partial notify-permissions-changed cfg) profiles))
