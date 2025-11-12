;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC

(ns backend-tests.http-middleware-test
  (:require
   [app.common.time :as ct]
   [app.db :as db]
   [app.http.access-token]
   [app.http.auth :as-alias auth]
   [app.http.middleware :as mw]
   [app.http.session :as session]
   [app.main :as-alias main]
   [app.rpc :as-alias rpc]
   [app.rpc.commands.access-token]
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

    (t/is (nil? (::auth/token-type @request)))
    (t/is (nil? (::auth/token @request)))

    (handler (->DummyRequest {"authorization" "Token aaaa"} {}))

    (t/is (= :token (::auth/token-type @request)))
    (t/is (= "aaaa" (::auth/token @request)))))

(t/deftest auth-middleware-2
  (let [request (volatile! nil)
        handler (#'app.http.middleware/wrap-auth
                 (fn [req] (vreset! request req))
                 {})]

    (handler (->DummyRequest {} {}))

    (t/is (nil? (::auth/token-type @request)))
    (t/is (nil? (::auth/token @request)))
    (t/is (nil? (::auth/claims @request)))

    (handler (->DummyRequest {"authorization" "Bearer aaaa"} {}))

    (t/is (= :bearer (::auth/token-type @request)))
    (t/is (= "aaaa" (::auth/token @request)))
    (t/is (nil? (::auth/claims @request)))))

(t/deftest auth-middleware-3
  (let [request (volatile! nil)
        handler (#'app.http.middleware/wrap-auth
                 (fn [req] (vreset! request req))
                 {})]

    (handler (->DummyRequest {} {}))

    (t/is (nil? (::auth/token-type @request)))
    (t/is (nil? (::auth/token @request)))
    (t/is (nil? (::auth/claims @request)))

    (handler (->DummyRequest {} {"auth-token" "foobar"}))

    (t/is (= :cookie (::auth/token-type @request)))
    (t/is (= "foobar" (::auth/token @request)))
    (t/is (nil? (::auth/claims @request)))))

(t/deftest auth-middleware-4
  (let [request (volatile! nil)
        handler (#'app.http.middleware/wrap-auth
                 (fn [req] (vreset! request req))
                 {:cookie (fn [_] "foobaz")})]

    (handler (->DummyRequest {} {}))

    (t/is (nil? (::auth/token-type @request)))
    (t/is (nil? (::auth/token @request)))
    (t/is (nil? (::auth/claims @request)))

    (handler (->DummyRequest {} {"auth-token" "foobar"}))

    (t/is (= :cookie (::auth/token-type @request)))
    (t/is (= "foobar" (::auth/token @request)))
    (t/is (delay? (::auth/claims @request)))
    (t/is (= "foobaz" (-> @request ::auth/claims deref)))))

(t/deftest shared-key-auth
  (let [handler (#'app.http.middleware/wrap-shared-key-auth
                 (fn [req] {::yres/status 200})
                 "secret-key")]

    (let [response (handler (->DummyRequest {} {}))]
      (t/is (= 403 (::yres/status response))))

    (let [response (handler (->DummyRequest {"x-shared-key" "secret-key2"} {}))]
      (t/is (= 403 (::yres/status response))))

    (let [response (handler (->DummyRequest {"x-shared-key" "secret-key"} {}))]
      (t/is (= 200 (::yres/status response))))))

(t/deftest access-token-authz
  (let [profile (th/create-profile* 1)
        token   (db/tx-run! th/*system* app.rpc.commands.access-token/create-access-token (:id profile) "test" nil)
        request (volatile! {})

        handler (#'app.http.access-token/wrap-authz
                 (fn [req] (vreset! request req))
                 th/*system*)]

    (handler nil)
    (t/is (nil? @request))

    (handler {::auth/claims (delay {:tid (:id token)})
              ::auth/token-type :token})

    (t/is (= #{} (:app.http.access-token/perms @request)))
    (t/is (= (:id profile) (:app.http.access-token/profile-id @request)))))

(t/deftest session-authz
  (let [manager (session/inmemory-manager)
        profile (th/create-profile* 1)
        handler (-> (fn [req] req)
                    (#'session/wrap-authz  {::session/manager manager})
                    (#'mw/wrap-auth {}))]


    (let [response (handler (->DummyRequest {} {"auth-token" "foobar"}))]
      (t/is (= :cookie (::auth/token-type response)))
      (t/is (= "foobar" (::auth/token response))))


    (session/write! manager "foobar" {:profile-id (:id profile)
                                      :user-agent "user agent"
                                      :created-at (ct/now)})

    (let [response (handler (->DummyRequest {} {"auth-token" "foobar"}))]
      (t/is (= :cookie (::auth/token-type response)))
      (t/is (= "foobar" (::auth/token response)))
      (t/is (= (:id profile) (::session/profile-id response)))
      (t/is (= "foobar" (::session/id response))))))
