;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; This Source Code Form is "Incompatible With Secondary Licenses", as
;; defined by the Mozilla Public License, v. 2.0.
;;
;; Copyright (c) 2019-2020 Andrey Antukh <niwi@niwi.nz>

(ns uxbox.services.init
  "A initialization of services."
  (:require
   [mount.core :as mount :refer [defstate]]))

(defn- load-query-services
  []
  ;; (require 'uxbox.services.queries.icons)
  (require 'uxbox.services.queries.media)
  (require 'uxbox.services.queries.colors)
  (require 'uxbox.services.queries.projects)
  (require 'uxbox.services.queries.files)
  (require 'uxbox.services.queries.pages)
  (require 'uxbox.services.queries.profile)
  (require 'uxbox.services.queries.recent-files)
  (require 'uxbox.services.queries.viewer)
  )

(defn- load-mutation-services
  []
  (require 'uxbox.services.mutations.demo)
  ;; (require 'uxbox.services.mutations.icons)
  (require 'uxbox.services.mutations.media)
  (require 'uxbox.services.mutations.colors)
  (require 'uxbox.services.mutations.projects)
  (require 'uxbox.services.mutations.files)
  (require 'uxbox.services.mutations.pages)
  (require 'uxbox.services.mutations.profile)
  )

(defstate query-services
  :start (load-query-services))

(defstate mutation-services
  :start (load-mutation-services))
