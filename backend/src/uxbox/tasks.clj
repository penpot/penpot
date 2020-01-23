;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; This Source Code Form is "Incompatible With Secondary Licenses", as
;; defined by the Mozilla Public License, v. 2.0.
;;
;; Copyright (c) 2020 Andrey Antukh <niwi@niwi.nz>

(ns uxbox.tasks
  "Async tasks abstraction (impl)."
  (:require
   [clojure.spec.alpha :as s]
   [clojure.tools.logging :as log]
   [mount.core :as mount :refer [defstate]]
   [promesa.core :as p]
   [uxbox.common.spec :as us]
   [uxbox.config :as cfg]
   [uxbox.core :refer [system]]
   [uxbox.db :as db]
   [uxbox.tasks.demo-gc]
   [uxbox.tasks.sendmail]
   [uxbox.tasks.impl :as impl]
   [vertx.core :as vc]
   [vertx.timers :as vt]))

;; --- Public API

(s/def ::name ::us/string)
(s/def ::delay ::us/integer)
(s/def ::props map?)
(s/def ::task-spec
  (s/keys :req-un [::name ::delay] :opt-un [::props]))

(defn schedule!
  ([task] (schedule! db/pool task))
  ([conn task]
   (us/assert ::task-spec task)
   (impl/schedule! conn task)))

;; --- State initialization

(def ^:private tasks
  [#'uxbox.tasks.demo-gc/handler
   #'uxbox.tasks.sendmail/handler])

(defstate tasks
  :start (as-> (impl/verticle tasks) $$
           (vc/deploy! system $$ {:instances 1})
           (deref $$)))
