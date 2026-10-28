;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS SUBSIDIARY SL

(ns app.tasks.restore-object
  "A generic task for object restore cascade handling.

  Mirrors `:delete-object`: the command stamps the object's own row
  (plus its ancestor chain up to the team) synchronously so it reads
  back instantly, and this task walks the rest of the tree with
  `deleted-at` nil. Always recursive: there is no single-level
  restore."
  (:require
   [app.common.logging :as l]
   [app.common.schema :as sm]
   [app.db :as db]
   [app.features.object-cascade :as cascade]
   [app.jobs :as jobs]
   [integrant.core :as ig]))

(defmulti restore-object
  (fn [_ props] (:object props)))

(defmethod restore-object :file
  [cfg {:keys [id]}]
  (cascade/update-cascade cfg :file id {:deleted-at nil}))

(defmethod restore-object :project
  [cfg {:keys [id]}]
  (cascade/update-cascade cfg :project id {:deleted-at nil}))

(defmethod restore-object :team
  [cfg {:keys [id]}]
  (cascade/update-cascade cfg :team id {:deleted-at nil}))

(defmethod restore-object :profile
  [cfg {:keys [id]}]
  (cascade/update-cascade cfg :profile id {:deleted-at nil}))

(defmethod restore-object :default
  [_cfg props]
  (l/wrn :obj (:object props) :hint "not implementation found"))

(def schema:restore-object-params
  [:map
   [:object [:enum :team :project :profile :file]]
   [:id ::sm/uuid]])

(defn execute-restore-object
  "Plain job handler: run the restore-object multimethod on the provided
  params inside a single transaction."
  [cfg params]
  (db/tx-run! cfg restore-object params))

(defmethod ig/assert-key ::job-def
  [_ params]
  (assert (db/pool? (::db/pool params)) "expected a valid database pool"))

(defmethod ig/init-key ::job-def
  [_ cfg]
  {::jobs/name      :restore-object
   ::jobs/schema    schema:restore-object-params
   ::jobs/handler
   (fn [_context params]
     (execute-restore-object cfg params))
   ::jobs/decoder   (sm/decoder schema:restore-object-params sm/json-transformer)
   ::jobs/validator (sm/validator schema:restore-object-params)})
