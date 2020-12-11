;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; This Source Code Form is "Incompatible With Secondary Licenses", as
;; defined by the Mozilla Public License, v. 2.0.
;;
;; Copyright (c) 2020 UXBOX Labs SL

(ns app.util.http
  "Http client abstraction layer."
  (:require
   [java-http-clj.core :as http]
   [promesa.exec :as px]))

(def default-client
  (delay (http/build-client {:executor @px/default-executor})))

(defn get!
  [url opts]
  (let [opts' (merge {:client @default-client :as :string} opts)]
    (http/get url nil opts')))

(defn send!
  ([req]
   (http/send req {:client @default-client :as :string}))
  ([req opts]
   (http/send req (merge {:client @default-client :as :string} opts))))
