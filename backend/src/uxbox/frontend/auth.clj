;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) 2016 Andrey Antukh <niwi@niwi.nz>

(ns uxbox.frontend.auth
  (:require [clojure.spec :as s]
            [catacumba.core :as ct]
            [catacumba.http :as http]
            [promesa.core :as p]
            [uxbox.util.spec :as us]
            [uxbox.services :as sv]
            [uxbox.util.uuid :as uuid]
            [uxbox.util.response :refer (rsp)]))

(s/def ::scope string?)
(s/def ::login (s/keys :req-un [::us/username ::us/password ::scope]))

(defn login
  [{data :data}]
  (let [data (us/conform ::login data)
        message (assoc data :type :login)]
    (->> (sv/novelty message)
         (p/map #(http/ok (rsp %))))))

;; TODO: improve authorization

(defn authorization
  [{:keys [identity] :as context}]
  (if identity
    (ct/delegate {:identity (uuid/from-string (:id identity))})
    (http/forbidden (rsp nil))))
