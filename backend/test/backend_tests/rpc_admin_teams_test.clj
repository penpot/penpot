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
        (sv/scan-ns 'app.rpc.admin.team)))

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
             (caught-code #(run "get-teams" {}))))
    (t/is (= :superuser-required
             (caught-code #(run "get-team" {:id (uuid/next)}))))
    (t/is (= :superuser-required
             (caught-code #(run "get-team-members" {:team-id (uuid/next)}))))
    (t/is (= :superuser-required
             (caught-code #(run "enable-team-feature"
                                {:team-id (uuid/next) :feature "components/v2"}))))
    (t/is (= :superuser-required
             (caught-code #(run "disable-team-feature"
                                {:team-id (uuid/next) :feature "components/v2"}))))))

(t/deftest unlisted-token-without-scope-rejected
  (let [profile (th/create-profile* 1)]
    (t/is (= :superuser-required
             (caught-code #(call #{} :token (:id profile) #{}
                                 "get-teams" {}))))))

(t/deftest token-with-granted-superuser-passes
  (let [admin (th/create-profile* 1)
        team  (th/create-team* 1 {:profile-id (:id admin)})
        out   (call #{} :token (:id admin) #{"superuser"}
                    "get-teams" {:id (:id team)})]
    (t/is (= [(:id team)] (mapv :id (:items out))))))

(t/deftest anonymous-rejected
  (t/is (= :authentication-required
           (caught-code #(call #{} nil nil #{}
                               "get-teams" {})))))

(t/deftest invalid-params-still-validated-for-listed-caller
  (let [profile (th/create-profile* 1)
        run     (as-superuser profile)]
    (t/is (= :params-validation
             (caught-code #(run "get-teams" {:limit 500}))))))

;; ----------------------------------------------------------------
;; Listing
;; ----------------------------------------------------------------

(t/deftest list-returns-teams-with-member-counts
  (let [admin (th/create-profile* 1)
        team  (th/create-team* 1 {:profile-id (:id admin)})
        run   (as-superuser admin)
        out   (run "get-teams" {:id (:id team)})
        item  (first (:items out))]
    (t/is (= 1 (count (:items out))))
    (t/is (= (:id team) (:id item)))
    (t/is (= (:name team) (:name item)))
    (t/is (= 1 (:total-members item)))
    (t/is (= (:email admin) (:owner item)))
    (t/is (some? (:created-at item)))
    (t/is (some? (:modified-at item)))
    (t/is (false? (:is-default item)))
    (t/is (not (contains? item :features)))))

(t/deftest list-shows-deleted-teams
  (let [admin (th/create-profile* 1)
        team  (th/create-team* 1 {:profile-id (:id admin)})
        _     (db/update! th/*system* :team {:deleted-at (ct/now)} {:id (:id team)})
        run   (as-superuser admin)
        out   (run "get-teams" {:id (:id team)})
        item  (first (:items out))]
    (t/is (= 1 (count (:items out))))
    (t/is (= (:id team) (:id item)))
    (t/is (some? (:deleted-at item)))))

(t/deftest list-filters-by-deleted
  (let [admin (th/create-profile* 1)
        team  (th/create-team* 1 {:profile-id (:id admin)})
        _     (db/update! th/*system* :team {:deleted-at (ct/now)} {:id (:id team)})
        run   (as-superuser admin)]
    (let [out (run "get-teams" {:deleted true})]
      (t/is (= [(:id team)] (mapv :id (:items out)))))
    (let [out (run "get-teams" {:deleted false})]
      (t/is (= [(:default-team-id admin)] (mapv :id (:items out)))
            "only the live default team lists"))
    (let [out (run "get-teams" {})]
      (t/is (= 2 (count (:items out)))
            "without the flag, deleted rows still list"))))

(t/deftest list-paginates
  (let [admin (th/create-profile* 1)
        _t1   (th/create-team* 1 {:profile-id (:id admin)})
        _t2   (th/create-team* 2 {:profile-id (:id admin)})
        run   (as-superuser admin)
        page1 (run "get-teams" {:limit 1})]
    (t/is (= 1 (count (:items page1))))
    (t/is (some? (:next-since page1)))
    (t/is (some? (:next-id page1)))
    (let [page2 (run "get-teams" {:limit 1
                                  :since (:next-since page1)
                                  :since-id (:next-id page1)})]
      (t/is (= 1 (count (:items page2))))
      (t/is (not= (-> page1 :items first :id)
                  (-> page2 :items first :id))))))

(t/deftest list-finds-by-exact-id
  (let [admin (th/create-profile* 1)
        team  (th/create-team* 1 {:profile-id (:id admin)})
        _     (th/create-team* 2 {:profile-id (:id admin)})
        run   (as-superuser admin)
        out   (run "get-teams" {:id (:id team)})]
    (t/is (= [(:id team)] (mapv :id (:items out))))))

(t/deftest list-finds-by-string-id
  (let [admin (th/create-profile* 1)
        team  (th/create-team* 1 {:profile-id (:id admin)})
        run   (as-superuser admin)
        out   (run "get-teams" {:id (str (:id team))})]
    (t/is (= [(:id team)] (mapv :id (:items out)))
          "the browser sends ids as strings")))

;; ----------------------------------------------------------------
;; Detail
;; ----------------------------------------------------------------

(t/deftest detail-returns-team-with-features-and-counts
  (let [admin (th/create-profile* 1)
        team  (th/create-team* 1 {:profile-id (:id admin)})
        run   (as-superuser admin)
        out   (run "get-team" {:id (:id team)})]
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
             (caught-code #(run "get-team" {:id (uuid/next)}))))))

(t/deftest detail-deleted-returns-with-stamp
  (let [admin (th/create-profile* 1)
        team  (th/create-team* 1 {:profile-id (:id admin)})
        _     (db/update! th/*system* :team {:deleted-at (ct/now)} {:id (:id team)})
        run   (as-superuser admin)
        out   (run "get-team" {:id (:id team)})]
    (t/is (= (:id team) (:id out)))
    (t/is (some? (:deleted-at out)))))

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
        out    (run "get-team-members" {:team-id (:id team)})
        by-id  (into {} (map (juxt :id identity) out))]
    (t/is (= 2 (count out)))
    (t/is (true? (:is-owner (get by-id (:id admin)))))
    (t/is (false? (:is-owner (get by-id (:id editor)))))
    (t/is (= (:email editor) (:email (get by-id (:id editor)))))))

(t/deftest members-unknown-team-gives-not-found
  (let [profile (th/create-profile* 1)
        run     (as-superuser profile)]
    (t/is (= :team-not-found
             (caught-code #(run "get-team-members"
                                {:team-id (uuid/next)}))))))

(t/deftest members-return-profile-status
  ;; A deleted member stays listed (the rel row survives) but
  ;; carries its profile status, so the panel can badge it.
  (let [admin  (th/create-profile* 1)
        editor (th/create-profile* 2)
        team   (th/create-team* 1 {:profile-id (:id admin)})
        _      (th/create-team-role* {:team-id (:id team)
                                      :profile-id (:id editor)
                                      :role :editor})
        _      (db/update! th/*system* :profile
                           {:deleted-at (ct/now)}
                           {:id (:id editor)})
        run    (as-superuser admin)
        out    (run "get-team-members" {:team-id (:id team)})
        by-id  (into {} (map (juxt :id identity) out))]
    (t/is (= 2 (count out)))
    (t/is (nil? (:deleted-at (get by-id (:id admin)))))
    (t/is (some? (:deleted-at (get by-id (:id editor)))))
    (t/is (false? (:is-blocked (get by-id (:id editor)))))))

;; ----------------------------------------------------------------
;; Feature toggles
;; ----------------------------------------------------------------

(t/deftest enable-adds-feature-and-is-idempotent
  (let [admin (th/create-profile* 1)
        team  (th/create-team* 1 {:profile-id (:id admin)})
        run   (as-superuser admin)
        out   (run "enable-team-feature"
                   {:team-id (:id team) :feature "text-editor/v2"})
        again (run "enable-team-feature"
                   {:team-id (:id team) :feature "text-editor/v2"})]
    (t/is (= (:id team) (:id out)))
    (t/is (some #{"text-editor/v2"} (:features out)))
    (t/is (= (:features out) (:features again)))))

(t/deftest disable-removes-feature-and-is-idempotent
  (let [admin (th/create-profile* 1)
        team  (th/create-team* 1 {:profile-id (:id admin)})
        run   (as-superuser admin)
        _     (run "enable-team-feature"
                   {:team-id (:id team) :feature "text-editor/v2"})
        out   (run "disable-team-feature"
                   {:team-id (:id team) :feature "text-editor/v2"})
        again (run "disable-team-feature"
                   {:team-id (:id team) :feature "text-editor/v2"})]
    (t/is (not (some #{"text-editor/v2"} (:features out))))
    (t/is (= (:features out) (:features again)))))

(t/deftest unsupported-feature-rejected-without-changes
  (let [admin  (th/create-profile* 1)
        team   (th/create-team* 1 {:profile-id (:id admin)})
        run    (as-superuser admin)
        before (run "get-team" {:id (:id team)})]
    (t/is (= :feature-not-supported
             (caught-code #(run "enable-team-feature"
                                {:team-id (:id team) :feature "nope/missing"}))))
    (t/is (= :feature-not-supported
             (caught-code #(run "disable-team-feature"
                                {:team-id (:id team) :feature "nope/missing"}))))
    (let [after (run "get-team" {:id (:id team)})]
      (t/is (= (:features before) (:features after))))))

(t/deftest toggles-on-unknown-team-give-not-found
  (let [profile (th/create-profile* 1)
        run     (as-superuser profile)
        missing (uuid/next)]
    (t/is (= :team-not-found
             (caught-code #(run "enable-team-feature"
                                {:team-id missing :feature "text-editor/v2"}))))
    (t/is (= :team-not-found
             (caught-code #(run "disable-team-feature"
                                {:team-id missing :feature "text-editor/v2"}))))))

(t/deftest writes-work-on-deleted-team
  (let [admin (th/create-profile* 1)
        team  (th/create-team* 1 {:profile-id (:id admin)})
        _     (db/update! th/*system* :team {:deleted-at (ct/now)} {:id (:id team)})
        run   (as-superuser admin)]
    (t/is (= 1 (count (run "get-team-members" {:team-id (:id team)}))))
    (let [out (run "enable-team-feature"
                   {:team-id (:id team) :feature "text-editor/v2"})]
      (t/is (some #{"text-editor/v2"} (:features out))))))

;; ----------------------------------------------------------------
;; Member roles
;; ----------------------------------------------------------------

(defn- role-of
  [member]
  (cond (:is-owner member) :owner
        (:is-admin member) :admin
        (:can-edit member) :editor
        :else              :viewer))

(defn- roles-by-id
  [run team-id]
  (into {}
        (map (juxt :id role-of))
        (run "get-team-members" {:team-id team-id})))

(defn- create-team-with-editor
  []
  (let [admin  (th/create-profile* 1)
        editor (th/create-profile* 2)
        team   (th/create-team* 1 {:profile-id (:id admin)})
        _      (th/create-team-role* {:team-id (:id team)
                                      :profile-id (:id editor)
                                      :role :editor})]
    {:admin admin :editor editor :team team}))

(t/deftest update-role-guard
  (let [profile (th/create-profile* 1)
        run     (as-session #{} profile)]
    (t/is (= :superuser-required
             (caught-code #(run "update-team-member-role"
                                {:team-id (uuid/next)
                                 :member-id (uuid/next)
                                 :role "admin"}))))))

(t/deftest update-role-promotes-and-demotes
  (let [{:keys [admin editor team]} (create-team-with-editor)
        run (as-superuser admin)]
    (t/is (= :editor (get (roles-by-id run (:id team)) (:id editor))))
    (run "update-team-member-role"
         {:team-id (:id team) :member-id (:id editor) :role "admin"})
    (t/is (= :admin (get (roles-by-id run (:id team)) (:id editor))))
    (run "update-team-member-role"
         {:team-id (:id team) :member-id (:id editor) :role "viewer"})
    (t/is (= :viewer (get (roles-by-id run (:id team)) (:id editor))))))

(t/deftest update-role-promote-to-owner-transfers-seat
  (let [{:keys [admin editor team]} (create-team-with-editor)
        run   (as-superuser admin)
        _     (run "update-team-member-role"
                   {:team-id (:id team) :member-id (:id editor) :role "owner"})
        roles (roles-by-id run (:id team))]
    (t/is (= :owner (get roles (:id editor))))
    (t/is (= :admin (get roles (:id admin))))))

(t/deftest update-role-cannot-demote-owner-directly
  (let [{:keys [admin team]} (create-team-with-editor)
        run (as-superuser admin)]
    (t/is (= :cant-change-role-to-owner
             (caught-code #(run "update-team-member-role"
                                {:team-id (:id team)
                                 :member-id (:id admin)
                                 :role "admin"}))))))

(t/deftest update-role-unknown-member-gives-not-found
  (let [{:keys [admin team]} (create-team-with-editor)
        run (as-superuser admin)]
    (t/is (= :member-does-not-exist
             (caught-code #(run "update-team-member-role"
                                {:team-id (:id team)
                                 :member-id (uuid/next)
                                 :role "admin"}))))))

(t/deftest update-role-unknown-team-gives-not-found
  (let [{:keys [admin editor]} (create-team-with-editor)
        run (as-superuser admin)]
    (t/is (= :team-not-found
             (caught-code #(run "update-team-member-role"
                                {:team-id (uuid/next)
                                 :member-id (:id editor)
                                 :role "admin"}))))))
