;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC

(ns backend-tests.http-middleware-test
  (:require
   [app.common.time :as ct]
   [app.config :as cf]
   [app.db :as db]
   [app.http :as-alias http]
   [app.http.access-token :as access-token]
   [app.http.auth-request]
   [app.http.middleware :as mw]
   [app.http.session :as session]
   [app.main :as-alias main]
   [app.rpc :as-alias rpc]
   [app.rpc.commands.access-token]
   [app.rpc.commands.profile :as profile]
   [app.tokens :as tokens]
   [backend-tests.helpers :as th]
   [clojure.test :as t]
   [mockery.core :refer [with-mocks]]
   [yetti.request :as yreq]
   [yetti.response :as yres]))

(t/use-fixtures :once th/state-init)
(t/use-fixtures :each th/database-reset)

(defrecord DummyRequest [headers cookies]
  yreq/IRequestCookies
  (get-cookie [_ name]
    {:value (get cookies name)})

  yreq/IRequest
  (get-header [_ name]
    (get headers name)))

(t/deftest auth-middleware-1
  (let [request (volatile! nil)
        handler (#'app.http.middleware/wrap-auth
                 (fn [req] (vreset! request req))
                 {})]

    (handler (->DummyRequest {} {}))

    (t/is (nil? (::http/auth-data @request)))

    (handler (->DummyRequest {"authorization" "Token aaaa"} {}))

    (let [{:keys [token claims] token-type :type} (get @request ::http/auth-data)]
      (t/is (= :token token-type))
      (t/is (= "aaaa" token))
      (t/is (nil? claims)))))

(t/deftest auth-middleware-2
  (let [request (volatile! nil)
        handler (#'app.http.middleware/wrap-auth
                 (fn [req] (vreset! request req))
                 {})]

    (handler (->DummyRequest {} {}))
    (t/is (nil? (::http/auth-data @request)))

    (handler (->DummyRequest {"authorization" "Bearer aaaa"} {}))

    (let [{:keys [token claims] token-type :type} (get @request ::http/auth-data)]
      (t/is (= :bearer token-type))
      (t/is (= "aaaa" token))
      (t/is (nil? claims)))))

(t/deftest auth-middleware-3
  (let [request (volatile! nil)
        handler (#'app.http.middleware/wrap-auth
                 (fn [req] (vreset! request req))
                 {})]

    (handler (->DummyRequest {} {}))
    (t/is (nil? (::http/auth-data @request)))

    (handler (->DummyRequest {} {"auth-token" "foobar"}))

    (let [{:keys [token claims] token-type :type} (get @request ::http/auth-data)]
      (t/is (= :cookie token-type))
      (t/is (= "foobar" token))
      (t/is (nil? claims)))))

(t/deftest shared-key-auth
  (let [handler (#'app.http.middleware/wrap-shared-key-auth
                 (fn [req] {::yres/status 200})
                 {:test1 "secret-key"})]

    (let [response (handler (->DummyRequest {} {}))]
      (t/is (= 403 (::yres/status response))))

    (let [response (handler (->DummyRequest {"x-shared-key" "secret-key2"} {}))]
      (t/is (= 403 (::yres/status response))))

    (let [response (handler (->DummyRequest {"x-shared-key" "secret-key"} {}))]
      (t/is (= 403 (::yres/status response))))

    (let [response (handler (->DummyRequest {"x-shared-key" "test1 secret-key"} {}))]
      (t/is (= 200 (::yres/status response))))))

