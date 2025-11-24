;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC

(ns app.rpc.commands.files-thumbnails
  (:require
   [app.binfile.common :as bfc]
   [app.common.data :as d]
   [app.common.data.macros :as dm]
   [app.common.features :as cfeat]
   [app.common.files.helpers :as cfh]
   [app.common.geom.shapes :as gsh]
   [app.common.schema :as sm]
   [app.common.thumbnails :as thc]
   [app.common.time :as ct]
   [app.common.types.shape-tree :as ctt]
   [app.config :as cf]
   [app.db :as db]
   [app.db.sql :as-alias sql]
   [app.loggers.audit :as-alias audit]
   [app.loggers.webhooks :as-alias webhooks]
   [app.media :as media]
   [app.rpc :as-alias rpc]
   [app.rpc.climit :as-alias climit]
   [app.rpc.commands.files :as files]
   [app.rpc.commands.teams :as teams]
   [app.rpc.cond :as-alias cond]
   [app.rpc.doc :as-alias doc]
   [app.rpc.retry :as rtry]
   [app.storage :as sto]
   [app.util.pointer-map :as pmap]
   [app.util.services :as sv]
   [cuerdas.core :as str]))

;; --- FEATURES

(def long-cache-duration
  (ct/duration {:days 7}))

;; --- COMMAND QUERY: get-file-object-thumbnails

(defn- get-object-thumbnails-by-tag
  [conn file-id tag]
  (let [sql (str/concat
             "select object_id, media_id, tag "
             "  from file_tagged_object_thumbnail"
             " where file_id=? and tag=? and deleted_at is null")
        res (db/exec! conn [sql file-id tag])]
    (->> res
         (d/index-by :object-id :media-id)
         (d/without-nils))))

(defn- get-object-thumbnails
  ([conn file-id]
   (let [sql (str/concat
              "select object_id, media_id, tag "
              "  from file_tagged_object_thumbnail"
              " where file_id=? and deleted_at is null")
         res (db/exec! conn [sql file-id])]
     (->> res
          (d/index-by :object-id :media-id)
          (d/without-nils))))

  ([conn file-id object-ids]
   (let [sql (str/concat
              "select object_id, media_id, tag "
              "  from file_tagged_object_thumbnail"
              " where file_id=? and object_id = ANY(?) and deleted_at is null")
         ids (db/create-array conn "text" (seq object-ids))
         res (db/exec! conn [sql file-id ids])]

     (->> res
          (d/index-by :object-id :media-id)
          (d/without-nils)))))

(sv/defmethod ::get-file-object-thumbnails
  "Retrieve a file object thumbnails."
  {::doc/added "1.17"
   ::doc/module :files
   ::sm/params [:map {:title "get-file-object-thumbnails"}
                [:file-id ::sm/uuid]
                [:tag {:optional true} [:string {:max 50}]]]
   ::sm/result [:map-of [:string {:max 250}] [:string {:max 250}]]}
  [{:keys [::db/pool] :as cfg} {:keys [::rpc/profile-id file-id tag] :as params}]
  (dm/with-open [conn (db/open pool)]
    (files/check-read-permissions! conn profile-id file-id)
    (if tag
      (get-object-thumbnails-by-tag conn file-id tag)
      (get-object-thumbnails conn file-id))))

;; --- COMMAND QUERY: get-file-data-for-thumbnail

;; We need to improve how we set frame for thumbnail in order to avoid
;; loading all pages into memory for find the frame set for thumbnail.

