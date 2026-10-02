;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS SUBSIDIARY SL

(ns backend-tests.http-admin-test
  "Tests for the `/admin` static file serving."
  (:require
   [app.http.admin :as admin]
   [backend-tests.helpers :as th]
   [clojure.test :as t]
   [cuerdas.core :as str]
   [yetti.response :as-alias yres]))

(t/use-fixtures :once th/state-init)
(t/use-fixtures :each th/database-reset)

(defn- run-handler
  [path]
  (admin/admin-handler {} {:path-params (when (some? path) {:path path})}))

(defn- content-type
  [response]
  (get (::yres/headers response) "content-type"))

(t/deftest root-serves-index
  ;; NOTE: the `""` route answers with a redirect to `/admin/` (see
  ;; below); this is the handler behind `"/"` and `/*path`.
  (let [response (run-handler nil)]
    (t/is (= 200 (::yres/status response)))
    (t/is (= "text/html" (content-type response)))
    (t/is (str/includes? (::yres/body response) "id=\"app\""))))

(t/deftest bare-path-redirects-to-trailing-slash
  ;; Without the slash, relative assets in index.html resolve against
  ;; `/` and the panel loads unstyled. The relative target keeps
  ;; subpath deployments working.
  (let [response (admin/admin-redirect-handler {} {})]
    (t/is (= 302 (::yres/status response)))
    (t/is (= "admin/" (get (::yres/headers response) "location")))))

(t/deftest unknown-subpath-is-not-found
  ;; SPA state travels in the query string: no subpath page exists.
  (let [response (run-handler "error-reports/some-id")]
    (t/is (= 404 (::yres/status response)))))

(t/deftest traversal-attempt-is-not-found
  (let [response (run-handler "../../config")]
    (t/is (= 404 (::yres/status response)))))

(t/deftest missing-file-is-not-found
  (let [response (run-handler "js/does-not-exist.js")]
    (t/is (= 404 (::yres/status response)))))

(t/deftest content-type-mapping
  (t/is (= "text/html" (#'admin/content-type-for "index.html")))
  (t/is (= "text/javascript" (#'admin/content-type-for "js/main.js")))
  (t/is (= "text/css" (#'admin/content-type-for "css/admin.css")))
  (t/is (nil? (#'admin/content-type-for "no-extension"))))
