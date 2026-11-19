;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS SUBSIDIARY SL

(ns backend-tests.http-sse-test
  (:require
   [app.config :as cf]
   [app.http.middleware :as mw]
   [app.http.sse :as sse]
   [clojure.test :as t]
   [cuerdas.core :as str]
   [yetti.response :as yres])
  (:import
   java.io.ByteArrayOutputStream))

(defn- run-stream
  "Build an SSE response under `base`, then run its body after the
  request scope has ended."
  [base]
  (let [seen     (atom nil)
        handler  (fn []
                   (reset! seen (str (cf/get :public-uri)))
                   {::result true})
        endpoint (sse/response handler)
        response (if base
                   (binding [cf/config (cf/with-public-uri base cf/config)]
                     (endpoint {}))
                   (endpoint {}))
        body     (::yres/body response)]
    (with-redefs [cf/config (assoc cf/config :public-uri "http://localhost:3449")]
      (yres/write-body-to-stream body response (ByteArrayOutputStream.)))
    @seen))

(t/deftest stream-uses-the-origin-of-the-request
  (t/is (= "https://alt.example.com"
           (run-stream "https://alt.example.com"))))

(t/deftest stream-keeps-the-canonical-origin-without-a-trusted-base
  (t/is (= "http://localhost:3449" (run-stream nil))))

(t/deftest stream-writes-the-alternate-public-subpath-after-the-request
  (let [public-config {:public-uri "http://localhost:3449/penpot/"
                       :trusted-origins #{"https://alt.example.com"}}
        endpoint      (mw/wrap-trusted-origin
                       (sse/response #(str (cf/get-public-uri "download"))))
        response      (binding [cf/config public-config]
                        (endpoint {:headers {"x-penpot-origin" "https://alt.example.com"}}))
        output        (ByteArrayOutputStream.)]
    (binding [cf/config public-config]
      (yres/write-body-to-stream (::yres/body response) response output))
    (let [body (.toString output "UTF-8")]
      (t/is (str/includes? body "event: end\n"))
      (t/is (str/includes? body "https://alt.example.com/penpot/download")))))