(defn get-file-data-for-thumbnail
  [{:keys [::db/conn] :as cfg} {:keys [data id] :as file} strip-frames-with-thumbnails]
  (letfn [;; function responsible on finding the frame marked to be
          ;; used as thumbnail; the returned frame always have
          ;; the :page-id set to the page that it belongs.
          (get-thumbnail-frame [{:keys [data]}]
            (d/seek #(or (:use-for-thumbnail %)
                         (:use-for-thumbnail? %)) ; NOTE: backward comp (remove on v1.21)
                    (for [page  (-> data :pages-index vals)
                          frame (-> page :objects ctt/get-frames)]
                      (assoc frame :page-id (:id page)))))

          ;; function responsible to filter objects data structure of
          ;; all unneeded shapes if a concrete frame is provided. If no
          ;; frame, the objects is returned untouched.
          (filter-objects [objects frame-id]
            (d/index-by :id (cfh/get-children-with-self objects frame-id)))

          ;; function responsible of assoc available thumbnails
          ;; to frames and remove all children shapes from objects if
          ;; thumbnails is available
          (assoc-thumbnails [objects page-id thumbnails]
            (loop [objects objects
                   frames  (filter cfh/frame-shape? (vals objects))]

              (if-let [frame  (-> frames first)]
                (let [frame-id  (:id frame)
                      object-id (thc/fmt-object-id (:id file) page-id frame-id "frame")

                      frame     (if-let [media-id (get thumbnails object-id)]
                                  (-> frame
                                      (assoc :thumbnail-id media-id)
                                      (assoc :shapes []))
                                  (dissoc frame :thumbnail))

                      children-ids
                      (cfh/get-children-ids objects frame-id)

                      bounds
                      (when (:show-content frame)
                        (gsh/shapes->rect (cons frame (map (d/getf objects) children-ids))))

                      frame
                      (cond-> frame
                        (some? bounds)
                        (assoc :children-bounds bounds))]

                  (if (:thumbnail frame)
                    (recur (-> objects
                               (assoc frame-id frame)
                               (d/without-keys children-ids))
                           (rest frames))
                    (recur (assoc objects frame-id frame)
                           (rest frames))))

                objects)))]

    (let [frame     (get-thumbnail-frame file)
          frame-id  (:id frame)
          page-id   (or (:page-id frame)
                        (-> data :pages first))

          page      (dm/get-in data [:pages-index page-id])
          page      (cond-> page (pmap/pointer-map? page) deref)
          frame-ids (if (some? frame) (list frame-id) (map :id (ctt/get-frames (:objects page))))

          obj-ids   (map #(thc/fmt-object-id (:id file) page-id % "frame") frame-ids)
          thumbs    (get-object-thumbnails conn id obj-ids)]

      (cond-> page
        ;; If we have frame, we need to specify it on the page level
        ;; and remove the all other unrelated objects.
        (some? frame-id)
        (-> (assoc :thumbnail-frame-id frame-id)
            (update :objects filter-objects frame-id))

        ;; Assoc the available thumbnails and prune not visible shapes
        ;; for avoid transfer unnecessary data.
        strip-frames-with-thumbnails
        (update :objects assoc-thumbnails page-id thumbs)))))

(def ^:private
  schema:get-file-data-for-thumbnail
  [:map {:title "get-file-data-for-thumbnail"}
   [:file-id ::sm/uuid]])

(def ^:private
  schema:partial-file
  [:map {:title "PartialFile"}
   [:id ::sm/uuid]
   [:revn {:min 0} ::sm/int]
   [:page [:map-of :keyword ::sm/any]]
   [:strip-frames-with-thumbnails {:optional true} ::sm/boolean]])

