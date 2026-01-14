;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC

(ns backend-tests.http-middleware-test
  (:require
   [app.common.time :as ct]
   [app.db :as db]
   [app.http :as-alias http]
   [app.http.access-token]
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
