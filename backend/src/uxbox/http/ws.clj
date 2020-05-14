;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) 2020 UXBOX Labs SL

(ns uxbox.http.ws
  "Web Socket handlers"
  (:require
   [clojure.core.async :as a]
   [ring.middleware.cookies :refer [wrap-cookies]]
   [ring.middleware.keyword-params :refer [wrap-keyword-params]]
   [ring.middleware.params :refer [wrap-params]]
   [uxbox.http.session :refer [wrap-auth]]
   [clojure.tools.logging :as log]
   [clojure.spec.alpha :as s]
   [promesa.core :as p]
   [ring.adapter.jetty9 :as jetty]
   [uxbox.common.exceptions :as ex]
   [uxbox.common.uuid :as uuid]
   [uxbox.common.spec :as us]
   [uxbox.redis :as redis]
   [ring.util.codec :as codec]
   [uxbox.util.transit :as t]
   [uxbox.services.notifications :as nf]))

(s/def ::file-id ::us/uuid)
(s/def ::session-id ::us/uuid)
(s/def ::websocket-params
  (s/keys :req-un [::file-id ::session-id]))

(defn websocket
  [req]
  (let [params (us/conform ::websocket-params (:params req))
        params (assoc params :profile-id (:profile-id req))]
    (nf/websocket params)))

(def handler
  (-> websocket
      (wrap-auth)
      (wrap-keyword-params)
      (wrap-cookies)
      (wrap-params)))