(sv/defmethod ::get-file-data-for-thumbnail
  "Retrieves the data for generate the thumbnail of the file. Used
  mainly for render thumbnails on dashboard."
  {::doc/added "1.17"
   ::doc/module :files
   ::sm/params schema:get-file-data-for-thumbnail
   ::sm/result schema:partial-file}
  [cfg {:keys [::rpc/profile-id file-id strip-frames-with-thumbnails] :as params}]
  (db/run! cfg (fn [{:keys [::db/conn] :as cfg}]
                 (files/check-read-permissions! conn profile-id file-id)

                 (let [team (teams/get-team conn
                                            :profile-id profile-id
                                            :file-id file-id)

                       file (bfc/get-file cfg file-id
                                          :realize? true
                                          :read-only? true)

                       strip-frames-with-thumbnails
                       (or (nil? strip-frames-with-thumbnails) ;; if not present, default to true
                           (true? strip-frames-with-thumbnails))]

                   (-> (cfeat/get-team-enabled-features cf/flags team)
                       (cfeat/check-file-features! (:features file)))

                   {:file-id file-id
                    :revn (:revn file)
                    :page (get-file-data-for-thumbnail cfg file strip-frames-with-thumbnails)}))))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; MUTATION COMMANDS
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(def sql:get-file-object-thumbnail
  "SELECT * FROM file_tagged_object_thumbnail
    WHERE file_id = ? AND object_id = ? AND tag = ?
      FOR UPDATE")

(def sql:create-file-object-thumbnail
  "INSERT INTO file_tagged_object_thumbnail (file_id, object_id, tag, media_id)
   VALUES (?, ?, ?, ?)
       ON CONFLICT (file_id, object_id, tag)
       DO UPDATE SET updated_at=?, media_id=?, deleted_at=?
   RETURNING *")

(defn- persist-thumbnail!
  [storage media created-at]
  (let [path  (:path media)
        mtype (:mtype media)
        hash  (sto/calculate-hash path)
        data  (-> (sto/content path)
                  (sto/wrap-with-hash hash))]

    (sto/put-object! storage
                     {::sto/content data
                      ::sto/deduplicate? true
                      ::sto/touched-at created-at
                      :content-type mtype
                      :bucket "file-object-thumbnail"})))

(defn- create-file-object-thumbnail!
  [{:keys [::sto/storage] :as cfg} file object-id media tag]
  (let [file-id   (:id file)
        timestamp (ct/now)
        media     (persist-thumbnail! storage media timestamp)
        [th1 th2] (db/tx-run! cfg (fn [{:keys [::db/conn]}]
                                    (let [th1 (db/exec-one! conn [sql:get-file-object-thumbnail file-id object-id tag])
                                          th2 (db/exec-one! conn [sql:create-file-object-thumbnail
                                                                  file-id object-id tag
                                                                  (:id media)
                                                                  timestamp
                                                                  (:id media)
                                                                  (:deleted-at file)])]
                                      [th1 th2])))]

    (when (and (some? th1)
               (not= (:media-id th1)
                     (:media-id th2)))
      (sto/touch-object! storage (:media-id th1)))

    th2))

(def ^:private
  schema:create-file-object-thumbnail
  [:map {:title "create-file-object-thumbnail"}
   [:file-id ::sm/uuid]
   [:object-id [:string {:max 250}]]
   [:media media/schema:upload]
   [:tag {:optional true} [:string {:max 50}]]])

(sv/defmethod ::create-file-object-thumbnail
  {::doc/added "1.19"
   ::doc/module :files
   ::climit/id [[:file-thumbnail-ops/by-profile ::rpc/profile-id]
                [:file-thumbnail-ops/global]]
   ::rtry/enabled true
   ::rtry/when rtry/conflict-exception?
   ::audit/skip true
   ::sm/params schema:create-file-object-thumbnail}

  [cfg {:keys [::rpc/profile-id file-id object-id media tag]}]
  (media/validate-media-type! media)
  (media/validate-media-size! media)

  (db/run! cfg files/check-edition-permissions! profile-id file-id)
  (when-let [file (files/get-minimal-file cfg file-id {::db/check-deleted false})]
    (create-file-object-thumbnail! cfg file object-id media (or tag "frame"))))

;; --- MUTATION COMMAND: delete-file-object-thumbnail

(defn- delete-file-object-thumbnail!
  [{:keys [::db/conn ::sto/storage]} file-id object-id]
  (when-let [{:keys [media-id tag]} (db/get* conn :file-tagged-object-thumbnail
                                             {:file-id file-id
                                              :object-id object-id}
                                             {::sql/for-update true})]
    (sto/touch-object! storage media-id)
    (db/update! conn :file-tagged-object-thumbnail
                {:deleted-at (ct/now)}
                {:file-id file-id
                 :object-id object-id
                 :tag tag})))

(def ^:private schema:delete-file-object-thumbnail
  [:map {:title "delete-file-object-thumbnail"}
   [:file-id ::sm/uuid]
   [:object-id [:string {:max 250}]]])

(sv/defmethod ::delete-file-object-thumbnail
  {::doc/added "1.19"
   ::doc/module :files
   ::sm/params schema:delete-file-object-thumbnail
   ::audit/skip true}
  [cfg {:keys [::rpc/profile-id file-id object-id]}]
  (files/check-edition-permissions! cfg profile-id file-id)
  (db/tx-run! cfg (fn [{:keys [::db/conn] :as cfg}]
                    (-> cfg
                        (update ::sto/storage sto/configure conn)
                        (delete-file-object-thumbnail! file-id object-id))
                    nil)))

;; --- MUTATION COMMAND: create-file-thumbnail

(defn- create-file-thumbnail!
  [{:keys [::db/conn ::sto/storage]} {:keys [file-id revn props media] :as params}]
  (media/validate-media-type! media)
  (media/validate-media-size! media)

  (let [props (db/tjson (or props {}))
        path  (:path media)
        mtype (:mtype media)
        hash  (sto/calculate-hash path)
        data  (-> (sto/content path)
                  (sto/wrap-with-hash hash))
        tnow  (ct/now)

        media (sto/put-object! storage
                               {::sto/content data
                                ::sto/deduplicate? true
                                ::sto/touched-at tnow
                                :content-type mtype
                                :bucket "file-thumbnail"})

        thumb (db/get* conn :file-thumbnail
                       {:file-id file-id
                        :revn revn}
                       {::db/remove-deleted false
                        ::sql/for-update true})]

    (if (some? thumb)
      (do
        ;; We mark the old media id as touched if it does not match
        (when (not= (:id media) (:media-id thumb))
          (sto/touch-object! storage (:media-id thumb)))

        (db/update! conn :file-thumbnail
                    {:media-id (:id media)
                     :deleted-at nil
                     :updated-at tnow
                     :props props}
                    {:file-id file-id
                     :revn revn}))

      (db/insert! conn :file-thumbnail
                  {:file-id file-id
                   :revn revn
                   :created-at tnow
                   :updated-at tnow
                   :props props
                   :media-id (:id media)}))

    media))

(def ^:private
  schema:create-file-thumbnail
  [:map {:title "create-file-thumbnail"}
   [:file-id ::sm/uuid]
   [:revn ::sm/int]
   [:media media/schema:upload]])

(sv/defmethod ::create-file-thumbnail
  "Creates or updates the file thumbnail. Mainly used for paint the
  grid thumbnails."
  {::doc/added "1.19"
   ::doc/module :files
   ::audit/skip true
   ::climit/id [[:file-thumbnail-ops/by-profile ::rpc/profile-id]
                [:file-thumbnail-ops/global]]
   ::rtry/enabled true
   ::rtry/when rtry/conflict-exception?
   ::sm/params schema:create-file-thumbnail}

  [cfg {:keys [::rpc/profile-id file-id] :as params}]
  (db/tx-run! cfg (fn [{:keys [::db/conn] :as cfg}]
                    ;; TODO For now we check read permissions instead of write,
                    ;; to allow viewer users to update thumbnails. We might
                    ;; review this approach on the future.
                    (files/check-read-permissions! conn profile-id file-id)
                    (when-not (db/read-only? conn)
                      (let [media (create-file-thumbnail! cfg params)]
                        {:uri (files/resolve-public-uri (:id media))
                         :id (:id media)})))))
