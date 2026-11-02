;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS SUBSIDIARY SL

(ns backend-tests.rpc-admin-perms-test
  "Tests for the implicit per-method permission of the admin API.

  Every admin method requires `admin:<method>` (derived from its
  registered name) unless the caller is a superuser, which bypasses
  everything. Granular access is token-only: sessions still need
  superuser."
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
        (apply sv/scan-ns perms/admin-namespaces)))

(defn- call
  [auth-type profile-id token-perms cmd params]
  (let [[f mdata] (raw-method cmd)
        cfg       (assoc th/*system* ::auth/superusers #{})
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

(defn- caught-code
  [thunk]
  (try
    (thunk)
    ::no-throw
    (catch clojure.lang.ExceptionInfo cause
      (th/ex-code cause))))

;; ----------------------------------------------------------------
;; Registry invariant
;; ----------------------------------------------------------------

(t/deftest admin-methods-declare-no-explicit-perms
  (let [offenders (into []
                        (comp (filter (fn [[_ mdata]] (perms/admin-method? mdata)))
                              (filter (fn [[_ mdata]]
                                        (let [declared (set (:app.rpc/perms mdata))]
                                          (and (seq declared)
                                               (not (and (= "app.rpc.admin.tokens" (:ns mdata))
                                                         (= #{"superuser"} declared)))))))
                              (map (fn [[_ mdata]]
                                     (str (:ns mdata) "/"
                                          (:app.util.services/name mdata)))))
                        (apply sv/scan-ns perms/admin-namespaces))]
    (t/is (empty? offenders)
          "admin methods rely on the implicit perm; only the token
          management methods keep an explicit superuser requirement")))

(t/deftest grantable-vocabulary-covers-known-methods
  (let [vocab (perms/grantable-perms)]
    (t/is (contains? vocab "admin:get-profiles"))
    (t/is (contains? vocab "admin:get-teams"))
    (t/is (not (contains? vocab "superuser"))
          "superuser is a bypass, never a grantable perm")
    (t/is (not (contains? vocab "admin:create-admin-token"))
          "root-only methods are out of the grantable set")))

;; ----------------------------------------------------------------
;; Implicit perm on the wire
;; ----------------------------------------------------------------

(t/deftest token-with-exact-implicit-reaches-method
  (let [profile (th/create-profile* 1 {:is-active true})
        out     (call :token (:id profile) #{"admin:get-profiles"}
                      "get-profiles" {})]
    (t/is (= [(:id profile)] (mapv :id (:items out))))))

(t/deftest token-with-other-implicit-rejected
  (let [profile (th/create-profile* 1 {:is-active true})]
    (t/is (= :missing-perms
             (caught-code #(call :token (:id profile) #{"admin:get-teams"}
                                 "get-profiles" {}))))))

(t/deftest token-without-perms-rejected
  (let [profile (th/create-profile* 1 {:is-active true})]
    (t/is (= :missing-perms
             (caught-code #(call :token (:id profile) #{}
                                 "get-profiles" {}))))))

(t/deftest session-without-superuser-still-rejected
  (let [profile (th/create-profile* 1 {:is-active true})]
    (t/is (= :superuser-required
             (caught-code #(call :session (:id profile) #{}
                                 "get-profiles" {}))))))

;; ----------------------------------------------------------------
;; Owner liveness (fail-closed)
;; ----------------------------------------------------------------

(defn- set-flags
  [profile-id flags]
  (db/update! th/*system* :profile flags {:id profile-id}))

(t/deftest token-of-blocked-owner-rejected
  (let [profile (th/create-profile* 1 {:is-active true})]
    (set-flags (:id profile) {:is-blocked true})
    (t/is (= :profile-blocked
             (caught-code #(call :token (:id profile) #{"admin:get-profiles"}
                                 "get-profiles" {}))))))

(t/deftest token-of-deleted-owner-rejected
  (let [profile (th/create-profile* 1 {:is-active true})]
    (set-flags (:id profile) {:deleted-at (ct/now)})
    (t/is (= :profile-inactive
             (caught-code #(call :token (:id profile) #{"admin:get-profiles"}
                                 "get-profiles" {}))))))

(t/deftest token-of-inactive-owner-rejected
  (let [profile (th/create-profile* 1)]
    (t/is (= :profile-inactive
             (caught-code #(call :token (:id profile) #{"admin:get-profiles"}
                                 "get-profiles" {}))))))

(t/deftest listed-session-of-blocked-profile-unaffected
  ;; The liveness check hardens the granular token path only;
  ;; superuser bypass keeps its historical behavior.
  (let [profile (th/create-profile* 1 {:is-active true})]
    (set-flags (:id profile) {:is-blocked true})
    (let [[f mdata] (raw-method "get-profiles")
          cfg       (assoc th/*system* ::auth/superusers #{(:id profile)})
          request   (assoc (th/make-dummy-request) :params {})
          server    (with-meta {} {::rpc/request-at (ct/now)
                                   ::http/request request})
          wrapped   (#'rpc/wrap cfg f mdata)]
      (with-host "example.com"
        (fn []
          (t/is (= [(:id profile)]
                   (mapv :id (:items (wrapped cfg (assoc server
                                                         ::rpc/profile-id (:id profile)
                                                         ::rpc/auth-type :session
                                                         ::rpc/token-perms #{})))))))))))
