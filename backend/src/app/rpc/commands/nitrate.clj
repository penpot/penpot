(ns app.rpc.commands.nitrate
  (:require
   [app.common.data :as d]
   [app.common.schema :as sm]
   [app.db :as db]
   [app.nitrate :as nitrate]
   [app.rpc :as-alias rpc]
   [app.rpc.commands.teams :as teams]
   [app.rpc.doc :as-alias doc]
   [app.util.services :as sv]))


(def schema:connectivity
  [:map {:title "nitrate-connectivity"}
   [:licenses ::sm/boolean]])

(sv/defmethod ::get-nitrate-connectivity
  {::rpc/auth false
   ::doc/added "2.14"
   ::sm/params [:map]
   ::sm/result schema:connectivity}
  [cfg _params]
  (nitrate/call cfg :connectivity {}))

(def ^:private sql:prefix-team-name-and-unset-default
  "UPDATE team
      SET name = ? || name,
          is_default = FALSE
    WHERE id = ?;")

(def ^:private schema:leave-org-schema
  [:map
   [:org-id ::sm/uuid]
   [:org-name ::sm/text]
   [:default-team-id ::sm/uuid]
   [:teams-to-delete
    [:vector ::sm/uuid]]
   [:teams-to-leave
    [:vector
     [:map
      [:id ::sm/uuid]
      [:reassign-to {:optional true} ::sm/uuid]]]]])

(sv/defmethod ::leave-org
  {::rpc/auth false
   ::doc/added "2.15"
   ::sm/params schema:leave-org-schema}
  [cfg {:keys [::rpc/profile-id org-id org-name default-team-id teams-to-delete teams-to-leave] :as params}]
  (db/tx-run!
   cfg
   (fn [{:keys [::db/conn] :as cfg}]
     (let [org-prefix (str "[" (d/sanitize-string org-name) "] ")]

       ;; delete the teams-to-delete
       (doseq [id teams-to-delete]
         (teams/delete-team cfg {:profile-id profile-id :team-id id}))

       ;; leave the teams-to-leave
       (doseq [{:keys [id reassign-to]} teams-to-leave]
         (teams/leave-team cfg {:profile-id profile-id :id id :reassign-to reassign-to}))

       ;; Rename default-team-id
       (db/exec! conn [sql:prefix-team-name-and-unset-default org-prefix default-team-id])

       ;; Api call to nitrate
       (nitrate/call cfg :remove-profile-from-org {:profile-id profile-id :org-id org-id})

       nil))))
