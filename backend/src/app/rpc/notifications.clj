;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC

(ns app.rpc.notifications
  (:require
   [app.common.uuid :as uuid]
   [app.msgbus :as mbus]))

(defn notify-team-change
  [cfg team-id team-name organization-id organization-name notification]
  (let [msgbus (::mbus/msgbus cfg)]
    (mbus/pub! msgbus
               ;;TODO There is a bug on dashboard with teams notifications.
               ;;For now we send it to uuid/zero instead of team-id
               :topic uuid/zero
               :message {:type :team-org-change
                         :team-id team-id
                         :team-name team-name
                         :organization-id organization-id
                         :organization-name organization-name
                         :notification notification})))