(t/deftest access-token-authz
  (let [profile (th/create-profile* 1)
        token   (db/tx-run! th/*system* app.rpc.commands.access-token/create-access-token (:id profile) "test" nil)
        handler (#'app.http.access-token/wrap-authz identity th/*system*)]

    (let [response (handler nil)]
      (t/is (nil? response)))

    (let [response (handler {::http/auth-data {:type :token :token "foobar" :claims {:tid (:id token)}}})]
      (t/is (= #{} (:app.http.access-token/perms response)))
      (t/is (= (:id profile) (:app.http.access-token/profile-id response))))))

(t/deftest session-authz
  (let [cfg      th/*system*
        manager  (session/inmemory-manager)
        profile  (th/create-profile* 1)
        handler  (-> (fn [req] req)
                     (#'session/wrap-authz  {::session/manager manager})
                     (#'mw/wrap-auth {:bearer (partial session/decode-token cfg)
                                      :cookie (partial session/decode-token cfg)}))

        session  (->> (session/create-session manager {:profile-id (:id profile)
                                                       :user-agent "user agent"})
                      (#'session/assign-token cfg))

        response (handler (->DummyRequest {} {"auth-token" (:token session)}))

        {:keys [token claims] token-type :type}
        (get response ::http/auth-data)]

    (t/is (= :cookie token-type))
    (t/is (= (:token session) token))
    (t/is (= "authentication" (:iss claims)))
    (t/is (= "penpot" (:aud claims)))
    (t/is (= (:id session) (:sid claims)))
    (t/is (= (:id profile) (:uid claims)))))

(t/deftest session-authz-does-not-renew-on-error-response
  ;; The renewal guard MUST also step aside on error responses.
  ;; Without this, a 403 produced by wrap-authz (proxy identity
  ;; mismatch with a blocked/inactive incoming profile) would still
  ;; cause alice's session cookie to be renewed on the way out —
  ;; extending her session lifetime on the very denial that signaled
  ;; her identity is no longer valid upstream.
  (let [cfg       th/*system*
        manager   (session/inmemory-manager)
        profile   (th/create-profile* 91)
        t0        (ct/inst "2025-01-01T00:00:00Z")
        t1        (ct/plus t0 (ct/duration {:seconds 2}))
        threshold (ct/duration {:seconds 1})
        handler   (-> (fn [_req] {::yres/status 403})
                      (#'session/wrap-authz {::session/manager manager})
                      (#'mw/wrap-auth {:bearer (partial session/decode-token cfg)
                                       :cookie (partial session/decode-token cfg)}))
        token     (binding [ct/*clock* (ct/fixed-clock t0)]
                    (->> (session/create-session manager {:profile-id (:id profile)
                                                          :user-agent "user agent"})
                         (#'session/assign-token cfg)))
        response  (binding [cf/config (assoc cf/config :auth-token-cookie-renewal-max-age threshold)
                            ct/*clock* (ct/fixed-clock t1)]
                    (handler (->DummyRequest {} {"auth-token" (:token token)})))]
    (t/is (= 403 (::yres/status response)))
    (t/is (not (contains? (::yres/cookies response) "auth-token")))))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; X-Auth-Request middleware tests
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(defn- make-xauth-cfg
  []
  (assoc th/*system* ::session/manager (session/inmemory-manager)))

(t/deftest x-auth-request-no-email-header
  (let [captured (volatile! nil)
        handler  (#'app.http.auth-request/wrap-authz
                  (fn [req] (vreset! captured req) {::yres/status 200})
                  (make-xauth-cfg))]
    (handler (->DummyRequest {} {}))
    (t/is (nil? (::session/profile-id @captured)))))

(t/deftest x-auth-request-preserves-session-when-header-email-unresolvable
  ;; When wrap-session has resolved alice's profile-id from the local
  ;; auth-token cookie AND the proxy header email cannot be resolved to
  ;; a profile (unknown user + auto-register off), the existing session
  ;; passes through unchanged. The middleware only re-keys when it has
  ;; a *real* alternative identity to switch to.
  (let [profile-id (random-uuid)
        handler    (#'app.http.auth-request/wrap-authz
                    (fn [req] req)
                    (make-xauth-cfg))
        request    (-> (->DummyRequest {"x-auth-request-email" "user@example.com"} {})
                       (assoc ::session/profile-id profile-id))
        result     (handler request)]
    (t/is (= profile-id (::session/profile-id result)))))

(t/deftest x-auth-request-rekeys-when-session-identity-differs
  ;; Repro of the QA-reported session-sharing bug: alice's auth-token
  ;; cookie persists on Penpot's subdomain after the portal "log out of
  ;; all apps"; bob then logs in upstream. wrap-session resolves alice's
  ;; profile-id from the old cookie, but oauth2-proxy is forwarding
  ;; bob's email. The middleware MUST re-key to bob.
  (let [alice    (th/create-profile* 1 {:is-active true})
        bob      (th/create-profile* 2 {:is-active true})
        captured (volatile! nil)
        cfg      (make-xauth-cfg)
        handler  (#'app.http.auth-request/wrap-authz
                  (fn [req] (vreset! captured req) {::yres/status 200})
                  cfg)
        request  (-> (->DummyRequest {"x-auth-request-email" (:email bob)} {})
                     (assoc ::session/profile-id (:id alice)))
        response (handler request)]
    ;; Downstream handler sees bob's profile-id, not alice's.
    (t/is (= (:id bob) (::session/profile-id @captured)))
    ;; A fresh auth-token cookie is issued for bob's session.
    (t/is (contains? (::yres/cookies response) "auth-token"))))

(t/deftest x-auth-request-no-rekey-when-session-matches-header
  ;; Steady-state guard: the browser session matches the proxy identity.
  ;; No re-key, no new cookie. Without this, every authenticated request
  ;; would mint a fresh cookie.
  (let [profile  (th/create-profile* 1 {:is-active true})
        captured (volatile! nil)
        cfg      (make-xauth-cfg)
        handler  (#'app.http.auth-request/wrap-authz
                  (fn [req] (vreset! captured req) {::yres/status 200})
                  cfg)
        request  (-> (->DummyRequest {"x-auth-request-email" (:email profile)} {})
                     (assoc ::session/profile-id (:id profile)))
        response (handler request)]
    (t/is (= (:id profile) (::session/profile-id @captured)))
    (t/is (not (contains? (::yres/cookies response) "auth-token")))))

(t/deftest x-auth-request-rekey-clears-stale-auth-data
  ;; The re-keyed request must have ::http/auth-data removed before the
  ;; inner handler runs. errors.clj logs auth-data.claims.uid as
  ;; :request/profile-id and rpc/helpers exposes the map to RPC
  ;; handlers via get-auth-data — both would otherwise see alice's UID
  ;; after we re-keyed to bob, leaking the stale identity across the
  ;; boundary the middleware thinks it has crossed.
  (let [alice    (th/create-profile* 1 {:is-active true})
        bob      (th/create-profile* 2 {:is-active true})
        captured (volatile! nil)
        cfg      (make-xauth-cfg)
        handler  (#'app.http.auth-request/wrap-authz
                  (fn [req] (vreset! captured req) {::yres/status 200})
                  cfg)
        request  (-> (->DummyRequest {"x-auth-request-email" (:email bob)} {})
                     (assoc ::session/profile-id (:id alice))
                     (assoc ::http/auth-data {:type :cookie
                                              :claims {:uid (:id alice)
                                                       :sid (random-uuid)}}))]
    (handler request)
    (t/is (= (:id bob) (::session/profile-id @captured)))
    (t/is (nil? (::http/auth-data @captured)))))

(t/deftest x-auth-request-rekey-clears-stale-session-map
  ;; ::session/session is read indirectly by session/get-session, which
  ;; the update-profile-password RPC calls via invalidate-others. If
  ;; alice's stale session map survives the re-key to bob, a password-
  ;; change made on this request would invalidate alice's other
  ;; sessions instead of bob's. Surfaced by Copilot review on PR #22.
  (let [alice         (th/create-profile* 1 {:is-active true})
        bob           (th/create-profile* 2 {:is-active true})
        alice-session {:id (random-uuid)
                       :profile-id (:id alice)
                       :user-agent "alice's ua"}
        captured      (volatile! nil)
        cfg           (make-xauth-cfg)
        handler       (#'app.http.auth-request/wrap-authz
                       (fn [req] (vreset! captured req) {::yres/status 200})
                       cfg)
        request       (-> (->DummyRequest {"x-auth-request-email" (:email bob)} {})
                          (assoc ::session/profile-id (:id alice))
                          (assoc ::session/session alice-session))]
    (handler request)
    (t/is (= (:id bob) (::session/profile-id @captured)))
    (t/is (nil? (::session/session @captured)))))

(t/deftest session-authz-renewal-does-not-overwrite-rekeyed-cookie
  ;; Integration guard: session/wrap-authz runs AFTER wrap-authz on the
  ;; way out and would normally renew the incoming session cookie. If
  ;; wrap-authz has already issued a fresh auth-token cookie for the
  ;; re-keyed identity (bob), the renewal MUST step aside — otherwise
  ;; alice's renewed cookie clobbers bob's fresh one and the re-key is
  ;; silently undone.
  ;;
  ;; This locks in the (not (contains? response cookies "auth-token"))
  ;; guard added to session.clj. A future refactor that removes it
  ;; fails this test.
  (let [alice          (th/create-profile* 91 {:is-active true})
        bob            (th/create-profile* 92 {:is-active true})
        cfg            (make-xauth-cfg)
        t0             (ct/inst "2025-01-01T00:00:00Z")
        t1             (ct/plus t0 (ct/duration {:seconds 2}))
        ;; Lower than (t1 - t0) so the seeded cookie is always due for renewal.
        renewal-threshold (ct/duration {:seconds 1})
        middleware     (-> (fn [req] {::yres/status 200
                                       :seen-profile-id (::session/profile-id req)})
                           (#'app.http.auth-request/wrap-authz cfg)
                           (#'session/wrap-authz cfg)
                           (#'mw/wrap-auth {:bearer (partial session/decode-token cfg)
                                            :cookie (partial session/decode-token cfg)}))
        seeded-token   (binding [ct/*clock* (ct/fixed-clock t0)]
                         (get-in ((session/create-fn cfg alice)
                                  (->DummyRequest {} {})
                                  {::yres/status 200})
                                 [::yres/cookies "auth-token" :value]))
        response       (binding [cf/config (assoc cf/config
                                                  :auth-token-cookie-renewal-max-age
                                                  renewal-threshold)
                                 ct/*clock* (ct/fixed-clock t1)]
                         (middleware (->DummyRequest {"x-auth-request-email" (:email bob)}
                                                     {"auth-token" seeded-token})))
        rekeyed-token  (get-in response [::yres/cookies "auth-token" :value])
        followup       (middleware (->DummyRequest {} {"auth-token" rekeyed-token}))]
    ;; Cookie was re-keyed (not just renewed): the new token differs.
    (t/is (some? rekeyed-token))
    (t/is (not= seeded-token rekeyed-token))
    ;; This request's inner handler ran as bob, not alice.
    (t/is (= (:id bob) (:seen-profile-id response)))
    ;; The re-keyed token, decoded on a follow-up request, still resolves to bob.
    ;; If renewal had clobbered the response cookie with a renewed alice token,
    ;; this would resolve to alice instead.
    (t/is (= (:id bob) (:seen-profile-id followup)))))

(t/deftest x-auth-request-skips-when-access-token-present
  (let [profile-id (random-uuid)
        handler    (#'app.http.auth-request/wrap-authz
                    (fn [req] req)
                    (make-xauth-cfg))
        request    (-> (->DummyRequest {"x-auth-request-email" "user@example.com"} {})
                       (assoc ::access-token/profile-id profile-id))
        result     (handler request)]
    (t/is (= profile-id (::access-token/profile-id result)))))

(t/deftest x-auth-request-authenticates-existing-active-profile
  (let [profile  (th/create-profile* 1 {:is-active true})
        captured (volatile! nil)
        cfg      (make-xauth-cfg)
        handler  (#'app.http.auth-request/wrap-authz
                  (fn [req] (vreset! captured req) {::yres/status 200})
                  cfg)
        response (handler (->DummyRequest {"x-auth-request-email" (:email profile)} {}))]
    ;; The profile-id must be injected into the request seen by the downstream handler
    (t/is (= (:id profile) (::session/profile-id @captured)))
    ;; A session cookie must be set on the response
    (t/is (contains? (::yres/cookies response) "auth-token"))))

(t/deftest x-auth-request-blocked-profile-returns-403
  (let [profile  (th/create-profile* 2 {:is-active true})
        _        (th/db-update! :profile {:is-blocked true} {:id (:id profile)})
        handler  (#'app.http.auth-request/wrap-authz
                  (fn [_] {::yres/status 200})
                  (make-xauth-cfg))
        response (handler (->DummyRequest {"x-auth-request-email" (:email profile)} {}))]
    (t/is (= 403 (::yres/status response)))))

(t/deftest x-auth-request-inactive-profile-returns-403
  (let [profile  (th/create-profile* 3 {:is-active false})
        handler  (#'app.http.auth-request/wrap-authz
                  (fn [_] {::yres/status 200})
                  (make-xauth-cfg))
        response (handler (->DummyRequest {"x-auth-request-email" (:email profile)} {}))]
    (t/is (= 403 (::yres/status response)))))

(t/deftest x-auth-request-unknown-email-no-autoregister
  (let [captured (volatile! nil)
        handler  (#'app.http.auth-request/wrap-authz
                  (fn [req] (vreset! captured req) {::yres/status 200})
                  (make-xauth-cfg))]
    (handler (->DummyRequest {"x-auth-request-email" "nobody@example.com"} {}))
    (t/is (nil? (::session/profile-id @captured)))))

(t/deftest x-auth-request-auto-register-creates-active-profile
  (binding [cf/flags (conj cf/flags :x-auth-request-auto-register)]
    (let [email    "newuser@example.com"
          fullname "New User"
          captured (volatile! nil)
          cfg      (make-xauth-cfg)
          handler  (#'app.http.auth-request/wrap-authz
                    (fn [req] (vreset! captured req) {::yres/status 200})
                    cfg)
          response (handler (->DummyRequest {"x-auth-request-email" email
                                             "x-auth-request-user"  fullname} {}))]
      ;; Profile must be injected into the downstream request
      (t/is (uuid? (::session/profile-id @captured)))
      ;; A session cookie must be set so the browser is authenticated
      (t/is (contains? (::yres/cookies response) "auth-token"))
      ;; The created profile must be active and match the forwarded email
      (let [profile (db/tx-run! cfg
                                (fn [{:keys [::db/conn]}]
                                  (profile/get-profile-by-email conn email)))]
        (t/is (some? profile))
        (t/is (true? (:is-active profile)))
        (t/is (= (::session/profile-id @captured) (:id profile)))))))

(t/deftest x-auth-request-auto-register-joins-named-smb-team
  (let [orig-get cf/get]
    (binding [cf/flags (conj cf/flags :x-auth-request-auto-register)
              cf/get (fn
                       ([k] (if (= k :smb-default-workspace-name)
                              "team1"
                              (orig-get k)))
                       ([k d] (if (= k :smb-default-workspace-name)
                                "team1"
                                (orig-get k d))))]
      (let [owner    (th/create-profile* 1 {:is-active true})
            ;; Matches :smb-default-workspace-name "team1" from create-team* default name.
            _        (th/create-team* 1 {:profile-id (:id owner)})
            email    "xauth-autojoin@example.com"
            captured (volatile! nil)
            cfg      (make-xauth-cfg)
            handler  (#'app.http.auth-request/wrap-authz
                      (fn [req] (vreset! captured req) {::yres/status 200})
                      cfg)
            _        (handler (->DummyRequest {"x-auth-request-email" email
                                               "x-auth-request-user"  "Shared Team Join"} {}))
            profile  (db/tx-run! cfg
                                 (fn [{:keys [::db/conn]}]
                                   (profile/get-profile-by-email conn email)))
            rels     (db/query th/*pool* :team-profile-rel {:profile-id (:id profile)})]
        (t/is (uuid? (:id profile)))
        (t/is (some #(not= (:team-id %) (:default-team-id profile)) rels)
              "profile should have a membership on the provisioned SMB (shared) team")))))
