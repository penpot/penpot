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
  (require 'uxbox.services.queries.pages)
  (require 'uxbox.services.queries.profiles)
  (require 'uxbox.services.queries.projects)
  (require 'uxbox.services.queries.user-storage))

(defn- load-mutation-services
  []
  (require 'uxbox.services.mutations.auth)
  (require 'uxbox.services.mutations.icons)
  (require 'uxbox.services.mutations.images)
  (require 'uxbox.services.mutations.projects)
  (require 'uxbox.services.mutations.pages)
  (require 'uxbox.services.mutations.profiles)
  (require 'uxbox.services.mutations.user-storage))

(defstate query-services
  :start (load-query-services))

(defstate mutation-services
  :start (load-mutation-services))
