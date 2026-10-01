;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS SUBSIDIARY SL

(ns backend-tests.rpc-admin-users-test
  "Tests for the superuser-guarded user management commands."
  (:require
   [app.auth :as-alias auth]
   [app.common.data :as d]
   [app.common.time :as ct]
   [app.common.uuid :as uuid]
   [app.db :as db]
   [app.http :as-alias http]
   [app.jobs :as jobs]
   [app.rpc :as rpc]
   [app.util.services :as sv]
   [backend-tests.helpers :as th]
   [clojure.set :as set]
   [clojure.string :as str]
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
        (sv/scan-ns 'app.rpc.admin.profile)))

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

(defn- set-flags
  [profile-id flags]
  (db/update! th/*system* :profile flags {:id profile-id}))

;; ----------------------------------------------------------------
;; Guard
;; ----------------------------------------------------------------

(t/deftest listed-session-lists-itself
  (let [profile (th/create-profile* 1)
        run     (as-superuser profile)
        out     (run "get-profiles" {})]
    (t/is (= [(:id profile)] (mapv :id (:items out)))
          "the listing includes the caller itself")))

(t/deftest unlisted-session-rejected
  (let [profile (th/create-profile* 1)
        run     (as-session #{} profile)]
    (t/is (= :superuser-required
             (caught-code #(run "get-profiles" {}))))))

(t/deftest unlisted-token-without-scope-rejected
  (let [profile (th/create-profile* 1)]
    (t/is (= :superuser-required
             (caught-code #(call #{} :token (:id profile) #{}
                                 "get-profiles" {}))))))

(t/deftest token-with-granted-superuser-passes
  (let [profile (th/create-profile* 1)
        out     (call #{} :token (:id profile) #{"superuser"}
                      "get-profiles" {})]
    (t/is (= [(:id profile)] (mapv :id (:items out))))))

(t/deftest anonymous-rejected
  (t/is (= :authentication-required
           (caught-code #(call #{} nil nil #{}
                               "get-profiles" {})))))

(t/deftest invalid-params-still-validated-for-listed-caller
  (let [profile (th/create-profile* 1)
        run     (as-superuser profile)]
    (t/is (= :params-validation
             (caught-code #(run "get-profiles" {:limit 500}))))))

;; ----------------------------------------------------------------
;; Listing
;; ----------------------------------------------------------------

(t/deftest list-returns-profiles-without-private-attrs
  (let [admin (th/create-profile* 1)
        alice (th/create-profile* 2 {:fullname "Alice Admin-Seen"})
        run   (as-superuser admin)
        out   (run "get-profiles" {})
        by-id (into {} (map (juxt :id identity)) (:items out))]
    (t/is (= 2 (count (:items out))))
    (t/is (= "Alice Admin-Seen" (:fullname (get by-id (:id alice)))))
    (doseq [item (:items out)]
      (t/is (contains? item :is-active))
      (t/is (contains? item :is-blocked))
      (t/is (contains? item :is-demo))
      (t/is (not (contains? item :password)))
      (t/is (not (contains? item :props)))
      (t/is (not (contains? item :deleted-at))))))

(t/deftest list-finds-by-id-and-exact-email
  (let [admin (th/create-profile* 1)
        alice (th/create-profile* 2 {:fullname "Alice Admin-Seen"})
        run   (as-superuser admin)]
    (let [out (run "get-profiles" {:id (:id alice)})]
      (t/is (= [(:id alice)] (mapv :id (:items out))))
      (t/is (some? (:modified-at (first (:items out))))))
    (let [out (run "get-profiles" {:id (uuid/next)})]
      (t/is (= {:items []} out)))
    (let [out (run "get-profiles" {:email (:email alice)})]
      (t/is (= [(:id alice)] (mapv :id (:items out)))
            "exact email hits the unique index"))
    (let [out (run "get-profiles" {:email (str/upper-case (:email alice))})]
      (t/is (= [(:id alice)] (mapv :id (:items out)))
            "email lookup is case-insensitive"))
    (let [out (run "get-profiles" {:email (str "  " (str/upper-case (:email alice)) "  ")})]
      (t/is (= [(:id alice)] (mapv :id (:items out)))
            "email lookup trims surrounding spaces"))
    (let [out (run "get-profiles" {:email "no-such-user@nodomain.com"})]
      (t/is (= {:items []} out)))))

(t/deftest list-filters-by-deleted
  (let [admin (th/create-profile* 1)
        gone  (th/create-profile* 2)
        _     (set-flags (:id gone) {:deleted-at (ct/now)})
        run   (as-superuser admin)]
    (let [out (run "get-profiles" {:deleted true})]
      (t/is (= [(:id gone)] (mapv :id (:items out)))))
    (let [out (run "get-profiles" {:deleted false})]
      (t/is (= [(:id admin)] (mapv :id (:items out)))))
    (let [out (run "get-profiles" {})]
      (t/is (= 2 (count (:items out)))
            "without the flag, deleted rows still list"))))

(t/deftest list-paginates
  (let [admin (th/create-profile* 1)
        _     (th/create-profile* 2)
        _     (th/create-profile* 3)
        _     (th/create-profile* 4)
        run   (as-superuser admin)
        page1 (run "get-profiles" {:limit 2})]
    (t/is (= 2 (count (:items page1))))
    (t/is (some? (:next-since page1)))
    (t/is (some? (:next-id page1)))
    (let [page2 (run "get-profiles" {:limit 2
                                     :since (:next-since page1)
                                     :since-id (:next-id page1)})
          ids1  (set (map :id (:items page1)))
          ids2  (set (map :id (:items page2)))]
      (t/is (= 2 (count (:items page2))))
      (t/is (empty? (set/intersection ids1 ids2))
            "pages do not overlap")
      (t/is (nil? (:next-since page2)))
      (t/is (nil? (:next-id page2))))))

(t/deftest list-includes-deleted-with-stamp
  (let [admin   (th/create-profile* 1)
        gone    (th/create-profile* 2)
        _       (set-flags (:id gone) {:deleted-at (ct/now)})
        run     (as-superuser admin)
        out     (run "get-profiles" {})
        by-id   (into {} (map (juxt :id identity)) (:items out))]
    (t/is (= 2 (count (:items out))))
    (t/is (some? (:deleted-at (get by-id (:id gone)))))
    (t/is (nil? (:deleted-at (get by-id (:id admin)))))))

;; ----------------------------------------------------------------
;; Detail
;; ----------------------------------------------------------------

(t/deftest detail-guard
  (let [profile (th/create-profile* 1)]
    (t/is (= :superuser-required
             (caught-code #((as-session #{} profile)
                            "get-profile" {:id (:id profile)}))))
    (t/is (= :authentication-required
             (caught-code #(call #{} nil nil #{}
                                 "get-profile" {:id (:id profile)}))))))

(t/deftest detail-returns-profile-with-teams
  (let [admin (th/create-profile* 1)
        user  (th/create-profile* 2 {:fullname "Detail User"})
        _     (th/create-team-role* {:team-id (:default-team-id admin)
                                     :profile-id (:id user)
                                     :role :editor})
        run   (as-superuser admin)
        out   (run "get-profile" {:id (:id user)})]
    (t/is (= (:id user) (:id out)))
    (t/is (= "Detail User" (:fullname out)))
    (t/is (false? (:is-blocked out)))
    (t/is (not (contains? out :password)))
    (t/is (not (contains? out :props)))
    (t/is (= 1 (count (:owned-teams out))))
    (t/is (= "Default" (:name (first (:owned-teams out)))))
    (t/is (= 1 (:members (first (:owned-teams out)))))
    (t/is (= 1 (count (:member-teams out))))
    (t/is (false? (:is-owner (first (:member-teams out)))))))

(t/deftest detail-unknown-id-gives-not-found
  (let [profile (th/create-profile* 1)
        run     (as-superuser profile)]
    (t/is (= :profile-not-found
             (caught-code #(run "get-profile" {:id (uuid/next)}))))))

(t/deftest detail-deleted-returns-with-stamp
  (let [admin (th/create-profile* 1)
        gone  (th/create-profile* 2)
        _     (set-flags (:id gone) {:deleted-at (ct/now)})
        run   (as-superuser admin)
        out   (run "get-profile" {:id (:id gone)})]
    (t/is (= (:id gone) (:id out)))
    (t/is (some? (:deleted-at out)))))

(t/deftest detail-deleted-keeps-owned-teams
  ;; Deleting a profile deletes its teams too, but the detail must
  ;; still list them (marked as deleted), not pretend there are none.
  (let [admin (th/create-profile* 1)
        gone  (th/create-profile* 2)
        team  (th/create-team* 2 {:profile-id (:id gone)})
        _     (set-flags (:id gone) {:deleted-at (ct/now)})
        _     (db/update! th/*system* :team
                          {:deleted-at (ct/now)}
                          {:id (:id team)})
        run   (as-superuser admin)
        out   (run "get-profile" {:id (:id gone)})
        by-id (into {} (map (juxt :id identity) (:owned-teams out)))]
    (t/is (= 2 (count (:owned-teams out))))
    (t/is (some? (:deleted-at (get by-id (:id team)))))
    (t/is (nil? (:deleted-at (get by-id (:default-team-id gone)))))))

;; ----------------------------------------------------------------
;; Block / unblock
;; ----------------------------------------------------------------

(defn- open-session
  [profile-id]
  (th/db-exec-one! ["INSERT INTO http_session_v2 (id, profile_id) VALUES (?, ?)"
                    (uuid/next) profile-id]))

(defn- session-count
  [profile-id]
  (:count (th/db-exec-one! ["SELECT count(*) AS count FROM http_session_v2 WHERE profile_id = ?"
                            profile-id])))

(t/deftest block-guard
  (let [profile (th/create-profile* 1)]
    (t/is (= :superuser-required
             (caught-code #((as-session #{} profile)
                            "block-profile" {:id (:id profile)}))))
    (t/is (= :superuser-required
             (caught-code #((as-session #{} profile)
                            "unblock-profile" {:id (:id profile)}))))))

(t/deftest block-sets-flag-and-closes-sessions
  (let [admin (th/create-profile* 1)
        user  (th/create-profile* 2)
        _     (open-session (:id user))
        _     (open-session (:id user))
        run   (as-superuser admin)
        out   (run "block-profile" {:id (:id user)})]
    (t/is (= {:id (:id user) :is-blocked true} out))
    (t/is (zero? (session-count (:id user))))))

(t/deftest block-is-idempotent
  (let [admin (th/create-profile* 1)
        user  (th/create-profile* 2)
        run   (as-superuser admin)]
    (run "block-profile" {:id (:id user)})
    (t/is (= {:id (:id user) :is-blocked true}
             (run "block-profile" {:id (:id user)})))))

(t/deftest block-self-refused
  (let [admin (th/create-profile* 1)
        run   (as-superuser admin)]
    (t/is (= :cannot-block-self
             (caught-code #(run "block-profile" {:id (:id admin)}))))))

(t/deftest block-unknown-id-gives-not-found
  (let [admin (th/create-profile* 1)
        run   (as-superuser admin)]
    (t/is (= :profile-not-found
             (caught-code #(run "block-profile" {:id (uuid/next)}))))
    (t/is (= :profile-not-found
             (caught-code #(run "unblock-profile" {:id (uuid/next)}))))))

(t/deftest writes-refuse-deleted
  (let [admin (th/create-profile* 1)
        gone  (th/create-profile* 2)
        _     (set-flags (:id gone) {:deleted-at (ct/now)})
        run   (as-superuser admin)]
    (t/is (= :profile-not-found
             (caught-code #(run "block-profile" {:id (:id gone)}))))
    (t/is (= :profile-not-found
             (caught-code #(run "unblock-profile" {:id (:id gone)}))))
    (t/is (= :profile-not-found
             (caught-code #(run "resend-verification" {:id (:id gone)}))))
    (let [out (run "delete-profiles" {:emails [(:email gone)]})]
      (t/is (= [] (:deleted out)))
      (t/is (= [(:email gone)] (:not-found out))))))

(t/deftest unblock-clears-flag
  (let [admin (th/create-profile* 1)
        user  (th/create-profile* 2)
        run   (as-superuser admin)]
    (run "block-profile" {:id (:id user)})
    (t/is (= {:id (:id user) :is-blocked false}
             (run "unblock-profile" {:id (:id user)})))))

;; ----------------------------------------------------------------
;; Resend verification
;; ----------------------------------------------------------------

(t/deftest resend-guard
  (let [profile (th/create-profile* 1)]
    (t/is (= :superuser-required
             (caught-code #((as-session #{} profile)
                            "resend-verification" {:id (:id profile)}))))
    (t/is (= :authentication-required
             (caught-code #(call #{} nil nil #{}
                                 "resend-verification"
                                 {:id (:id profile)}))))))

(t/deftest resend-schedules-email-for-inactive
  (let [admin (th/create-profile* 1)
        user  (th/create-profile* 2)
        run   (as-superuser admin)
        sent  (atom nil)]
    (with-redefs [jobs/submit (fn [_cfg {:keys [::jobs/name ::jobs/params]}]
                                (reset! sent {:task name :params params})
                                nil)]
      (let [out (run "resend-verification" {:id (:id user)})]
        (t/is (= {:id (:id user) :email (:email user)} out))))
    (t/is (= :sendmail (:task @sent)))
    (t/is (= (:email user) (:to (:params @sent))))))

(t/deftest resend-active-refused
  (let [admin  (th/create-profile* 1)
        active (th/create-profile* 2 {:is-active true})
        run    (as-superuser admin)
        sent   (atom nil)]
    (with-redefs [jobs/submit (fn [_cfg {:keys [::jobs/name ::jobs/params]}]
                                (reset! sent {:task name :params params})
                                nil)]
      (t/is (= :already-active
               (caught-code #(run "resend-verification"
                                  {:id (:id active)})))))
    (t/is (nil? @sent) "no email is scheduled")))

(t/deftest resend-unknown-id-gives-not-found
  (let [admin (th/create-profile* 1)
        run   (as-superuser admin)]
    (t/is (= :profile-not-found
             (caught-code #(run "resend-verification"
                                {:id (uuid/next)}))))))

;; ----------------------------------------------------------------
;; Delete in bulk by email
;; ----------------------------------------------------------------

(t/deftest delete-guard
  (let [profile (th/create-profile* 1)]
    (t/is (= :superuser-required
             (caught-code #((as-session #{} profile)
                            "delete-profiles"
                            {:emails ["a@test.com"]}))))
    (t/is (= :authentication-required
             (caught-code #(call #{} nil nil #{}
                                 "delete-profiles"
                                 {:emails ["a@test.com"]}))))))
(t/deftest delete-marks-profiles-and-closes-sessions
  (let [admin (th/create-profile* 1)
        user2 (th/create-profile* 2)
        user3 (th/create-profile* 3)
        _     (open-session (:id user2))
        run   (as-superuser admin)
        jobs  (atom [])]
    (with-redefs [jobs/submit (fn [_cfg {:keys [::jobs/name ::jobs/params]}]
                                (swap! jobs conj {:task name :params params})
                                nil)]
      (let [out (run "delete-profiles"
                     {:emails [(:email user2)
                               "ghost@test.com"
                               (:email user3)]})]
        (t/is (= 3 (:total out)))
        (t/is (= (set [(:id user2) (:id user3)]) (set (:deleted out))))
        (t/is (= ["ghost@test.com"] (:not-found out)))
        (t/is (= [] (:skipped-self out)))))
    (t/is (some? (:deleted-at (db/get* th/*system* :profile {:id (:id user2)}
                                       {::db/remove-deleted false})))
          "the row stays until the collector purges it")
    (t/is (zero? (session-count (:id user2))))
    (t/is (= 2 (count @jobs)))
    (t/is (every? #(= :delete-object (:task %)) @jobs))
    (let [out (run "get-profiles" {:email (:email user2)})]
      (t/is (= 1 (count (:items out)))
            "a marked profile still lists under its own email")
      (t/is (some? (:deleted-at (first (:items out))))))))

(t/deftest delete-skips-self
  (let [admin (th/create-profile* 1)
        run   (as-superuser admin)]
    (let [out (run "delete-profiles" {:emails [(:email admin)]})]
      (t/is (= [(:email admin)] (:skipped-self out)))
      (t/is (= [] (:deleted out))))
    (t/is (nil? (:deleted-at (db/get* th/*system* :profile {:id (:id admin)})))
          "the operator row is untouched")))

(t/deftest delete-normalizes-email-case
  (let [admin (th/create-profile* 1)
        user  (th/create-profile* 2)
        run   (as-superuser admin)
        jobs  (atom [])]
    (with-redefs [jobs/submit (fn [_cfg {:keys [::jobs/name ::jobs/params]}]
                                (swap! jobs conj {:task name :params params})
                                nil)]
      (let [out (run "delete-profiles"
                     {:emails [(str/upper-case (:email user))]})]
        (t/is (= [(:id user)] (:deleted out)))
        (t/is (= 1 (count @jobs)))))))

(t/deftest delete-validates-batch-size
  (let [admin (th/create-profile* 1)
        run   (as-superuser admin)]
    (t/is (= :params-validation
             (caught-code #(run "delete-profiles" {:emails []}))))
    (t/is (= :params-validation
             (caught-code #(run "delete-profiles"
                                {:emails (mapv str (range 101))}))))))
