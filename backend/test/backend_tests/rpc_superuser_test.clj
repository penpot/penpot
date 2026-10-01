;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS SUBSIDIARY SL

(ns backend-tests.rpc-superuser-test
  "Tests for the `superuser` permission in the main RPC API."
  (:require
   [app.auth :as-alias auth]
   [app.common.data :as d]
   [app.common.schema :as sm]
   [app.common.time :as ct]
   [app.config :as cf]
   [app.db :as db]
   [app.http :as-alias http]
   [app.rpc :as rpc]
   [app.util.services :as sv]
   [backend-tests.helpers :as th]
   [clojure.test :as t]
   [cuerdas.core :as str]
   [integrant.core :as ig]))

(t/use-fixtures :once th/state-init)
(t/use-fixtures :each th/database-reset)

;; ----------------------------------------------------------------
;; Helpers
;; ----------------------------------------------------------------

(defn- with-host
  "Run `f` with `cf/get :host` answering `host`."
  [host f]
  (with-redefs [cf/get (th/config-get-mock {:host host})]
    (f)))

(defn- as-session
  [profile-id]
  {::rpc/profile-id profile-id
   ::rpc/auth-type :session})

(defn- as-token
  [profile-id]
  {::rpc/profile-id profile-id
   ::rpc/auth-type :token
   ::rpc/token-perms #{}})

(defn- as-token-with-perms
  [profile-id perms]
  (assoc (as-token profile-id) ::rpc/token-perms (set perms)))

(def ^:private schema:needs-id
  "A params schema that rejects a body missing `:id`.

  Malli maps are open by default, so this needs `:closed true` for the
  missing key to actually fail validation."
  [:map {:title "needs-id" :closed true}
   [:id ::sm/uuid]])

(defn- mdata
  [perms]
  {:name       "needs-id"
   ::rpc/auth  true
   ::rpc/perms perms
   ::sm/params schema:needs-id})

(defn- run-main-chain
  "Run `#'rpc/wrap` (the main chain) around a recording handler.

  `superusers` is the registry set, injected straight into the config the
  chain receives. Returns `::reached` when the handler ran; any rejection
  is thrown so the caller can inspect the error code. Qualified keys stay
  in `params` as server context; the unqualified ones go into the request,
  which is where `wrap-params-validation` reads them from."
  [superusers mdata params]
  (let [cfg           (assoc th/*system*
                             ::db/pool th/*pool*
                             ::auth/superusers superusers)
        request       (assoc (th/make-dummy-request)
                             :params (d/without-qualified params))
        server-params (with-meta params {::rpc/request-at (ct/now)
                                         ::http/request   request})
        wrapped       (#'rpc/wrap cfg (fn [_ _] ::reached) mdata)]
    (wrapped cfg server-params)))

(defn- command-fn
  "The raw command function behind `cmd-name` in the given namespace,
  without the wrapper chain. Lets tests call a command with a hand-built
  config, e.g. carrying a non-empty superuser set."
  [nsym cmd-name]
  (some (fn [[f mdata]]
          (when (= cmd-name (::sv/name mdata))
            f))
        (sv/scan-ns nsym)))

(defn- caught-code
  "Run `thunk` and return the error code it raised, or `::no-throw`."
  [thunk]
  (try
    (thunk)
    ::no-throw
    (catch clojure.lang.ExceptionInfo cause
      (th/ex-code cause))))

(defn- route-paths
  "Every full path in the route tree, joined from its segments."
  [prefix node]
  (if (vector? node)
    (let [segment (when (string? (first node)) (first node))
          here    (str prefix segment)]
      (cons here
            (mapcat #(route-paths here %) (filter vector? (rest node)))))
    []))

;; ----------------------------------------------------------------
;; The registry service
;; ----------------------------------------------------------------

(t/deftest registry-resolves-listed-emails-to-ids
  (let [profile (th/create-profile* 1 {:email "listed@example.com"})]
    (with-redefs [cf/get (th/config-get-mock {:superusers #{"listed@example.com"
                                                            "missing@example.com"}})]
      (let [registry (ig/init-key :app.auth/superusers {::db/pool th/*pool*})]
        (t/is (= #{(:id profile)} registry))))))

(t/deftest registry-ignores-what-does-not-exist-at-startup
  ;; Frozen at boot: whoever registers afterwards waits for a restart.
  (with-redefs [cf/get (th/config-get-mock {:superusers #{"late@example.com"}})]
    (let [registry (ig/init-key :app.auth/superusers {::db/pool th/*pool*})]
      (t/is (= #{} registry))
      (th/create-profile* 1 {:email "late@example.com"})
      (t/is (= #{} registry)))))

;; ----------------------------------------------------------------
;; The guard in the main chain
;; ----------------------------------------------------------------

(t/deftest listed-session-reaches-a-guarded-method
  (let [profile (th/create-profile* 1)]
    (with-host "example.com"
      (fn []
        (t/is (= ::reached
                 (run-main-chain #{(:id profile)}
                                 (mdata #{"superuser"})
                                 (merge (as-session (:id profile))
                                        {:id (random-uuid)}))))))))

(t/deftest listed-token-reaches-a-guarded-method
  ;; The set wins: a token needs no granted perms when the id is listed.
  (let [profile (th/create-profile* 1)]
    (with-host "example.com"
      (fn []
        (t/is (= ::reached
                 (run-main-chain #{(:id profile)}
                                 (mdata #{"superuser"})
                                 (merge (as-token (:id profile))
                                        {:id (random-uuid)}))))))))

(t/deftest unlisted-session-rejected-from-guarded-method
  (let [profile (th/create-profile* 1)]
    (with-host "example.com"
      (fn []
        (t/is (= :superuser-required
                 (caught-code
                  #(run-main-chain #{}
                                   (mdata #{"superuser"})
                                   (merge (as-session (:id profile))
                                          {:id (random-uuid)})))))))))

(t/deftest unlisted-token-rejected-from-guarded-method
  ;; A valid token does not exempt whoever is not listed.
  (let [profile (th/create-profile* 1)]
    (with-host "example.com"
      (fn []
        (t/is (= :superuser-required
                 (caught-code
                  #(run-main-chain #{}
                                   (mdata #{"superuser"})
                                   (merge (as-token (:id profile))
                                          {:id (random-uuid)})))))))))

(t/deftest token-with-granted-superuser-reaches-guarded-method
  ;; A token carrying "superuser" as an operator-granted scope satisfies the
  ;; guard for a profile the registry does not list. Scopes are granted
  ;; outside the public API, so this opens nothing by itself.
  (let [profile (th/create-profile* 1)]
    (with-host "example.com"
      (fn []
        (t/is (= ::reached
                 (run-main-chain #{}
                                 (mdata #{"superuser"})
                                 (merge (as-token-with-perms (:id profile)
                                                             #{"superuser"})
                                        {:id (random-uuid)}))))))))

(t/deftest token-with-other-scope-still-rejected
  ;; Only the exact "superuser" scope counts.
  (let [profile (th/create-profile* 1)]
    (with-host "example.com"
      (fn []
        (t/is (= :superuser-required
                 (caught-code
                  #(run-main-chain #{}
                                   (mdata #{"superuser"})
                                   (merge (as-token-with-perms (:id profile)
                                                               #{"error-reports:read"})
                                          {:id (random-uuid)})))))))))

(t/deftest anonymous-rejected-from-guarded-method
  (with-host "example.com"
    (fn []
      (t/is (= :authentication-required
               (caught-code
                #(run-main-chain #{}
                                 (mdata #{"superuser"})
                                 {:id (random-uuid)})))))))

(t/deftest scope-perm-still-requires-token-for-sessions
  ;; Methods with token scopes keep their meaning: a session cannot satisfy
  ;; them, listed or not.
  (let [profile (th/create-profile* 1)]
    (with-host "example.com"
      (fn []
        (t/is (= :token-auth-required
                 (caught-code
                  #(run-main-chain #{(:id profile)}
                                   (mdata #{"error-reports:read"})
                                   (merge (as-session (:id profile))
                                          {:id (random-uuid)})))))))))

(t/deftest guard-runs-before-params-validation
  ;; An invalid body from whoever is not listed is rejected as such, not as
  ;; a params failure: the latter would leak which params the command
  ;; expects.
  (let [profile (th/create-profile* 1)]
    (with-host "example.com"
      (fn []
        ;; `:id` missing, so params validation would fail if it ran first.
        (t/is (= :superuser-required
                 (caught-code
                  #(run-main-chain #{}
                                   (mdata #{"superuser"})
                                   (as-session (:id profile))))))))))

(t/deftest valid-params-still-validated-for-listed-caller
  (let [profile (th/create-profile* 1)]
    (with-host "example.com"
      (fn []
        (t/is (= :params-validation
                 (caught-code
                  #(run-main-chain #{(:id profile)}
                                   (mdata #{"superuser"})
                                   (as-session (:id profile))))))))))

(t/deftest any-profile-passes-on-devenv
  (let [profile (th/create-profile* 1)]
    (with-host "devenv"
      (fn []
        (t/is (= ::reached
                 (run-main-chain #{}
                                 (mdata #{"superuser"})
                                 (merge (as-session (:id profile))
                                        {:id (random-uuid)}))))))))

;; ----------------------------------------------------------------
;; The flag on get-profile and login
;; ----------------------------------------------------------------

(defn- call-profile
  [superusers profile-id]
  (let [f   (command-fn 'app.rpc.commands.profile "get-profile")
        cfg (assoc th/*system* ::auth/superusers superusers)]
    (f cfg (cond-> {}
             (some? profile-id) (assoc ::rpc/profile-id profile-id)))))

(t/deftest get-profile-flags-listed-id
  (let [profile (th/create-profile* 1)]
    (with-host "example.com"
      (fn []
        (t/is (true? (:is-superuser (call-profile #{(:id profile)} (:id profile)))))))))

(t/deftest get-profile-flags-unlisted-id-as-false
  (let [profile (th/create-profile* 1)]
    (with-host "example.com"
      (fn []
        (t/is (false? (:is-superuser (call-profile #{} (:id profile)))))))))

(t/deftest get-profile-anonymous-carries-no-flag
  (t/is (not (contains? (call-profile #{} nil) :is-superuser))))

(t/deftest get-profile-on-devenv-flags-any-profile
  ;; The flag and the guard share the rule, so they cannot diverge.
  (let [profile (th/create-profile* 1)]
    (with-host "devenv"
      (fn []
        (t/is (true? (:is-superuser (call-profile #{} (:id profile)))))))))

(defn- call-login
  [superusers email]
  (let [f   (command-fn 'app.rpc.commands.auth "login-with-password")
        cfg (assoc th/*system* ::auth/superusers superusers)]
    (f cfg {:email email :password "Test123!"})))

(t/deftest login-flags-listed-id-instead-of-admin
  (let [profile (th/create-profile* 1 {:is-active true})]
    (let [out (call-login #{(:id profile)} (:email profile))]
      (t/is (true? (:is-superuser out)))
      (t/is (not (contains? out :is-admin))))))

(t/deftest login-flags-unlisted-id-as-not-superuser
  (let [profile (th/create-profile* 1 {:is-active true})]
    (let [out (call-login #{} (:email profile))]
      (t/is (false? (:is-superuser out))))))

;; ----------------------------------------------------------------
;; Wiring: no separate module anymore
;; ----------------------------------------------------------------

(t/deftest admin-namespace-contributes-no-methods-to-main
  ;; The namespace is registered but empty: a home for future commands.
  (t/is (not (contains? (:app.rpc/methods th/*system*) :get-admin-profile))))

(t/deftest admin-route-is-gone-and-existing-routes-stand
  (let [paths (route-paths "" (:app.rpc/routes th/*system*))]
    (t/is (not-any? #(str/starts-with? % "/api/admin") paths))
    (t/is (some #{"/api/main/methods/:method-name"} paths))
    (t/is (some #{"/api/management/methods/:method-name"} paths))
    ;; still the deprecated alias of main, out of scope for this change
    (t/is (some #{"/api/rpc/command/:method-name"} paths))))
