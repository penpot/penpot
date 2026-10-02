;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS SUBSIDIARY SL

(ns backend-tests.rpc-admin-project-test
  "Tests for the superuser-guarded project listing command."
  (:require
   [app.auth :as-alias auth]
   [app.common.data :as d]
   [app.common.time :as ct]
   [app.common.uuid :as uuid]
   [app.db :as db]
   [app.http :as-alias http]
   [app.rpc :as rpc]
   [app.util.services :as sv]
   [backend-tests.helpers :as th]
   [clojure.test :as t]))

(t/use-fixtures :once th/state-init)
(t/use-fixtures :each th/database-reset)

;; ----------------------------------------------------------------
;; Helpers
;; ----------------------------------------------------------------

(defn- raw-method
  "The raw [f mdata] behind `cmd-name` in the admin namespace."
  [cmd-name]
  (some (fn [[f mdata]]
          (when (= cmd-name (::sv/name mdata))
            [f mdata]))
        (sv/scan-ns 'app.rpc.admin.project)))

(defn- call
  "Invoke an admin command through the real wrapper chain with a
  hand-built config.

  The chain closes over its cfg at startup, so the superuser set is
  injected per call here instead of going through `th/command!`.
  Qualified keys stay in `params` as server context; the rest goes
  into the request, where params validation reads it from."
  [superusers auth-type profile-id token-perms cmd params]
  (let [[f mdata]   (raw-method cmd)
        cfg         (assoc th/*system* ::auth/superusers superusers)
        request     (assoc (th/make-dummy-request)
                           :params (d/without-qualified params))
        server      (with-meta params {::rpc/request-at (ct/now)
                                       ::http/request request})
        wrapped     (#'rpc/wrap cfg f mdata)]
    (wrapped cfg (assoc server
                        ::rpc/profile-id profile-id
                        ::rpc/auth-type auth-type
                        ::rpc/token-perms token-perms))))

(defn- as-session
  [superusers profile]
  (fn [cmd params]
    (call superusers :session (:id profile) #{} cmd params)))

(defn- as-superuser
  [profile]
  (as-session #{(:id profile)} profile))

(defn- caught-code
  "Run `thunk` and return the error code it raised, or `::no-throw`."
  [thunk]
  (try
    (thunk)
    ::no-throw
    (catch clojure.lang.ExceptionInfo cause
      (th/ex-code cause))))

(defn- create-project!
  "Create a profile, a team and a project with a searchable name.
  Returns the ids plus the profile."
  [i]
  (let [profile (th/create-profile* i)
        team    (th/create-team* i {:profile-id (:id profile)})
        project (th/create-project* i {:profile-id (:id profile)
                                       :team-id (:id team)
                                       :name (str "searchable-project-" i)})]
    {:profile profile :team team :project project}))

(defn- mark-deleted!
  [table id]
  (db/update! th/*system* table {:deleted-at (ct/now)} {:id id}))

;; ----------------------------------------------------------------
;; Guard
;; ----------------------------------------------------------------

(t/deftest unlisted-session-rejected
  (let [profile (th/create-profile* 1)
        run     (as-session #{} profile)]
    (t/is (= :superuser-required
             (caught-code #(run "get-projects" {}))))))

(t/deftest unlisted-token-without-scope-rejected
  (let [profile (th/create-profile* 1)]
    (t/is (= :superuser-required
             (caught-code #(call #{} :token (:id profile) #{}
                                 "get-projects" {}))))))

(t/deftest token-with-granted-superuser-passes
  (let [{:keys [profile]} (create-project! 1)
        out (call #{} :token (:id profile) #{"superuser"}
                  "get-projects" {:search "searchable-project-1"})]
    (t/is (= 1 (count (:items out))))))

(t/deftest anonymous-rejected
  (t/is (= :authentication-required
           (caught-code #(call #{} nil nil #{}
                               "get-projects" {})))))

;; ----------------------------------------------------------------
;; Listing
;; ----------------------------------------------------------------

(t/deftest list-returns-projects-with-team-and-counts
  (let [{:keys [profile team project]} (create-project! 1)
        file  (th/create-file* 1 {:profile-id (:id profile)
                                  :project-id (:id project)})
        _     (:id file)
        run   (as-superuser profile)
        out   (run "get-projects" {:search (:name project)})
        item  (first (:items out))]
    (t/is (= 1 (count (:items out))))
    (t/is (= (:id project) (:id item)))
    (t/is (= (:name project) (:name item)))
    (t/is (= (:id team) (:team-id item)))
    (t/is (string? (:team-name item)))
    (t/is (boolean? (:is-default item)))
    (t/is (= 1 (:total-files item)))
    (t/is (some? (:created-at item)))
    (t/is (some? (:modified-at item)))))

(t/deftest list-finds-by-exact-id
  (let [{:keys [profile project]} (create-project! 1)
        _     (create-project! 2)
        run   (as-superuser profile)
        out   (run "get-projects" {:search (str (:id project))})]
    (t/is (= [(:id project)] (mapv :id (:items out))))))

(t/deftest list-filters-by-team
  (let [{:keys [profile team project]} (create-project! 1)
        other (create-project! 2)
        run   (as-superuser profile)
        out   (run "get-projects" {:team-id (:id team)})
        ids   (set (map :id (:items out)))]
    (t/is (contains? ids (:id project)))
    (t/is (not (contains? ids (:id (:project other)))))))

(t/deftest list-hides-deleted-projects
  (let [{:keys [profile project]} (create-project! 1)
        _     (mark-deleted! :project (:id project))
        run   (as-superuser profile)
        out   (run "get-projects" {:search (:name project)})]
    (t/is (= [] (:items out)))))

(t/deftest list-hides-projects-of-deleted-teams
  (let [{:keys [profile team project]} (create-project! 1)
        _     (mark-deleted! :team (:id team))
        run   (as-superuser profile)
        out   (run "get-projects" {:search (:name project)})]
    (t/is (= [] (:items out)))))

(t/deftest list-counts-only-live-files
  (let [{:keys [profile project]} (create-project! 1)
        file  (th/create-file* 1 {:profile-id (:id profile)
                                  :project-id (:id project)})
        run   (as-superuser profile)
        before (run "get-projects" {:search (:name project)})]
    (t/is (= 1 (:total-files (first (:items before)))))
    (th/mark-file-deleted* {:id (:id file)})
    (let [after (run "get-projects" {:search (:name project)})]
      (t/is (= 0 (:total-files (first (:items after))))))))

(t/deftest list-search-and-pagination
  (let [{:keys [profile]} (create-project! 1)
        _     (create-project! 2)
        run   (as-superuser profile)
        page1 (run "get-projects" {:search "searchable-project" :limit 1})]
    (t/is (= 1 (count (:items page1))))
    (t/is (some? (:next-since page1)))
    (t/is (some? (:next-id page1)))
    (let [page2 (run "get-projects" {:search "searchable-project" :limit 1
                                     :since (:next-since page1)
                                     :since-id (:next-id page1)})]
      (t/is (= 1 (count (:items page2))))
      (t/is (not= (-> page1 :items first :id)
                  (-> page2 :items first :id))))))
