;; ;; This Source Code Form is subject to the terms of the Mozilla Public
;; ;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; ;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;; ;;
;; ;; This Source Code Form is "Incompatible With Secondary Licenses", as
;; ;; defined by the Mozilla Public License, v. 2.0.
;; ;;
;; ;; Copyright (c) 2019 Andrey Antukh <niwi@niwi.nz>
;;
;; (ns uxbox.services.queries.icons
;;   (:require
;;    [clojure.spec.alpha :as s]
;;    [uxbox.common.exceptions :as ex]
;;    [uxbox.common.spec :as us]
;;    [uxbox.common.uuid :as uuid]
;;    [uxbox.db :as db]
;;    [uxbox.images :as images]
;;    [uxbox.media :as media]
;;    [uxbox.services.queries :as sq]
;;    [uxbox.services.queries.teams :as teams]
;;    [uxbox.util.blob :as blob]
;;    [uxbox.util.data :as data]))
;;
;; ;; --- Helpers & Specs
;;
;; (s/def ::id ::us/uuid)
;; (s/def ::name ::us/string)
;; (s/def ::profile-id ::us/uuid)
;; (s/def ::library-id ::us/uuid)
;; (s/def ::team-id ::us/uuid)
;;
;; (defn decode-row
;;   [{:keys [metadata] :as row}]
;;   (when row
;;     (cond-> row
;;       metadata (assoc :metadata (blob/decode metadata)))))
;;
;; ;; --- Query: Icons Librarys
;;
;; (def ^:private sql:libraries
;;   "select lib.*,
;;           (select count(*) from icon where library_id = lib.id) as num_icons
;;      from icon_library as lib
;;     where lib.team_id = ?
;;       and lib.deleted_at is null
;;     order by lib.created_at desc")
;;
;; (s/def ::icon-libraries
;;   (s/keys :req-un [::profile-id ::team-id]))
;;
;; (sq/defquery ::icon-libraries
;;   [{:keys [profile-id team-id]}]
;;   (db/with-atomic [conn db/pool]
;;     (teams/check-read-permissions! conn profile-id team-id)
;;     (db/exec! conn [sql:libraries team-id])))
;;
;;
;;
;; ;; --- Query: Icon Library
;;
;; (declare retrieve-library)
;;
;; (s/def ::icon-library
;;   (s/keys :req-un [::profile-id ::id]))
;;
;; (sq/defquery ::icon-library
;;   [{:keys [profile-id id]}]
;;   (db/with-atomic [conn db/pool]
;;     (let [lib (retrieve-library conn id)]
;;       (teams/check-read-permissions! conn profile-id (:team-id lib))
;;       lib)))
;;
;; (def ^:private sql:single-library
;;   "select lib.*,
;;           (select count(*) from icon where library_id = lib.id) as num_icons
;;      from icon_library as lib
;;     where lib.deleted_at is null
;;       and lib.id = ?")
;;
;; (defn- retrieve-library
;;   [conn id]
;;   (let [row (db/exec-one! conn [sql:single-library id])]
;;     (when-not row
;;       (ex/raise :type :not-found))
;;     row))
;;
;;
;;
;; ;; --- Query: Icons (by library)
;;
;; (declare retrieve-icons)
;;
;; (s/def ::icons
;;   (s/keys :req-un [::profile-id ::library-id]))
;;
;; (sq/defquery ::icons
;;   [{:keys [profile-id library-id] :as params}]
;;   (db/with-atomic [conn db/pool]
;;     (let [lib (retrieve-library conn library-id)]
;;       (teams/check-read-permissions! conn profile-id (:team-id lib))
;;       (->> (retrieve-icons conn library-id)
;;            (mapv decode-row)))))
;;
;; (def ^:private sql:icons
;;   "select icon.*
;;      from icon as icon
;;     inner join icon_library as lib on (lib.id = icon.library_id)
;;     where icon.deleted_at is null
;;       and icon.library_id = ?
;;    order by created_at desc")
;;
;; (defn- retrieve-icons
;;   [conn library-id]
;;   (db/exec! conn [sql:icons library-id]))
;;
;;
;;
;; ;; --- Query: Icon (by ID)
;;
;; (declare retrieve-icon)
;;
;; (s/def ::id ::us/uuid)
;; (s/def ::icon
;;   (s/keys :req-un [::profile-id ::id]))
;;
;; (sq/defquery ::icon
;;   [{:keys [profile-id id] :as params}]
;;   (db/with-atomic [conn db/pool]
;;     (let [icon (retrieve-icon conn id)]
;;       (teams/check-read-permissions! conn profile-id (:team-id icon))
;;       (decode-row icon))))
;;
;; (def ^:private sql:single-icon
;;   "select icon.*,
;;           lib.team_id as team_id
;;      from icon as icon
;;     inner join icon_library as lib on (lib.id = icon.library_id)
;;     where icon.deleted_at is null
;;       and icon.id = ?
;;    order by created_at desc")
;;
;; (defn retrieve-icon
;;   [conn id]
;;   (let [row (db/exec-one! conn [sql:single-icon id])]
;;     (when-not row
;;       (ex/raise :type :not-found))
;;     row))
;;
