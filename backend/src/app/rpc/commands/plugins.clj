;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS SUBSIDIARY SL

(ns app.rpc.commands.plugins
  (:require
   [app.common.exceptions :as ex]
   [app.common.schema :as sm]
   [app.common.types.plugins :as ctp]
   [app.db :as db]
   [app.rpc :as-alias rpc]
   [app.rpc.climit :as-alias climit]
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
   ::climit/id [[:profile-plugin-ops/by-profile ::rpc/profile-id]
                [:profile-plugin-ops/global]]
   ::sm/params schema:add-profile-plugin
   ::sm/result ctp/schema:registry-entry
   ::db/transaction true}
  [{:keys [::db/conn] :as cfg} {:keys [::rpc/profile-id plugin]}]
  (validate-plugin-permissions! plugin)

  (let [profile (profile/get-profile conn profile-id ::db/for-update true)
        plugins (get-in profile [:props :plugins] {:ids [] :data {}})
        plugin-id (:plugin-id plugin)
        exists? (contains? (set (:ids plugins)) plugin-id)]
    (when (and (not exists?) (>= (count (:ids plugins)) ctp/max-plugins))
      (ex/raise :type :validation
                :code :too-many-plugins
                :hint "plugin registry exceeds maximum size"))
    (let [plugins (-> plugins
                      (update :ids #(vec (distinct (conj % plugin-id))))
                      (assoc-in [:data plugin-id] plugin))
          props   (-> (:props profile)
                      (assoc :plugins plugins)
                      (profile/check-props-size))]
      (db/update! conn :profile
                  {:props (db/tjson props)}
                  {:id profile-id}
                  {::db/return-keys false})
      plugin)))

(def ^:private
  schema:remove-profile-plugin
  [:map {:title "remove-profile-plugin"}
   [:plugin-id ::sm/uuid]])

(sv/defmethod ::remove-profile-plugin
  {::doc/added "2.18"
   ::climit/id [[:profile-plugin-ops/by-profile ::rpc/profile-id]
                [:profile-plugin-ops/global]]
   ::sm/params schema:remove-profile-plugin
   ::sm/result :nil
   ::db/transaction true}
  [{:keys [::db/conn] :as cfg} {:keys [::rpc/profile-id plugin-id]}]
  (let [profile (profile/get-profile conn profile-id ::db/for-update true)
        plugins (get-in profile [:props :plugins] {:ids [] :data {}})
        plugin-id-str (str plugin-id)]
    (if-not (or (some #(= % plugin-id-str) (:ids plugins))
                (contains? (:data plugins) plugin-id-str))
      ;; Nothing to remove: skip the write (and the size check) entirely.
      nil
      (let [plugins (-> plugins
                        (update :ids #(vec (remove (partial = plugin-id-str) %)))
                        (update :data dissoc plugin-id-str))
            ;; The size check runs on the resulting props: removal usually
            ;; shrinks them, but still raises when the remaining props exceed
            ;; the limit. Kept for uniformity so the user-facing profile.props
            ;; RPC writes (update-profile-props, update-profile-notifications,
            ;; add/remove-profile-plugin) all go through the size check. System
            ;; writers (OIDC login merge, management subscription update) are
            ;; exempt: they write fixed-key, non-accumulating shapes.
            props   (-> (:props profile)
                        (assoc :plugins plugins)
                        (profile/check-props-size))]
        (db/update! conn :profile
                    {:props (db/tjson props)}
                    {:id profile-id}
                    {::db/return-keys false})
        nil))))
