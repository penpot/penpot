;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC Sucursal en España SL

(ns app.rpc.commands.plugins
  (:require
   [app.common.exceptions :as ex]
   [app.common.schema :as sm]
   [app.common.types.plugins :as ctp]
   [app.db :as db]
   [app.rpc :as-alias rpc]
   [app.rpc.commands.profile :as profile]
   [app.rpc.doc :as-alias doc]
   [app.util.services :as sv]))

(defn- validate-plugin-permissions!
  "Validates that all permissions in the plugin are within the valid set."
  [plugin]
  (let [permissions (:permissions plugin)
        invalid     (remove ctp/valid-permissions permissions)]
    (when (seq invalid)
      (ex/raise :type :validation
                :code :invalid-plugin-permissions
                :hint (str "Invalid permissions: " (pr-str (set invalid)))
                :invalid-permissions (set invalid)))))

(def ^:private
  schema:add-profile-plugin
  [:map {:title "add-profile-plugin"}
   [:plugin ctp/schema:registry-entry]])

(sv/defmethod ::add-profile-plugin
  {::doc/added "2.18"
   ::sm/params schema:add-profile-plugin
   ::sm/result ctp/schema:registry-entry
   ::db/transaction true}
  [{:keys [::db/conn] :as cfg} {:keys [::rpc/profile-id plugin]}]
  (validate-plugin-permissions! plugin)

  (let [profile (profile/get-profile conn profile-id ::db/for-update true)
        plugins (get-in profile [:props :plugins] {:ids [] :data {}})
        plugin-id (:plugin-id plugin)
        plugins (-> plugins
                    (update :ids #(vec (distinct (conj % plugin-id))))
                    (assoc-in [:data plugin-id] plugin))]
    (db/update! conn :profile
                {:props (db/tjson (assoc (:props profile) :plugins plugins))}
                {:id profile-id}
                {::db/return-keys false})
    plugin))

(def ^:private
  schema:remove-profile-plugin
  [:map {:title "remove-profile-plugin"}
   [:plugin-id ::sm/uuid]])

(sv/defmethod ::remove-profile-plugin
  {::doc/added "2.18"
   ::sm/params schema:remove-profile-plugin
   ::sm/result :nil
   ::db/transaction true}
  [{:keys [::db/conn] :as cfg} {:keys [::rpc/profile-id plugin-id]}]
  (let [profile (profile/get-profile conn profile-id ::db/for-update true)
        plugins (get-in profile [:props :plugins] {:ids [] :data {}})
        plugin-id-str (str plugin-id)
        plugins (-> plugins
                    (update :ids #(vec (remove (partial = plugin-id-str) %)))
                    (update :data dissoc plugin-id-str))]
    (db/update! conn :profile
                {:props (db/tjson (assoc (:props profile) :plugins plugins))}
                {:id profile-id}
                {::db/return-keys false})
    nil))
