;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; This Source Code Form is "Incompatible With Secondary Licenses", as
;; defined by the Mozilla Public License, v. 2.0.
;;
;; Copyright (c) 2020 Andrey Antukh <niwi@niwi.nz>

(ns uxbox.services.queries.teams
  (:require
   [clojure.spec.alpha :as s]
   [promesa.core :as p]
   [uxbox.db :as db]
   [uxbox.common.exceptions :as ex]
   [uxbox.common.spec :as us]
   [uxbox.services.queries :as sq]
   [uxbox.services.util :as su]
   [uxbox.util.blob :as blob]
   [uxbox.common.uuid :as uuid]))


;; --- Team Edition Permissions

(def ^:private sql:team-permissions
  "select tpr.is_owner,
          tpr.is_admin,
          tpr.can_edit
     from team_profile_rel as tpr
    where tpr.profile_id = $1
      and tpr.team_id = $2")

(defn check-edition-permissions!
  [conn profile-id team-id]
  (-> (db/query-one conn [sql:team-permissions profile-id team-id])
      (p/then' (fn [row]
                 (when-not (or (:can-edit row)
                               (:is-admin row)
                               (:is-owner row))
                   (ex/raise :type :validation
                             :code :not-authorized))))))

(defn check-read-permissions!
  [conn profile-id team-id]
  (-> (db/query-one conn [sql:team-permissions profile-id team-id])
      (p/then' (fn [row]
                 (when-not (or (:can-edit row)
                               (:is-admin row)
                               (:is-owner row)
                               ;; We can read global-project owned items
                               (= team-id #uuid "00000000-0000-0000-0000-000000000000"))
                   (ex/raise :type :validation
                             :code :not-authorized))))))
