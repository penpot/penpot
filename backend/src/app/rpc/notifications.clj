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