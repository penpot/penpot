;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS SUBSIDIARY SL

(ns backend-tests.rpc-commands-mcp-document-test
  (:require
   [app.binfile.common :as bfc]
   [app.common.json :as json]
   [app.common.uuid :as uuid]
   [app.config :as cf]
   [app.db :as db]
   [app.http :as http]
   [app.nitrate :as nitrate]
   [app.rpc.commands.access-token :as access-token]
   [app.rpc.commands.teams :as teams]
   [backend-tests.helpers :as th]
   [clojure.test :as t]
   [ring.core.protocols :as rcp]
   [yetti.response :as-alias yres]))

(t/use-fixtures :once (t/compose-fixtures
                       (partial th/init-config [:enable-access-tokens
                                                :enable-client-header-check-middleware])
                       th/init-system))
(t/use-fixtures :each th/database-reset)

(defn- create-identity
  []
  (let [profile (th/create-profile* (uuid/next) {:is-active true})
        token   (db/tx-run! th/*system* access-token/create-access-token
                            (:id profile) "MCP" nil "mcp")]
    {:profile profile :token (:token token)}))

(defn- request
  [token params]
  ((#'http/router-handler (::http/router th/*system*))
   (th/make-dummy-request
    {:path "/api/main/methods/get-mcp-document"
     :method :post
     :headers {"content-type" "application/json"
               "accept" "application/json"
               "x-client" "penpot-mcp"
               "authorization" (str "Token " token)}
     :body-bytes (json/encode params)})))

(defn- body
  [response]
  (let [output (java.io.ByteArrayOutputStream.)]
    (rcp/write-body-to-stream (::yres/body response) nil output)
    (json/decode (.toString output "UTF-8") :key-fn keyword)))

(t/deftest discovers-only-the-authenticated-profiles-teams
  (let [{:keys [profile token]} (create-identity)
        _other (create-identity)
        response (request token {:resource "teams"})]
    (t/is (= 200 (::yres/status response)))
    (t/is (= [(str (:default-team-id profile))]
             (mapv :id (:items (body response)))))))

(t/deftest traverses-projects-files-pages-and-shapes-by-id
  (let [{:keys [profile token]} (create-identity)
        file (th/create-file* (uuid/next)
                              {:profile-id (:id profile)
                               :project-id (:default-project-id profile)})
        projects (request token {:resource "projects" :id (:default-team-id profile)})
        files (request token {:resource "files" :id (:default-project-id profile)})
        pages (request token {:resource "pages" :id (:id file)})
        page-id (first (get-in file [:data :pages]))
        shapes (request token {:resource "shapes" :id (:id file) :page-id page-id})]
    (doseq [response [projects files pages shapes]]
      (t/is (= 200 (::yres/status response))))
    (t/is (= [(str (:default-project-id profile))] (mapv :id (:items (body projects)))))
    (t/is (= [(str (:id file))] (mapv :id (:items (body files)))))
    (t/is (= [(str page-id)] (mapv :id (:items (body pages)))))
    (t/is (= (str (:revn file) ":" (:vern file)) (:snapshot (body pages))))
    (t/is (= [(str uuid/zero)] (mapv :id (:items (body shapes)))))))

(t/deftest bounds-results-and-detects-changed-files
  (let [{:keys [profile token]} (create-identity)
        file (th/create-file* (uuid/next)
                              {:profile-id (:id profile)
                               :project-id (:default-project-id profile)})
        query {:resource "pages" :id (:id file) :limit 1}
        result (body (request token query))]
    (t/is (= 1 (count (:items result))))
    (t/is (contains? result :nextOffset))
    (t/is (nil? (:nextOffset result)))
    (t/is (= 200 (::yres/status (request token (assoc query :offset 1 :snapshot (:snapshot result))))))
    (t/is (= 400 (::yres/status (request token (assoc query :offset 1)))))
    (t/is (= 400 (::yres/status (request token (assoc query :limit 101)))))
    (db/update! th/*system* :file {:revn 1} {:id (:id file)})
    (t/is (= 409 (::yres/status (request token (assoc query :snapshot (:snapshot result))))))))

(t/deftest rejects-invalid-continuations-before-hydration
  (let [{:keys [profile token]} (create-identity)
        file (th/create-file* (uuid/next)
                              {:profile-id (:id profile)
                               :project-id (:default-project-id profile)})
        loads (atom 0)
        get-file bfc/get-file]
    (with-redefs [bfc/get-file (fn [& args]
                                 (swap! loads inc)
                                 (apply get-file args))]
      (doseq [resource ["pages" "shapes"]]
        (let [query (cond-> {:resource resource :id (:id file) :offset 1}
                      (= resource "shapes") (assoc :page-id (first (get-in file [:data :pages]))))]
          (t/is (= 400 (::yres/status (request token query))))
          (t/is (= 409 (::yres/status (request token (assoc query :snapshot "stale")))))))
      (t/is (zero? @loads)))))

(t/deftest rejects-foreign-missing-and-deleted-resources
  (let [{:keys [profile token]} (create-identity)
        other (create-identity)
        file (th/create-file* (uuid/next)
                              {:profile-id (:id profile)
                               :project-id (:default-project-id profile)})]
    (doseq [query [{:resource "projects" :id (:default-team-id profile)}
                   {:resource "files" :id (:default-project-id profile)}
                   {:resource "pages" :id (:id file)}
                   {:resource "shapes" :id (:id file) :page-id (uuid/next)}]]
      (t/is (= 404 (::yres/status (request (:token other) query)))))
    (t/is (= 404 (::yres/status (request token {:resource "shapes" :id (:id file) :page-id (uuid/next)}))))
    (db/update! th/*system* :project {:deleted-at (java.time.Instant/now)} {:id (:default-project-id profile)})
    (t/is (= 404 (::yres/status (request token {:resource "pages" :id (:id file)}))))))

(t/deftest rejects-invalid-credentials-and-unavailable-profiles
  (let [{:keys [profile token]} (create-identity)]
    (doseq [credential [nil "" "invalid"]]
      (t/is (= 401 (::yres/status (request credential {:resource "teams"})))))
    (db/update! th/*system* :profile {:is-blocked true} {:id (:id profile)})
    (t/is (= 403 (::yres/status (request token {:resource "teams"}))))
    (db/update! th/*system* :profile {:is-blocked false :is-active false} {:id (:id profile)})
    (t/is (= 403 (::yres/status (request token {:resource "teams"}))))))

(t/deftest reads-one-database-snapshot-per-request
  (let [{:keys [profile token]} (create-identity)
        team-id (:default-team-id profile)
        original (teams/get-teams th/*system* (:id profile))
        get-teams teams/get-teams]
    ;; Commit another transaction after the request has read its profile.
    (with-redefs [teams/get-teams (fn [cfg profile-id]
                                    (db/update! th/*system* :team {:name "Concurrent edit"} {:id team-id})
                                    (get-teams cfg profile-id))]
      (t/is (= (:name (first original))
               (-> (request token {:resource "teams"}) body :items first :name))))))

(t/deftest rejects-personal-and-revoked-tokens
  (let [{:keys [profile token]} (create-identity)
        personal (db/tx-run! th/*system* access-token/create-access-token
                             (:id profile) "Personal" nil "personal")]
    (t/is (= 403 (::yres/status (request (:token personal) {:resource "teams"}))))
    (db/tx-run! th/*system* access-token/create-access-token
                (:id profile) "Replacement" nil "mcp")
    (t/is (= 401 (::yres/status (request token {:resource "teams"}))))))

(t/deftest paginates-discovery-without-dropping-items
  (let [{:keys [profile token]} (create-identity)
        params {:profile-id (:id profile) :project-id (:default-project-id profile)}
        files [(th/create-file* 1 params) (th/create-file* 2 params)]
        query {:resource "files" :id (:default-project-id profile) :limit 1}
        first-result (body (request token query))
        last-result (body (request token (assoc query :offset (:nextOffset first-result))))]
    (t/is (= 1 (:nextOffset first-result)))
    (t/is (nil? (:nextOffset last-result)))
    (t/is (= (set (map (comp str :id) files))
             (set (map :id (concat (:items first-result) (:items last-result))))))))

(t/deftest discovery-timestamp-ties-have-stable-id-order
  (let [{:keys [profile token]} (create-identity)
        profile-id (:id profile)
        team-id (:default-team-id profile)
        project-id (:default-project-id profile)
        timestamp (java.time.Instant/parse "2026-01-01T00:00:00Z")]
    (doseq [i [3 2 1]]
      (let [id (uuid/coerce (str "00000000-0000-0000-0000-00000000000" i))]
        (th/create-team* i {:id id :profile-id profile-id})
        (th/create-project* i {:id id :profile-id profile-id :team-id team-id})
        (th/create-file* i {:id id :profile-id profile-id :project-id project-id})))
    (doseq [table ["team" "team_profile_rel" "project" "file"]]
      (db/exec! th/*system* [(str "update " table
                                  " set created_at = ?, modified_at = ?") timestamp timestamp]))
    (doseq [query [{:resource "teams"}
                   {:resource "projects" :id team-id}
                   {:resource "files" :id project-id}]]
      (let [all (mapv :id (:items (body (request token query))))
            walked (loop [offset 0 result []]
                     (let [response (request token (assoc query :offset offset :limit 1))
                           page (body response)
                           result (into result (map :id (:items page)))]
                       (t/is (= 200 (::yres/status response)))
                       (if-let [next-offset (:nextOffset page)]
                         (recur next-offset result)
                         result)))]
        (t/is (= (sort all) walked) (str query))
        (t/is (= (count all) (count (set walked))))))))

(t/deftest reads-persisted-shape-structure
  (let [{:keys [profile token]} (create-identity)
        file     (th/create-file* (uuid/next)
                                  {:profile-id (:id profile)
                                   :project-id (:default-project-id profile)})
        page-id  (first (get-in file [:data :pages]))
        shape-id (uuid/next)]
    (th/update-file* {:file-id (:id file) :profile-id (:id profile)
                      :changes [(th/add-rect-change page-id shape-id)]})
    (let [result (body (request token {:resource "shapes" :id (:id file) :page-id page-id}))
          shape  (first (filter #(= (str shape-id) (:id %)) (:items result)))]
      (t/is (= "1:0" (:snapshot result)))
      (t/is (= {:id (str shape-id) :name "rect" :type "rect"
                :parentId (str uuid/zero) :frameId (str uuid/zero)
                :x 0 :y 0 :width 10 :height 10}
               shape)))))

(t/deftest enforces-organization-sso-for-every-resource
  (let [{:keys [profile token]} (create-identity)
        team-id (:default-team-id profile)
        organization-id (uuid/next)
        file (th/create-file* (uuid/next)
                              {:profile-id (:id profile)
                               :project-id (:default-project-id profile)})
        queries [{:resource "teams"}
                 {:resource "projects" :id team-id}
                 {:resource "files" :id (:default-project-id profile)}
                 {:resource "pages" :id (:id file)}
                 {:resource "shapes" :id (:id file) :page-id (first (get-in file [:data :pages]))}]]
    ;; Stub only the external service, retaining real session authorization,
    ;; token authentication, database permissions, and HTTP error handling.
    (binding [cf/flags (conj cf/flags :admin-console)]
      (doseq [active [false true]]
        (let [checked (atom [])]
          (with-redefs [nitrate/call
                        (fn [_ method params]
                          (when (= method :get-organization-sso-by-team)
                            (swap! checked conj (:team-id params))
                            {:active active :issuer "https://idp.example.com"
                             :organization-id organization-id}))]
            (doseq [query queries]
              (let [response (request token query)]
                (t/is (= (if active 401 200) (::yres/status response)) (str query))
                (when active
                  (t/is (= "nitrate-sso-required" (:code (body response)))))))
            (t/is (= (repeat (count queries) team-id) @checked))))))))

(t/deftest rejects-invalid-query-shapes
  (let [{:keys [token]} (create-identity)]
    (doseq [query [{} {:resource "execute"} {:resource "projects"}
                   {:resource "teams" :id (uuid/next)}
                   {:resource "pages" :id "invalid"}
                   {:resource "teams" :code "return secret"}]]
      (t/is (= 400 (::yres/status (request token query)))))))
