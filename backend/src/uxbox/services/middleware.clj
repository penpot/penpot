;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; This Source Code Form is "Incompatible With Secondary Licenses", as
;; defined by the Mozilla Public License, v. 2.0.
;;
;; Copyright (c) 2020 UXBOX Labs SL

(ns uxbox.services.middleware
  "Common middleware for services."
  (:require
   [clojure.tools.logging :as log]
   [clojure.spec.alpha :as s]
   [cuerdas.core :as str]
   [expound.alpha :as expound]
   [uxbox.common.exceptions :as ex]
   [uxbox.common.spec :as us]
   [uxbox.metrics :as mtx]))

(defn wrap-spec
  [handler]
  (let [mdata (meta handler)
        spec (s/get-spec (:spec mdata))]
    (if (nil? spec)
      handler
      (with-meta
        (fn [params]
          (let [result (us/conform spec params)]
            (handler result)))
        (assoc mdata ::wrap-spec true)))))

(defn wrap-error
  [handler]
  (let [mdata (meta handler)]
    (with-meta
      (fn [params]
        (try
          (handler params)
          (catch Throwable error
            (ex/raise :type :service-error
                      :name (:spec mdata)
                      :cause error))))
      (assoc mdata ::wrap-error true))))

(defn- get-prefix
  [nsname]
  (let [[a b c] (str/split nsname ".")]
    c))

(defn wrap-metrics
  [handler]
  (let [mdata  (meta handler)
        nsname (namespace (:spec mdata))
        smname (name (:spec mdata))
        prefix (get-prefix nsname)

        sname  (str prefix "/" smname)

        props  {:id (str/join "__" [prefix
                                    (str/snake smname)
                                    "response_time"])
                :help (str "Service timing measures for: " sname ".")}]
    (with-meta
      (mtx/wrap-summary handler props)
      (assoc mdata ::wrap-metrics true))))

(defn wrap
  [handler]
  (-> handler
      (wrap-spec)
      (wrap-error)
      (wrap-metrics)))
