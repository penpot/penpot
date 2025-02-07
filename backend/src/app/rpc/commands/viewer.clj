;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC

(ns app.rpc.commands.viewer
  (:require
   [app.common.exceptions :as ex]
   [app.common.features :as cfeat]
   [app.common.schema :as sm]
   [app.config :as cf]
   [app.db :as db]
   [app.rpc :as-alias rpc]
   [app.rpc.commands.files :as files]
   [app.rpc.commands.teams :as teams]
   [app.rpc.cond :as-alias cond]
   [app.rpc.doc :as-alias doc]
   [app.util.services :as sv]))

;; --- QUERY: View Only Bundle

(defn- remove-not-allowed-pages
  [data allowed]
  (-> data
      (update :pages (fn [pages] (filterv #(contains? allowed %) pages)))
      (update :pages-index select-keys allowed)))

(defn- get-viewer-bundle
  [{:keys [::db/conn] :as cfg} {:keys [profile-id file-id share-id ::perms] :as params}]
  (let [file    (files/get-file cfg file-id)

        _       (-> (cfeat/get-team-enabled-features cf/flags team)
                    (cfeat/check-client-features! (:features params))
                    (cfeat/check-file-features! (:features file)))

        file    (cond-> file
                  (= :share-link (:type perms))
                  (update :data remove-not-allowed-pages (:pages perms))

                  :always
                  (update :data select-keys [:id :options :pages :pages-index :components]))

        libs    (files/get-file-libraries conn file-id)

        links   (if (= :membership (:type perms))
                  (db/query conn :share-link {:file-id file-id})
                  (db/query conn :share-link {:file-id file-id :id share-id}))

        links   (mapv (fn [row]
                        (-> row
                            (update :pages db/decode-pgarray #{})
                            ;; NOTE: the flags are deprecated but are still present
                            ;; on the table on old rows. The flags are pgarray and
                            ;; for avoid decoding it (because they are no longer used
                            ;; on frontend) we just dissoc the column attribute from
                            ;; row.
                            (dissoc :flags)))
                      links)

        fonts   (db/query conn :team-font-variant
                          {:team-id (:id team)
                           :deleted-at nil})]

    {:fonts fonts
     :share-links links
     :libraries libs
     :file file}))

(def schema:get-viewer-bundle
  [:map {:title "get-view-only-bundle"}
   [:file-id ::sm/uuid]
   [:share-id {:optional true} ::sm/uuid]
   [:features {:optional true} ::cfeat/features]])

(sv/defmethod ::get-viewer-bundle
  {::rpc/auth false
   ::doc/added "2.4"
   ::sm/params schema:get-viewer-bundle}
  [system {:keys [::rpc/profile-id file-id share-id] :as params}]
  (db/run! system
           (fn [{:keys [::db/conn] :as system}]
             (let [perms  (files/get-permissions conn profile-id file-id share-id)
                   params (-> params
                              (assoc ::perms perms)
                              (assoc :profile-id profile-id))]

               ;; When we have neither profile nor share, we just return a not
               ;; found response to the user.
               (when-not perms
                 (ex/raise :type :not-found
                           :code :object-not-found
                           :hint "object not found"))

               (get-view-only-bundle system params)))))


(def ^:private sql:get-team-and-project
  "SELECT t.*,
          p.id AS project_id,
          p.name AS project_name
     FROM team AS t
    INNER JOIN project AS p ON (p.team_id = t.id)
    INNER JOIN file AS f ON (f.project_id = p.id)
    WHERE f.id = ?")

(def ^:private schema:get-viewer-info
  [:map {:title "get-viewer-info"}
   [:file-id ::sm/uuid]
   [:share-id {:optional true} ::sm/uuid]])

(sv/defmethod ::get-viewer-info
  "Get bootstrap data for team, for logged or unlogged user"
  {::rpc/auth false
   ::doc/added "2.5"
   ::sm/params schema:get-viewer-info}
  [system {:keys [::rpc/profile-id file-id share-id] :as params}]
  (db/run! system
           (fn [{:keys [::db/conn]}]
             (let [perms (files/get-permissions conn profile-id file-id share-id)]
               ;; When we have neither profile nor share, we just return a not
               ;; found response to the user.
               (when-not perms
                 (ex/raise :type :not-found
                           :code :object-not-found
                           :hint "object not found"))

               (let [data       (db/exec-one! conn [sql:get-team-and-project file-id])
                     team       (-> (dissoc data :project-id :project-name)
                                    (teams/decode-row))

                     project    {:id (:project-id data)
                                 :name (:project-name data)}

                     members    (teams/get-team-members conn (:team-id project))
                     member-ids (into #{} (map :id) members)
                     perms      (assoc perms :in-team (contains? member-ids profile-id))]

                 {:permissions perms
                  :team (assoc team :permissions perms)
                  :project project
                  :members members})))))
