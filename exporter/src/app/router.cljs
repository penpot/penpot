;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC Sucursal en España SL

(ns app.router
  "Method + path dispatch.

  Requests arrive with whatever prefix the proxy in front uses (`/api/export`
  in devenv, `/` when talking to the process directly), so routes are matched
  on the remainder after that prefix."
  (:require
   [app.common.exceptions :as ex]
   [app.handlers.jobs :as jobs.handlers]
   [cuerdas.core :as str]))

(def ^:private mount-point "/api/export")

(defn- route-path
  [path]
  (let [path (or path "/")
        path (if (str/starts-with? path mount-point)
               (subs path (count mount-point))
               path)
        path (str/rtrim path "/")]
    (if (str/empty? path) "/" path)))

(defn- job-id
  [path prefix]
  (let [id (subs path (count prefix))]
    (when-not (or (str/empty? id) (str/includes? id "/"))
      id)))

(defn create
  "Builds the request handler. `legacy-handler` serves the original
  `POST /api/export` command multiplex."
  [legacy-handler]
  (fn [{:keys [:request/method :request/path] :as exchange}]
    (let [path (route-path path)]
      (cond
        (and (= "post" method) (= "/" path))
        (legacy-handler exchange)

        (and (= "post" method) (= "/jobs" path))
        (jobs.handlers/create exchange)

        (and (= "get" method) (str/starts-with? path "/jobs/"))
        (if-let [id (job-id path "/jobs/")]
          (jobs.handlers/fetch exchange id)
          (ex/raise :type :not-found :code :object-not-found :hint "unknown route"))

        (and (= "delete" method) (str/starts-with? path "/jobs/"))
        (if-let [id (job-id path "/jobs/")]
          (jobs.handlers/cancel exchange id)
          (ex/raise :type :not-found :code :object-not-found :hint "unknown route"))

        :else
        (ex/raise :type :not-found
                  :code :route-not-found
                  :hint (str "no route for " method " " path))))))
