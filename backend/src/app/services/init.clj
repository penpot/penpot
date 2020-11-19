;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; This Source Code Form is "Incompatible With Secondary Licenses", as
;; defined by the Mozilla Public License, v. 2.0.
;;
;; Copyright (c) 2020 UXBOX Labs SL

(ns app.services.init
  "A initialization of services."
  (:require
   [mount.core :as mount :refer [defstate]]))

(defn- load-query-services
  []
  (require 'app.services.queries.projects)
  (require 'app.services.queries.files)
  (require 'app.services.queries.comments)
  (require 'app.services.queries.profile)
  (require 'app.services.queries.recent-files)
  (require 'app.services.queries.viewer))

(defn- load-mutation-services
  []
  (require 'app.services.mutations.demo)
  (require 'app.services.mutations.media)
  (require 'app.services.mutations.projects)
  (require 'app.services.mutations.files)
  (require 'app.services.mutations.comments)
  (require 'app.services.mutations.profile)
  (require 'app.services.mutations.viewer)
  (require 'app.services.mutations.verify-token))

(defstate query-services
  :start (load-query-services))

(defstate mutation-services
  :start (load-mutation-services))
