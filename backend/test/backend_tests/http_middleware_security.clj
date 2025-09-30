;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC

(ns backend-tests.http-middleware-security
  (:require
   [app.http.security :as sec]
   [clojure.test :as t]
   [yetti.request :as yreq]
   [yetti.response :as yres]))

(defn- mock-request
  [method value]
  (reify yreq/IRequest
    (method [_]
      method)
    (get-header [_ _]
      value)))

(t/deftest sec-fetch-metadata
  (let [request1 (mock-request :get "same-origin")
        request2 (mock-request :post "same-origin")
        request3 (mock-request :get "same-site")
        request4 (mock-request :post "same-site")
        request5 (mock-request :get "cross-site")
        request6 (mock-request :post "cross-site")

        handler  (fn [request]
                   {::yres/status 200})
        handler  (#'sec/wrap-sec-fetch-metadata handler)
        resp1    (handler request1)
        resp2    (handler request2)
        resp3    (handler request3)
        resp4    (handler request4)
        resp5    (handler request5)
        resp6    (handler request6)]

    (t/is (= 200 (::yres/status resp1)))
    (t/is (= 200 (::yres/status resp2)))
    (t/is (= 200 (::yres/status resp3)))
    (t/is (= 403 (::yres/status resp4)))
    (t/is (= 200 (::yres/status resp5)))
    (t/is (= 403 (::yres/status resp6)))))

(t/deftest client-header-check
  (let [request1 (mock-request :get "some")
        request2 (mock-request :post nil)

        handler  (fn [request]
                   {::yres/status 200})
        handler  (#'sec/wrap-client-header-check handler)
        resp1    (handler request1)
        resp2    (handler request2)]

    (t/is (= 200 (::yres/status resp1)))
    (t/is (= 403 (::yres/status resp2)))))

