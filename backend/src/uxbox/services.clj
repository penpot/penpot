;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) 2016 Andrey Antukh <niwi@niwi.nz>

(ns uxbox.services
  "Main namespace for access to all uxbox services."
  (:require [suricatta.core :as sc]
            [executors.core :as exec]
            [promesa.core :as p]
            [uxbox.db :as db]
            [uxbox.services.core :as core]
            [uxbox.util.transit :as t]
            [uxbox.util.blob :as blob]))

;; Load relevant subnamespaces with the implementation
(load "services/auth")
(load "services/projects")
(load "services/pages")
(load "services/images")
(load "services/icons")
(load "services/kvstore")

;; --- Implementation

(def ^:private encode (comp blob/encode t/encode))

(defn- insert-txlog
  [data]
  (with-open [conn (db/connection)]
    (let [sql (str "INSERT INTO txlog (payload) VALUES (?)")
          sqlv [sql (encode data)]]
      (sc/execute conn sqlv))))

(defn- handle-novelty
  [data]
  (let [rs (core/novelty data)
        rs (if (p/promise? rs) rs (p/resolved rs))]
    (p/map (fn [v]
             (insert-txlog data)
             v) rs)))

(defn- handle-query
  [data]
  (let [result (core/query data)]
    (if (p/promise? result)
      result
      (p/resolved result))))

;; --- Public Api

(defn novelty
  [data]
  (->> (exec/submit (partial handle-novelty data))
       (p/mapcat identity)))

(defn query
  [data]
  (->> (exec/submit (partial handle-query data))
       (p/mapcat identity)))
