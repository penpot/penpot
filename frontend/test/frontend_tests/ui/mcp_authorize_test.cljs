;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS SUBSIDIARY SL

(ns frontend-tests.ui.mcp-authorize-test
  (:require
   [app.common.uri :as uri]
   [app.main.router :as rt]
   [app.main.store :as st]
   [app.main.ui.mcp-authorize :as oauth]
   [app.util.http :as http]
   [app.util.storage :as storage]
   [beicon.v2.core :as rx]
   [cljs.test :as t :include-macros true]
   [frontend-tests.helpers.async :as a]
   [frontend-tests.helpers.mock :as mock]))

(t/deftest ^:async approval-uses-form-encoding-and-preserves-response-keys
  (let [sent (atom nil)]
    (await
     (mock/with-mocks*
       {http/send! (mock/stub
                    (fn [request]
                      (reset! sent request)
                      (->> (rx/of {:status 200 :body #js {"redirect-uri" "https://client.example/cb?code=abc"}})
                           (rx/observe-on :async))))}
       (let [response (await (a/->promise
                              (#'oauth/oauth-request :post "decision"
                                                     {:request "opaque-handle" :csrf "a+b&c" :decision "approve"})))]
         (t/is (= :post (:method @sent)))
         (t/is (= "application/x-www-form-urlencoded" (get-in @sent [:headers "content-type"])))
         (t/is (= {:request "opaque-handle" :csrf "a+b&c" :decision "approve"}
                  (uri/query-string->map (:body @sent))))
         (t/is (= "https://client.example/cb?code=abc" (get-in response [:body :redirect-uri])))
         (t/is (nil? (get-in response [:body :access_token]))))))))

(t/deftest ^:async approval-request-preserves-login-required-response
  (await
   (mock/with-mocks*
     {http/send! (mock/stub
                  (fn [_]
                    (->> (rx/of {:status 401 :body #js {:error "login_required"}})
                         (rx/observe-on :async))))}
     (let [response (await (a/->promise (#'oauth/oauth-request :get "request" {:request "opaque-handle"})))]
       (t/is (= 401 (:status response)))
       (t/is (= "login_required" (get-in response [:body :error])))))))

(t/deftest ^:async login-keeps-the-approval-return-url
  (let [href     "https://penpot.example/?screen=mcp-authorize&request=opaque-handle"
        saved    (:login-redirect storage/session)
        emitted  (atom [])]
    (try
      (await
       (mock/with-mocks*
         {rt/get-current-href (mock/stub (fn [] href))
          st/emit!            (mock/stub (fn [event] (swap! emitted conj @event)))}
         (#'oauth/return-after-login!)
         (await (a/settle))
         (t/is (= href (:login-redirect storage/session)))
         (t/is (= :auth-login (:id (first @emitted))))
         ;; The login form clears a stored redirect unless its route supplies
         ;; callback-url. Round-trip the real navigation through the router.
         (let [{:keys [id params]} (first @emitted)
               token              (rt/resolve #{:auth-login} id params)
               route              (rt/match #{:auth-login} token)]
           (t/is (= href (get-in route [:query-params :callback-url]))))))
      (finally
        (binding [storage/*sync* true]
          (if saved
            (swap! storage/session assoc :login-redirect saved)
            (swap! storage/session dissoc :login-redirect)))))))
