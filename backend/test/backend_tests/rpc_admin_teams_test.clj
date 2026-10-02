;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS SUBSIDIARY SL

(ns backend-tests.rpc-admin-teams-test
  "Tests for the superuser-guarded team management commands."
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
        (sv/scan-ns 'app.rpc.commands.admin)))

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

;; ----------------------------------------------------------------
;; Guard
;; ----------------------------------------------------------------

(t/deftest unlisted-session-rejected
  (let [profile (th/create-profile* 1)
        run     (as-session #{} profile)]
    (t/is (= :superuser-required
             (caught-code #(run "get-admin-teams" {}))))
    (t/is (= :superuser-required
             (caught-code #(run "get-admin-team" {:id (uuid/next)}))))
    (t/is (= :superuser-required
             (caught-code #(run "get-admin-team-members" {:team-id (uuid/next)}))))
    (t/is (= :superuser-required
             (caught-code #(run "enable-admin-team-feature"
                                {:team-id (uuid/next) :feature "components/v2"}))))
    (t/is (= :superuser-required
             (caught-code #(run "disable-admin-team-feature"
                                {:team-id (uuid/next) :feature "components/v2"}))))))

(t/deftest unlisted-token-without-scope-rejected
  (let [profile (th/create-profile* 1)]
    (t/is (= :superuser-required
             (caught-code #(call #{} :token (:id profile) #{}
                                 "get-admin-teams" {}))))))

(t/deftest token-with-granted-superuser-passes
  (let [admin (th/create-profile* 1)
        team  (th/create-team* 1 {:profile-id (:id admin)})
        out   (call #{} :token (:id admin) #{"superuser"}
                    "get-admin-teams" {:search (:name team)})]
    (t/is (= [(:id team)] (mapv :id (:items out))))))

(t/deftest anonymous-rejected
  (t/is (= :authentication-required
           (caught-code #(call #{} nil nil #{}
                               "get-admin-teams" {})))))

(t/deftest invalid-params-still-validated-for-listed-caller
  (let [profile (th/create-profile* 1)
        run     (as-superuser profile)]
    (t/is (= :params-validation
             (caught-code #(run "get-admin-teams" {:limit 500}))))))

;; ----------------------------------------------------------------
;; Listing
;; ----------------------------------------------------------------

(t/deftest list-returns-teams-with-member-counts
  (let [admin (th/create-profile* 1)
        team  (th/create-team* 1 {:profile-id (:id admin)})
        run   (as-superuser admin)
        out   (run "get-admin-teams" {:search (:name team)})
        item  (first (:items out))]
    (t/is (= 1 (count (:items out))))
    (t/is (= (:id team) (:id item)))
    (t/is (= (:name team) (:name item)))
    (t/is (= 1 (:total-members item)))
    (t/is (= (:email admin) (:owner item)))
    (t/is (false? (:is-default item)))
    (t/is (not (contains? item :features)))))

(t/deftest list-hides-deleted-teams
  (let [admin (th/create-profile* 1)
        team  (th/create-team* 1 {:profile-id (:id admin)})
        _     (db/update! th/*system* :team {:deleted-at (ct/now)} {:id (:id team)})
        run   (as-superuser admin)
        out   (run "get-admin-teams" {:search (:name team)})]
    (t/is (= [] (:items out)))))

(t/deftest list-search-and-pagination
  (let [admin (th/create-profile* 1)
        _t1   (th/create-team* 1 {:profile-id (:id admin)})
        _t2   (th/create-team* 2 {:profile-id (:id admin)})
        run   (as-superuser admin)
        page1 (run "get-admin-teams" {:search "team" :limit 1})]
    (t/is (= 1 (count (:items page1))))
    (t/is (some? (:next-since page1)))
    (t/is (some? (:next-id page1)))
    (let [page2 (run "get-admin-teams" {:search "team" :limit 1
                                        :since (:next-since page1)
                                        :since-id (:next-id page1)})]
      (t/is (= 1 (count (:items page2))))
      (t/is (not= (-> page1 :items first :id)
                  (-> page2 :items first :id))))))

;; ----------------------------------------------------------------
;; Detail
;; ----------------------------------------------------------------

(t/deftest detail-returns-team-with-features-and-counts
  (let [admin (th/create-profile* 1)
        team  (th/create-team* 1 {:profile-id (:id admin)})
        run   (as-superuser admin)
        out   (run "get-admin-team" {:id (:id team)})]
    (t/is (= (:id team) (:id out)))
    (t/is (= (:name team) (:name out)))
    (t/is (= 1 (:total-members out)))
    (t/is (= 1 (:total-projects out)))
    (t/is (vector? (:features out)))
    (t/is (vector? (:effective-features out)))
    (t/is (<= (count (:features out))
              (count (:effective-features out))))))

(t/deftest detail-unknown-id-gives-not-found
  (let [profile (th/create-profile* 1)
        run     (as-superuser profile)]
    (t/is (= :team-not-found
             (caught-code #(run "get-admin-team" {:id (uuid/next)}))))))

(t/deftest detail-deleted-gives-not-found
  (let [admin (th/create-profile* 1)
        team  (th/create-team* 1 {:profile-id (:id admin)})
        _     (db/update! th/*system* :team {:deleted-at (ct/now)} {:id (:id team)})
        run   (as-superuser admin)]
    (t/is (= :team-not-found
             (caught-code #(run "get-admin-team" {:id (:id team)}))))))

;; ----------------------------------------------------------------
;; Members
;; ----------------------------------------------------------------

(t/deftest members-returns-roles
  (let [admin  (th/create-profile* 1)
        editor (th/create-profile* 2)
        team   (th/create-team* 1 {:profile-id (:id admin)})
        _      (th/create-team-role* {:team-id (:id team)
                                      :profile-id (:id editor)
                                      :role :editor})
        run    (as-superuser admin)
        out    (run "get-admin-team-members" {:team-id (:id team)})
        by-id  (into {} (map (juxt :id identity) out))]
    (t/is (= 2 (count out)))
    (t/is (true? (:is-owner (get by-id (:id admin)))))
    (t/is (false? (:is-owner (get by-id (:id editor)))))
    (t/is (= (:email editor) (:email (get by-id (:id editor)))))))

(t/deftest members-unknown-team-gives-not-found
  (let [profile (th/create-profile* 1)
        run     (as-superuser profile)]
    (t/is (= :team-not-found
             (caught-code #(run "get-admin-team-members"
                                {:team-id (uuid/next)}))))))

;; ----------------------------------------------------------------
;; Feature toggles
;; ----------------------------------------------------------------

(t/deftest enable-adds-feature-and-is-idempotent
  (let [admin (th/create-profile* 1)
        team  (th/create-team* 1 {:profile-id (:id admin)})
        run   (as-superuser admin)
        out   (run "enable-admin-team-feature"
                   {:team-id (:id team) :feature "text-editor/v2"})
        again (run "enable-admin-team-feature"
                   {:team-id (:id team) :feature "text-editor/v2"})]
    (t/is (= (:id team) (:id out)))
    (t/is (some #{"text-editor/v2"} (:features out)))
    (t/is (= (:features out) (:features again)))))

(t/deftest disable-removes-feature-and-is-idempotent
  (let [admin (th/create-profile* 1)
        team  (th/create-team* 1 {:profile-id (:id admin)})
        run   (as-superuser admin)
        _     (run "enable-admin-team-feature"
                   {:team-id (:id team) :feature "text-editor/v2"})
        out   (run "disable-admin-team-feature"
                   {:team-id (:id team) :feature "text-editor/v2"})
        again (run "disable-admin-team-feature"
                   {:team-id (:id team) :feature "text-editor/v2"})]
    (t/is (not (some #{"text-editor/v2"} (:features out))))
    (t/is (= (:features out) (:features again)))))

(t/deftest unsupported-feature-rejected-without-changes
  (let [admin  (th/create-profile* 1)
        team   (th/create-team* 1 {:profile-id (:id admin)})
        run    (as-superuser admin)
        before (run "get-admin-team" {:id (:id team)})]
    (t/is (= :feature-not-supported
             (caught-code #(run "enable-admin-team-feature"
                                {:team-id (:id team) :feature "nope/missing"}))))
    (t/is (= :feature-not-supported
             (caught-code #(run "disable-admin-team-feature"
                                {:team-id (:id team) :feature "nope/missing"}))))
    (let [after (run "get-admin-team" {:id (:id team)})]
      (t/is (= (:features before) (:features after))))))

(t/deftest toggles-on-unknown-team-give-not-found
  (let [profile (th/create-profile* 1)
        run     (as-superuser profile)
        missing (uuid/next)]
    (t/is (= :team-not-found
             (caught-code #(run "enable-admin-team-feature"
                                {:team-id missing :feature "text-editor/v2"}))))
    (t/is (= :team-not-found
             (caught-code #(run "disable-admin-team-feature"
                                {:team-id missing :feature "text-editor/v2"}))))))

(t/deftest writes-refuse-deleted-team
  (let [admin (th/create-profile* 1)
        team  (th/create-team* 1 {:profile-id (:id admin)})
        _     (db/update! th/*system* :team {:deleted-at (ct/now)} {:id (:id team)})
        run   (as-superuser admin)]
    (t/is (= :team-not-found
             (caught-code #(run "get-admin-team-members"
                                {:team-id (:id team)}))))
    (t/is (= :team-not-found
             (caught-code #(run "enable-admin-team-feature"
                                {:team-id (:id team) :feature "text-editor/v2"}))))
    (t/is (= :team-not-found
             (caught-code #(run "disable-admin-team-feature"
                                {:team-id (:id team) :feature "text-editor/v2"}))))))
