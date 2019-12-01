;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) 2019 Andrey Antukh <niwi@niwi.nz>

(ns uxbox.http.debug
  "Debug related handlers."
  (:require
   [clojure.tools.logging :as log]
   [promesa.core :as p]
   [uxbox.http.errors :as errors]
   [uxbox.http.session :as session]
   [uxbox.util.uuid :as uuid]))

(defn emails-list
  [req]
  {:status 200
   :body "Hello world\n"})

(defn email
  [req]
  {:status 200
   :body "Hello world\n"})
