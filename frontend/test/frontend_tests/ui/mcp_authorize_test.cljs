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

(t/deftest ^:async login-keeps-the-approval-return-url-without-navigation
  (let [href    "https://penpot.example/?screen=mcp-authorize&request=opaque-handle"
        saved   (:login-redirect storage/session)
        emitted (atom [])]
    (try
      (await
       (mock/with-mocks*
         {rt/get-current-href (mock/stub (fn [] href))
          st/emit!            (mock/stub (fn [event] (swap! emitted conj event)))}
         (let [params (#'oauth/prepare-login!)]
           (await (a/settle))
           (t/is (= href (:callback-url params)))
           (t/is (= href (:login-redirect storage/session)))
           (t/is (empty? @emitted)))))
      (finally
        (binding [storage/*sync* true]
          (if saved
            (swap! storage/session assoc :login-redirect saved)
            (swap! storage/session dissoc :login-redirect)))))))

(t/deftest ^:async cancelling-login-clears-only-this-attempts-return-url
  (let [href  "https://penpot.example/?screen=mcp-authorize&request=opaque-handle"
        saved (:login-redirect storage/session)]
    (try
      (await
       (mock/with-mocks*
         {rt/get-current-href (mock/stub (fn [] href))}
         (#'oauth/prepare-login!)
         (#'oauth/cancel-login!)
         (t/is (nil? (:login-redirect storage/session)))
         (binding [storage/*sync* true]
           (swap! storage/session assoc :login-redirect "https://penpot.example/other"))
         (#'oauth/cancel-login!)
         (t/is (= "https://penpot.example/other" (:login-redirect storage/session)))))
      (finally
        (binding [storage/*sync* true]
          (if saved
            (swap! storage/session assoc :login-redirect saved)
            (swap! storage/session dissoc :login-redirect)))))))
