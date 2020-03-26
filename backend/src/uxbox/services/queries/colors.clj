;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; This Source Code Form is "Incompatible With Secondary Licenses", as
;; defined by the Mozilla Public License, v. 2.0.
;;
;; Copyright (c) 2019 Andrey Antukh <niwi@niwi.nz>

(ns uxbox.services.queries.colors
  (:require
   [clojure.spec.alpha :as s]
   [promesa.core :as p]
   [promesa.exec :as px]
   [uxbox.common.exceptions :as ex]
   [uxbox.common.spec :as us]
   [uxbox.db :as db]
   [uxbox.media :as media]
   [uxbox.images :as images]
   [uxbox.services.queries.teams :as teams]
   [uxbox.services.queries :as sq]
   [uxbox.services.util :as su]
   [uxbox.util.blob :as blob]
   [uxbox.util.data :as data]
   [uxbox.util.uuid :as uuid]
   [vertx.core :as vc]))

;; --- Helpers & Specs

(s/def ::id ::us/uuid)
(s/def ::profile-id ::us/uuid)
(s/def ::team-id ::us/uuid)
(s/def ::library-id (s/nilable ::us/uuid))



;; --- Query: Colors Librarys

(def ^:private sql:libraries
  "select lib.*,
          (select count(*) from color where library_id = lib.id) as num_colors
     from color_library as lib
    where lib.team_id = $1
      and lib.deleted_at is null
    order by lib.created_at desc")

(s/def ::color-libraries
  (s/keys :req-un [::profile-id ::team-id]))

(sq/defquery ::color-libraries
  [{:keys [profile-id team-id]}]
  (db/with-atomic [conn db/pool]
    (teams/check-read-permissions! conn profile-id team-id)
    (db/query conn [sql:libraries team-id])))



;; --- Query: Color Library

(declare retrieve-library)

(s/def ::color-library
  (s/keys :req-un [::profile-id ::id]))

(sq/defquery ::color-library
  [{:keys [profile-id id]}]
  (db/with-atomic [conn db/pool]
    (p/let [lib (retrieve-library conn id)]
      (teams/check-read-permissions! conn profile-id (:team-id lib))
      lib)))

(def ^:private sql:single-library
  "select lib.*,
          (select count(*) from color where library_id = lib.id) as num_colors
     from color_library as lib
    where lib.deleted_at is null
      and lib.id = $1")

(defn- retrieve-library
  [conn id]
  (-> (db/query-one conn [sql:single-library id])
      (p/then' su/raise-not-found-if-nil)))



;; --- Query: Colors (by library)

(declare retrieve-colors)

(s/def ::colors
  (s/keys :req-un [::profile-id ::library-id]))

(sq/defquery ::colors
  [{:keys [profile-id library-id] :as params}]
  (db/with-atomic [conn db/pool]
    (p/let [lib (retrieve-library conn library-id)]
      (teams/check-read-permissions! conn profile-id (:team-id lib))
      (retrieve-colors conn library-id))))

(def ^:private sql:colors
  "select color.*
     from color as color
    inner join color_library as lib on (lib.id = color.library_id)
    where color.deleted_at is null
      and color.library_id = $1
   order by created_at desc")

(defn- retrieve-colors
  [conn library-id]
  (db/query conn [sql:colors library-id]))



;; --- Query: Color (by ID)

(declare retrieve-color)

(s/def ::id ::us/uuid)
(s/def ::color
  (s/keys :req-un [::profile-id ::id]))

(sq/defquery ::color
  [{:keys [profile-id id] :as params}]
  (db/with-atomic [conn db/pool]
    (p/let [color (retrieve-color conn id)]
      (teams/check-read-permissions! conn profile-id (:team-id color))
      color)))

(def ^:private sql:single-color
  "select color.*,
          lib.team_id as team_id
     from color as color
    inner join color_library as lib on (lib.id = color.library_id)
    where color.deleted_at is null
      and color.id = $1
   order by created_at desc")

(defn retrieve-color
  [conn id]
  (-> (db/query-one conn [sql:single-color id])
      (p/then' su/raise-not-found-if-nil)))
