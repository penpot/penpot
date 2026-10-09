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

(defn- create-project
  "Create a profile, a team and a project with a distinct name.
  Returns the ids plus the profile."
  [i]
  (let [profile (th/create-profile* i)
        team    (th/create-team* i {:profile-id (:id profile)})
        project (th/create-project* i {:profile-id (:id profile)
                                       :team-id (:id team)
                                       :name (str "searchable-project-" i)})]
    {:profile profile :team team :project project}))

(defn- mark-deleted
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
  (let [{:keys [profile project]} (create-project 1)
        out (call #{} :token (:id profile) #{"superuser"}
                  "get-projects" {:id (:id project)})]
    (t/is (= 1 (count (:items out))))))

(t/deftest anonymous-rejected
  (t/is (= :authentication-required
           (caught-code #(call #{} nil nil #{}
                               "get-projects" {})))))

;; ----------------------------------------------------------------
;; Listing
;; ----------------------------------------------------------------

(t/deftest list-returns-projects-with-team-and-counts
  (let [{:keys [profile team project]} (create-project 1)
        file  (th/create-file* 1 {:profile-id (:id profile)
                                  :project-id (:id project)})
        _     (:id file)
        run   (as-superuser profile)
        out   (run "get-projects" {:id (:id project)})
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
  (let [{:keys [profile project]} (create-project 1)
        _     (create-project 2)
        run   (as-superuser profile)
        out   (run "get-projects" {:id (:id project)})]
    (t/is (= [(:id project)] (mapv :id (:items out))))))

(t/deftest list-finds-by-string-id
  (let [{:keys [profile project]} (create-project 1)
        run   (as-superuser profile)
        out   (run "get-projects" {:id (str (:id project))})]
    (t/is (= [(:id project)] (mapv :id (:items out)))
          "the browser sends ids as strings")))

(t/deftest list-returns-newest-first-without-gaps
  (let [{:keys [profile]} (create-project 1)
        _     (create-project 2)
        _     (create-project 3)
        run   (as-superuser profile)
        out   (run "get-projects" {:limit 50})
        items (:items out)
        keys  (mapv (juxt :created-at :id) items)]
    (t/is (= keys (sort #(compare %2 %1) keys))
          "pages follow created_at DESC, id DESC")
    (t/is (= (count keys) (count (distinct (map :id items))))
          "no duplicates across the order")))

(t/deftest list-filters-by-deleted
  (let [{:keys [profile project]} (create-project 1)
        _     (mark-deleted :project (:id project))
        run   (as-superuser profile)]
    (let [out (run "get-projects" {:deleted true})]
      (t/is (= [(:id project)] (mapv :id (:items out)))))
    (let [out (run "get-projects" {:deleted false})
          ids (set (map :id (:items out)))]
      (t/is (contains? ids (:default-project-id profile))
            "the live default project lists")
      (t/is (not (contains? ids (:id project)))
            "the deleted project is excluded"))
    (let [out (run "get-projects" {})
          ids (set (map :id (:items out)))]
      (t/is (contains? ids (:id project))
            "without the flag, deleted rows still list"))))

(t/deftest list-filters-by-team
  (let [{:keys [profile team project]} (create-project 1)
        other (create-project 2)
        run   (as-superuser profile)
        out   (run "get-projects" {:team-id (:id team)})
        ids   (set (map :id (:items out)))]
    (t/is (contains? ids (:id project)))
    (t/is (not (contains? ids (:id (:project other)))))))

(t/deftest list-shows-deleted-projects
  (let [{:keys [profile project]} (create-project 1)
        _     (mark-deleted :project (:id project))
        run   (as-superuser profile)
        out   (run "get-projects" {:id (:id project)})
        item  (first (:items out))]
    (t/is (= 1 (count (:items out))))
    (t/is (= (:id project) (:id item)))
    (t/is (some? (:deleted-at item)))))

(t/deftest list-hides-projects-of-deleted-teams
  (let [{:keys [profile team project]} (create-project 1)
        _     (mark-deleted :team (:id team))
        run   (as-superuser profile)
        out   (run "get-projects" {:id (:id project)})]
    (t/is (= [] (:items out)))))

(t/deftest list-counts-only-live-files
  (let [{:keys [profile project]} (create-project 1)
        file  (th/create-file* 1 {:profile-id (:id profile)
                                  :project-id (:id project)})
        run   (as-superuser profile)
        before (run "get-projects" {:id (:id project)})]
    (t/is (= 1 (:total-files (first (:items before)))))
    (th/mark-file-deleted* {:id (:id file)})
    (let [after (run "get-projects" {:id (:id project)})]
      (t/is (= 0 (:total-files (first (:items after))))))))

(t/deftest list-paginates
  (let [{:keys [profile]} (create-project 1)
        _     (create-project 2)
        run   (as-superuser profile)
        page1 (run "get-projects" {:limit 1})]
    (t/is (= 1 (count (:items page1))))
    (t/is (some? (:next-since page1)))
    (t/is (some? (:next-id page1)))
    (let [page2 (run "get-projects" {:limit 1
                                     :since (:next-since page1)
                                     :since-id (:next-id page1)})]
      (t/is (= 1 (count (:items page2))))
      (t/is (not= (-> page1 :items first :id)
                  (-> page2 :items first :id))))))

;; ----------------------------------------------------------------
;; Detail
;; ----------------------------------------------------------------

(t/deftest detail-unlisted-session-rejected
  (let [{:keys [profile project]} (create-project 1)
        run   (as-session #{} profile)]
    (t/is (= :superuser-required
             (caught-code #(run "get-project" {:id (:id project)}))))))

(t/deftest detail-resolves-regardless-of-deleted-state
  (let [{:keys [profile team project]} (create-project 1)
        run   (as-superuser profile)]
    (let [out (run "get-project" {:id (:id project)})]
      (t/is (= (:id project) (:id out)))
      (t/is (= (:name project) (:name out)))
      (t/is (= (:id team) (:team-id out))))
    ;; A stray live project under a deleted team still resolves:
    ;; the admin must reach objects the list hides.
    (mark-deleted :team (:id team))
    (let [out (run "get-project" {:id (:id project)})]
      (t/is (= (:id project) (:id out))))
    (mark-deleted :project (:id project))
    (let [out (run "get-project" {:id (:id project)})]
      (t/is (some? (:deleted-at out))))))

(t/deftest detail-unknown-id-gives-not-found
  (let [{:keys [profile]} (create-project 1)
        run   (as-superuser profile)]
    (t/is (= :project-not-found
             (caught-code #(run "get-project" {:id (uuid/next)}))))))
