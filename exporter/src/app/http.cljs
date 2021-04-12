;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) UXBOX Labs SL

(ns app.http
  (:require
   [app.http.export :refer [export-handler]]
   [app.http.thumbnail :refer [thumbnail-handler]]
   [app.http.impl :as impl]
   [lambdaisland.glogi :as log]
   [promesa.core :as p]
   [reitit.core :as r]))

(def routes
  [["/export/thumbnail" {:handler thumbnail-handler}]
   ["/export" {:handler export-handler}]])

(defn start!
  [extra]
  (log/info :msg "starting http server" :port 6061)
  (let [router  (r/router routes)
        handler (impl/handler router extra)
        server  (impl/server handler)]
    (.listen server 6061)
    (p/resolved server)))

(defn stop!
  [server]
  (p/create (fn [resolve]
              (.close server (fn []
                               (log/info :msg "shutdown http server")
                               (resolve))))))
