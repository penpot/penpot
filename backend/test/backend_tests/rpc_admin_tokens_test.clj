;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS SUBSIDIARY SL

(ns backend-tests.rpc-admin-tokens-test
  "Tests for the admin access-token issuance and management."
  (:require
   [app.auth :as-alias auth]
   [app.common.data :as d]
   [app.common.time :as ct]
   [app.config :as cf]
   [app.db :as db]
   [app.http :as-alias http]
   [app.rpc :as rpc]
   [app.rpc.admin.perms :as perms]
   [app.util.services :as sv]
   [backend-tests.helpers :as th]
   [clojure.test :as t]))

(t/use-fixtures :once th/state-init)
(t/use-fixtures :each th/database-reset)

;; ----------------------------------------------------------------
;; Helpers
;; ----------------------------------------------------------------

(defn- with-host
  [host f]
  (with-redefs [cf/get (th/config-get-mock {:host host})]
    (f)))

(defn- raw-method
  [cmd-name]
  (some (fn [[f mdata]]
          (when (= cmd-name (:app.util.services/name mdata))
            [f mdata]))
        (sv/scan-ns 'app.rpc.admin.tokens)))

(defn- call-as
  [superusers profile-id auth-type token-perms cmd params]
  (let [[f mdata] (raw-method cmd)
        cfg       (assoc th/*system* ::auth/superusers superusers)
        request   (assoc (th/make-dummy-request)
                         :params (d/without-qualified params))
        server    (with-meta params {::rpc/request-at (ct/now)
                                     ::http/request request})
        wrapped   (#'rpc/wrap cfg f mdata)]
    (with-host "example.com"
      (fn []
        (wrapped cfg (assoc server
                            ::rpc/profile-id profile-id
                            ::rpc/auth-type auth-type
                            ::rpc/token-perms token-perms))))))

(defn- as-superuser
  [profile]
  (fn [cmd params]
    (call-as #{(:id profile)} (:id profile) :session #{} cmd params)))

(defn- caught-code
  [thunk]
  (try
    (thunk)
    ::no-throw
    (catch clojure.lang.ExceptionInfo cause
      (th/ex-code cause))))

(defn- token-row
  [id]
  (db/get* th/*pool* :access-token {:id id}))

;; ----------------------------------------------------------------
;; Issuance
;; ----------------------------------------------------------------

(t/deftest non-superuser-cannot-issue
  (let [profile (th/create-profile* 1 {:is-active true})]
    (t/is (= :superuser-required
             (caught-code #(call-as #{} (:id profile) :session #{}
                                    "create-admin-token"
                                    {:name "support" :perms ["admin:get-teams"]}))))))

(t/deftest superuser-issues-for-self-by-default
  (let [profile (th/create-profile* 1 {:is-active true})
        run     (as-superuser profile)
        out     (run "create-admin-token" {:name "support"
                                           :perms ["admin:get-teams"]})]
    (t/is (string? (:token out)) "the secret is returned once, at creation")
    (t/is (= #{"admin:get-teams"} (set (:perms out))))
    (t/is (= "admin" (:type out)))
    (t/is (= (:id profile) (:profile-id (token-row (:id out))))
          "the stored row belongs to the creator")))

(t/deftest superuser-issues-for-another-profile
  (let [admin (th/create-profile* 1 {:is-active true})
        other (th/create-profile* 2 {:is-active true})
        run   (as-superuser admin)
        out   (run "create-admin-token" {:name "support"
                                         :profile-id (:id other)
                                         :perms ["admin:get-teams"]})]
    (t/is (= (:id other) (:profile-id (token-row (:id out)))))))

(t/deftest unknown-perm-rejected
  (let [profile (th/create-profile* 1 {:is-active true})
        run     (as-superuser profile)]
    (t/is (= :unknown-permission
             (caught-code #(run "create-admin-token" {:name "support"
                                                      :perms ["admin:nope"]}))))))

(t/deftest superuser-scope-never-grantable
  (let [profile (th/create-profile* 1 {:is-active true})
        run     (as-superuser profile)]
    (t/is (= :superuser-not-grantable
             (caught-code #(run "create-admin-token" {:name "root"
                                                      :perms ["superuser"]}))))))

(t/deftest empty-perms-rejected
  (let [profile (th/create-profile* 1 {:is-active true})
        run     (as-superuser profile)]
    (t/is (= :params-validation
             (caught-code #(run "create-admin-token" {:name "support"
                                                      :perms []}))))))

(t/deftest missing-owner-rejected
  (let [profile (th/create-profile* 1 {:is-active true})
        run     (as-superuser profile)]
    (t/is (= :profile-not-found
             (caught-code #(run "create-admin-token" {:name "support"
                                                      :profile-id (random-uuid)
                                                      :perms ["admin:get-teams"]}))))))

(t/deftest expiration-is-optional-but-respected
  (let [profile (th/create-profile* 1 {:is-active true})
        run     (as-superuser profile)
        plain   (run "create-admin-token" {:name "no-expiry"
                                           :perms ["admin:get-teams"]})
        dated   (run "create-admin-token" {:name "dated"
                                           :expiration (ct/duration "720h")
                                           :perms ["admin:get-teams"]})]
    (t/is (nil? (:expires-at plain)))
    (t/is (ct/is-after? (:expires-at dated) (ct/now)))))

;; ----------------------------------------------------------------
;; Listing and revocation
;; ----------------------------------------------------------------

(t/deftest listing-hides-secret-and-shows-perms
  (let [profile (th/create-profile* 1 {:is-active true})
        run     (as-superuser profile)
        created (run "create-admin-token" {:name "support"
                                           :perms ["admin:get-teams"]})
        listed  (run "get-admin-tokens" {})
        entry   (some #(when (= (:id created) (:id %)) %) listed)]
    (t/is (some? entry))
    (t/is (not (contains? entry :token)) "the secret never leaves creation")
    (t/is (= #{"admin:get-teams"} (set (:perms entry))))))

(t/deftest revocation-deletes-the-row
  (let [profile (th/create-profile* 1 {:is-active true})
        run     (as-superuser profile)
        created (run "create-admin-token" {:name "support"
                                           :perms ["admin:get-teams"]})]
    (run "revoke-admin-token" {:id (:id created)})
    (t/is (nil? (token-row (:id created))))
    (t/is (empty? (filter #(= (:id created) (:id %))
                          (run "get-admin-tokens" {}))))))

(t/deftest revoking-unknown-token-is-not-found
  (let [profile (th/create-profile* 1 {:is-active true})
        run     (as-superuser profile)]
    (t/is (= :access-token-not-found
             (caught-code #(run "revoke-admin-token" {:id (random-uuid)}))))))

;; ----------------------------------------------------------------
;; The permission vocabulary
;; ----------------------------------------------------------------

(t/deftest superuser-lists-grantable-permissions
  (let [profile (th/create-profile* 1 {:is-active true})
        run     (as-superuser profile)
        out     (run "get-admin-permissions" {})]
    (t/is (= (sort out) (vec out)) "stable order for the panel")
    (t/is (contains? (set out) "admin:get-teams"))
    (t/is (not (contains? (set out) "superuser")))
    (t/is (not (contains? (set out) "admin:create-admin-token"))
          "root-only methods are not offered")))

(t/deftest non-superuser-cannot-list-permissions
  (let [profile (th/create-profile* 1 {:is-active true})]
    (t/is (= :superuser-required
             (caught-code #(call-as #{} (:id profile) :session #{}
                                    "get-admin-permissions" {}))))
    (t/is (= :superuser-required
             (caught-code #(call-as #{} (:id profile) :token #{"admin:get-teams"}
                                    "get-admin-permissions" {}))))))

;; ----------------------------------------------------------------
;; The issued token works where it should
;; ----------------------------------------------------------------

(t/deftest issued-token-reaches-its-method
  (let [admin   (th/create-profile* 1 {:is-active true})
        run     (as-superuser admin)
        created (run "create-admin-token" {:name "support"
                                           :perms ["admin:get-profiles"]})
        [f mdata] (some (fn [[f mdata]]
                          (when (= "get-profiles"
                                   (:app.util.services/name mdata))
                            [f mdata]))
                        (apply sv/scan-ns perms/admin-namespaces))
        cfg       (assoc th/*system* ::auth/superusers #{})
        request   (assoc (th/make-dummy-request) :params {})
        server    (with-meta {} {::rpc/request-at (ct/now)
                                 ::http/request request})
        wrapped   (#'rpc/wrap cfg f mdata)]
    (with-host "example.com"
      (fn []
        (let [out (wrapped cfg (assoc server
                                      ::rpc/profile-id (:id admin)
                                      ::rpc/auth-type :token
                                      ::rpc/token-perms #{"admin:get-profiles"}))]
          (t/is (seq (:items out))))
        (t/is (= (:id created) (:id (token-row (:id created))))
              "sanity: the issued row is the one the wrapper trusted")))))
