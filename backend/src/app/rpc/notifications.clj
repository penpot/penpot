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
