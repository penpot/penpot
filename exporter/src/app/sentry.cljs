;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) UXBOX Labs SL

(ns app.sentry
  (:require
   ["@sentry/node" :as sentry]
   ["@sentry/tracing" :as sentry-t]
   [app.common.data :as d]
   [app.config :as cf]))

(defn init!
  []
  (when-let [dsn (cf/get :sentry-dsn)]
    (sentry/init
     #js {:dsn dsn
          :release (str "frontend@" (:base @cf/version))
          :serverName (cf/get :host)
          :environment (cf/get :tenant)
          :autoSessionTracking false
          :attachStacktrace false
          :maxBreadcrumbs 20
          :tracesSampleRate 1.0})))

(def parse-request (unchecked-get sentry/Handlers "parseRequest"))

(defn capture-exception
  [error {:keys [::request ::tags] :as context}]
  (let [context (-> (dissoc context ::request ::tags)
                    (d/without-nils))]
    (sentry/withScope
     (fn [scope]
       (.addEventProcessor ^js scope (fn [event]
                                       (let [node-request (:internal-request request)]
                                         (parse-request event node-request))))
       (doseq [[k v] tags]
         (.setTag ^js scope (if (keyword? k) (name k) (str k)) (str v)))

       (doseq [[k v] context]
         (.setContext ^js scope (if (keyword? k) (name k) (str k)) (clj->js v)))

       (sentry/captureException error)))))
