;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS SUBSIDIARY SL

(ns app.rpc.commands.mcp-document
  (:require
   [app.binfile.common :as bfc]
   [app.common.exceptions :as ex]
   [app.common.schema :as sm]
   [app.config :as cf]
   [app.db :as db]
   [app.nitrate :as nitrate]
   [app.rpc :as-alias rpc]
   [app.rpc.commands.files :as files]
   [app.rpc.commands.profile :as profile]
   [app.rpc.commands.projects :as projects]
   [app.rpc.commands.teams :as teams]
   [app.rpc.doc :as-alias doc]
   [app.util.services :as sv]))

(def ^:private schema:pagination
  [:map
   [:offset {:optional true} [::sm/int {:min 0 :max 1000000}]]
   [:limit {:optional true} [::sm/int {:min 1 :max 100}]]])

(def ^:private schema:file-read
  [:map
   [:id ::sm/uuid]
   [:snapshot {:optional true} [:string {:max 64}]]])

(def schema:get-mcp-document
  [:multi {:dispatch :resource}
   ["teams" [:merge schema:pagination
             [:map {:closed true} [:resource [:= "teams"]]]]]
   ["projects" [:merge schema:pagination
                [:map {:closed true} [:resource [:= "projects"]] [:id ::sm/uuid]]]]
   ["files" [:merge schema:pagination
             [:map {:closed true} [:resource [:= "files"]] [:id ::sm/uuid]]]]
   ["pages" [:merge schema:pagination schema:file-read
             [:map {:closed true} [:resource [:= "pages"]]]]]
   ["shapes" [:merge schema:pagination schema:file-read
              [:map {:closed true} [:resource [:= "shapes"]] [:page-id ::sm/uuid]]]]])

(defn- check-team-sso
  [cfg team-id]
  ;; The resource selector overloads :id and discovery spans teams, so the
  ;; static RPC id-type gate cannot authorize this command. MCP tokens carry
  ;; no SSO session: never reuse the ordinary RPC profile-level SSO cache or
  ;; a browser cookie that happens to accompany a token request.
  (when (contains? cf/flags :admin-console)
    (let [{:keys [authorized sso]} (nitrate/sso-session-authorized? cfg nil team-id nil)]
      (when-not authorized
        (ex/raise :type :authentication
                  :code :nitrate-sso-required
                  :organization-id (:organization-id sso)
                  :team-id team-id
                  :hint "organization SSO authentication required")))))

(defn- get-project
  [cfg id]
  (let [project (db/get cfg :project {:id id})]
    (db/get cfg :team {:id (:team-id project)})
    (check-team-sso cfg (:team-id project))
    project))

(defn- order-discovery
  [rows timestamp descending?]
  ;; Stable sorting retains the unique ID order when timestamps tie. UUID
  ;; strings use the same unsigned ordering as PostgreSQL UUID columns.
  (sort-by timestamp (if descending? #(compare %2 %1) compare)
           (sort-by (comp str :id) rows)))

(defn- get-document
  [cfg profile-id {:keys [resource id page-id snapshot offset]}]
  (case resource
    "teams"
    {:items (mapv (fn [team]
                    (check-team-sso cfg (:id team))
                    (select-keys team [:id :name]))
                  (order-discovery (teams/get-teams cfg profile-id) :created-at false))}

    "projects"
    (do
      (teams/check-read-permissions! cfg profile-id id)
      (db/get cfg :team {:id id})
      (check-team-sso cfg id)
      {:items (into [] (comp (remove :deleted-at)
                             (map #(select-keys % [:id :name :team-id])))
                    (order-discovery (projects/get-projects cfg profile-id id) :modified-at true))})

    "files"
    (do
      (projects/check-read-permissions! cfg profile-id id)
      (get-project cfg id)
      {:items (mapv #(select-keys % [:id :name :project-id :revn :vern])
                    (order-discovery (files/get-project-files cfg id) :modified-at true))})

    (do
      (files/check-read-permissions! cfg profile-id id)
      (let [metadata (db/get-with-sql cfg
                                      ["select id, project_id, revn, vern, deleted_at from file where id = ?" id])
            _        (get-project cfg (:project-id metadata))
            current  (str (:revn metadata) ":" (:vern metadata))
            _        (when (and (pos? (or offset 0)) (nil? snapshot))
                       (ex/raise :type :validation :code :snapshot-required))
            _        (when (and snapshot (not= snapshot current))
                       (ex/raise :type :validation :code :document-changed))
            file     (-> (bfc/get-file cfg id :read-only? true :realize? true)
                         (files/check-version!))
            data     (:data file)
            page     (when (= resource "shapes") (get-in data [:pages-index page-id]))]
        (when (and (= resource "shapes") (nil? page))
          (ex/raise :type :not-found :code :object-not-found))
        {:file-id id
         :snapshot current
         :items (if (= resource "pages")
                  (mapv #(select-keys (get-in data [:pages-index %]) [:id :name]) (:pages data))
                  (mapv #(select-keys % [:id :name :type :parent-id :frame-id :x :y :width :height])
                        (sort-by :id (vals (:objects page)))))}))))

(sv/defmethod ::get-mcp-document
  "Read document summaries without an editor or plugin connection."
  {::rpc/token-type "mcp"
   ::db/transaction true
   ::doc/added "2.20"
   ::sm/params schema:get-mcp-document}
  [cfg {:keys [::rpc/profile-id] :as params}]
  ;; Keep file metadata and lazily loaded fragments in one snapshot, and
  ;; prevent read-time migrations from persisting changes.
  (db/exec! cfg ["SET TRANSACTION ISOLATION LEVEL REPEATABLE READ, READ ONLY"])
  (let [profile (profile/get-profile cfg profile-id)]
    ;; Blocking a profile invalidates sessions, not access tokens.
    (when (:is-blocked profile)
      (ex/raise :type :authorization :code :profile-blocked))
    (when-not (:is-active profile)
      (ex/raise :type :authorization :code :profile-not-active))
    (let [{:keys [items] :as result} (get-document cfg profile-id params)
          offset (get params :offset 0)
          limit  (get params :limit 100)
          batch  (vec (take (inc limit) (drop offset items)))]
      (assoc result
             :items (vec (take limit batch))
             :next-offset (when (> (count batch) limit) (+ offset limit))))))
