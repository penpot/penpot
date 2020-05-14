;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) 2019 Andrey Antukh <niwi@niwi.nz>

(ns uxbox.util.http
  "Http client abstraction layer."
  (:require
   [promesa.core :as p]
   [promesa.exec :as px]
   [java-http-clj.core :as http]))

(def default-client
  (delay (http/build-client {:executor @px/default-executor})))

(defn send!
  [req]
  (http/send req {:client @default-client :as :string}))
