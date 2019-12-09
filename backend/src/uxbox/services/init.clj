;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) 2019 Andrey Antukh <niwi@niwi.nz>

(ns uxbox.services.init
  "A initialization of services."
  (:require
   [mount.core :as mount :refer [defstate]]))

(defn- load-query-services
  []
  (require 'uxbox.services.queries.icons)
  (require 'uxbox.services.queries.images)
  (require 'uxbox.services.queries.projects)
  (require 'uxbox.services.queries.project-files)
  (require 'uxbox.services.queries.project-pages)
  (require 'uxbox.services.queries.users)
  (require 'uxbox.services.queries.user-attrs))

(defn- load-mutation-services
  []
  (require 'uxbox.services.mutations.icons)
  (require 'uxbox.services.mutations.images)
  (require 'uxbox.services.mutations.projects)
  (require 'uxbox.services.mutations.project-files)
  (require 'uxbox.services.mutations.project-pages)
  (require 'uxbox.services.mutations.auth)
  (require 'uxbox.services.mutations.users)
  (require 'uxbox.services.mutations.user-attrs))

(defstate query-services
  :start (load-query-services))

(defstate mutation-services
  :start (load-mutation-services))
