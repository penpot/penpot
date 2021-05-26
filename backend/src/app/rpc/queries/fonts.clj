;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) UXBOX Labs SL

(ns app.rpc.queries.fonts
  (:require
   [app.common.spec :as us]
   [app.db :as db]
   [app.rpc.queries.teams :as teams]
   [app.util.services :as sv]
   [clojure.spec.alpha :as s]))

;; --- Query: Team Font Variants

(s/def ::team-id ::us/uuid)
(s/def ::profile-id ::us/uuid)
(s/def ::team-font-variants
  (s/keys :req-un [::profile-id ::team-id]))

(sv/defmethod ::team-font-variants
  [{:keys [pool] :as cfg} {:keys [profile-id team-id] :as params}]
  (with-open [conn (db/open pool)]
    (teams/check-read-permissions! conn profile-id team-id)
    (db/query conn :team-font-variant
              {:team-id team-id
               :deleted-at nil})))

