;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC

(ns app.rpc.commands.files-thumbnails
  (:require
   [app.common.data :as d]
   [app.common.data.macros :as dm]
   [app.common.exceptions :as ex]
   [app.common.geom.shapes :as gsh]
   [app.common.pages.helpers :as cph]
   [app.common.spec :as us]
   [app.common.types.shape-tree :as ctt]
   [app.config :as cf]
   [app.db :as db]
   [app.db.sql :as sql]
   [app.loggers.audit :as-alias audit]
   [app.loggers.webhooks :as-alias webhooks]
   [app.media :as media]
   [app.rpc :as-alias rpc]
   [app.rpc.commands.files :as files]
   [app.rpc.cond :as-alias cond]
   [app.rpc.doc :as-alias doc]
   [app.rpc.helpers :as rph]
   [app.storage :as sto]
   [app.util.pointer-map :as pmap]
   [app.util.services :as sv]
   [app.util.time :as dt]
   [clojure.spec.alpha :as s]
   [cuerdas.core :as str]))

;; --- FEATURES

(def long-cache-duration
  (dt/duration {:days 7}))

;; --- COMMAND QUERY: get-file-object-thumbnails

(defn- get-public-uri
  [media-id]
  (str (cf/get :public-uri) "/assets/by-id/" media-id))

(defn- get-object-thumbnails
  ([conn file-id]
   (let [sql (str/concat
              "select object_id, data, media_id "
              "  from file_object_thumbnail"
              " where file_id=?")]
     (->> (db/exec! conn [sql file-id])
          (d/index-by :object-id (fn [row]
                                   (or (some-> row :media-id get-public-uri)
                                       (:data row))))
          (d/without-nils))))

  ([conn file-id object-ids]
   (let [sql (str/concat
              "select object_id, data "
              "  from file_object_thumbnail"
              " where file_id=? and object_id = ANY(?)")
         ids (db/create-array conn "text" (seq object-ids))]
     (->> (db/exec! conn [sql file-id ids])
          (d/index-by :object-id (fn [row]
                                   (or (some-> row :media-id get-public-uri)
                                       (:data row))))))))

(s/def ::file-id ::us/uuid)
(s/def ::get-file-object-thumbnails
  (s/keys :req [::rpc/profile-id] :req-un [::file-id]))

(sv/defmethod ::get-file-object-thumbnails
  "Retrieve a file object thumbnails."
  {::doc/added "1.17"
   ::cond/get-object #(files/get-minimal-file %1 (:file-id %2))
   ::cond/reuse-key? true
   ::cond/key-fn files/get-file-etag}
  [{:keys [::db/pool] :as cfg} {:keys [::rpc/profile-id file-id] :as params}]
  (dm/with-open [conn (db/open pool)]
    (files/check-read-permissions! conn profile-id file-id)
    (get-object-thumbnails conn file-id)))

;; --- COMMAND QUERY: get-file-thumbnail

;; FIXME: refactor to support uploading data to storage

(defn get-file-thumbnail
  [conn file-id revn]
  (let [sql (sql/select :file-thumbnail
                        (cond-> {:file-id file-id}
                          revn (assoc :revn revn))
                        {:limit 1
                         :order-by [[:revn :desc]]})
        row (db/exec-one! conn sql)]
    (when-not row
      (ex/raise :type :not-found
                :code :file-thumbnail-not-found))

    {:data (:data row)
     :props (some-> (:props row) db/decode-transit-pgobject)
     :revn (:revn row)
     :file-id (:file-id row)}))

(s/def ::revn ::us/integer)

(s/def ::get-file-thumbnail
  (s/keys :req [::rpc/profile-id]
          :req-un [::file-id]
          :opt-un [::revn]))

(sv/defmethod ::get-file-thumbnail
  "Method used in frontend for obtain the file thumbnail (used in the
  dashboard)."
  {::doc/added "1.17"}
  [{:keys [::db/pool]} {:keys [::rpc/profile-id file-id revn]}]
  (dm/with-open [conn (db/open pool)]
    (files/check-read-permissions! conn profile-id file-id)
    (-> (get-file-thumbnail conn file-id revn)
        (rph/with-http-cache long-cache-duration))))


;; --- COMMAND QUERY: get-file-data-for-thumbnail

;; FIXME: performance issue, handle new media_id
;;
;; We need to improve how we set frame for thumbnail in order to avoid
;; loading all pages into memory for find the frame set for thumbnail.

(defn get-file-data-for-thumbnail
  [conn {:keys [data id] :as file}]
  (letfn [;; function responsible on finding the frame marked to be
          ;; used as thumbnail; the returned frame always have
          ;; the :page-id set to the page that it belongs.

          (get-thumbnail-frame [data]
            ;; NOTE: this is a hack for avoid perform blocking
            ;; operation inside the for loop, clojure lazy-seq uses
            ;; synchronized blocks that does not plays well with
            ;; virtual threads, so we need to perform the load
            ;; operation first. This operation forces all pointer maps
            ;; load into the memory.
            (->> (-> data :pages-index vals)
                 (filter pmap/pointer-map?)
                 (run! pmap/load!))

            ;; Then proceed to find the frame set for thumbnail

            (d/seek :use-for-thumbnail?
                    (for [page  (-> data :pages-index vals)
                          frame (-> page :objects ctt/get-frames)]
                      (assoc frame :page-id (:id page)))))

          ;; function responsible to filter objects data structure of
          ;; all unneeded shapes if a concrete frame is provided. If no
          ;; frame, the objects is returned untouched.
          (filter-objects [objects frame-id]
            (d/index-by :id (cph/get-children-with-self objects frame-id)))

          ;; function responsible of assoc available thumbnails
          ;; to frames and remove all children shapes from objects if
          ;; thumbnails is available
          (assoc-thumbnails [objects page-id thumbnails]
            (loop [objects objects
                   frames  (filter cph/frame-shape? (vals objects))]

              (if-let [frame  (-> frames first)]
                (let [frame-id (:id frame)
                      object-id (str page-id frame-id)
                      frame (if-let [thumb (get thumbnails object-id)]
                              (assoc frame :thumbnail thumb :shapes [])
                              (dissoc frame :thumbnail))

                      children-ids
                      (cph/get-children-ids objects frame-id)

                      bounds
                      (when (:show-content frame)
                        (gsh/selection-rect (concat [frame] (->> children-ids (map (d/getf objects))))))

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

    (binding [pmap/*load-fn* (partial files/load-pointer conn id)]
      (let [frame     (get-thumbnail-frame data)
            frame-id  (:id frame)
            page-id   (or (:page-id frame)
                          (-> data :pages first))

            page      (dm/get-in data [:pages-index page-id])
            page      (cond-> page (pmap/pointer-map? page) deref)
            frame-ids (if (some? frame) (list frame-id) (map :id (ctt/get-frames (:objects page))))

            obj-ids   (map #(str page-id %) frame-ids)
            thumbs    (get-object-thumbnails conn id obj-ids)]

        (cond-> page
          ;; If we have frame, we need to specify it on the page level
          ;; and remove the all other unrelated objects.
          (some? frame-id)
          (-> (assoc :thumbnail-frame-id frame-id)
              (update :objects filter-objects frame-id))

          ;; Assoc the available thumbnails and prune not visible shapes
          ;; for avoid transfer unnecessary data.
          :always
          (update :objects assoc-thumbnails page-id thumbs))))))

(s/def ::get-file-data-for-thumbnail
  (s/keys :req [::rpc/profile-id]
          :req-un [::file-id]
          :opt-un [::features]))

(sv/defmethod ::get-file-data-for-thumbnail
  "Retrieves the data for generate the thumbnail of the file. Used
  mainly for render thumbnails on dashboard."
  {::doc/added "1.17"}
  [{:keys [::db/pool] :as cfg} {:keys [::rpc/profile-id file-id features] :as props}]
  (dm/with-open [conn (db/open pool)]
    (files/check-read-permissions! conn profile-id file-id)
    ;; NOTE: we force here the "storage/pointer-map" feature, because
    ;; it used internally only and is independent if user supports it
    ;; or not.
    (let [feat (into #{"storage/pointer-map"} features)
          file (files/get-file conn file-id feat)]
      {:file-id file-id
       :revn (:revn file)
       :page (get-file-data-for-thumbnail conn file)})))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; MUTATION COMMANDS
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

;; --- MUTATION COMMAND: upsert-file-object-thumbnail

(def sql:upsert-object-thumbnail-1
  "insert into file_object_thumbnail(file_id, object_id, data)
   values (?, ?, ?)
       on conflict(file_id, object_id) do
          update set data = ?;")

(def sql:upsert-object-thumbnail-2
  "insert into file_object_thumbnail(file_id, object_id, media_id)
   values (?, ?, ?)
       on conflict(file_id, object_id) do
          update set media_id = ?;")

(defn upsert-file-object-thumbnail!
  [{:keys [::db/conn ::sto/storage]} {:keys [file-id object-id] :as params}]

  ;; NOTE: params can come with data set but with `nil` value, so we
  ;; need first check the existence of the key and then the value.
  (cond
    (contains? params :data)
    (if-let [data (:data params)]
      (db/exec-one! conn [sql:upsert-object-thumbnail-1 file-id object-id data data])
      (db/delete! conn :file-object-thumbnail {:file-id file-id :object-id object-id}))

    (contains? params :media)
    (if-let [{:keys [path mtype] :as media} (:media params)]
      (let [_     (media/validate-media-type! media)
            _     (media/validate-media-size! media)
            hash  (sto/calculate-hash path)
            data  (-> (sto/content path)
                      (sto/wrap-with-hash hash))
            media (sto/put-object! storage
                                   {::sto/content data
                                    ::sto/deduplicate? false
                                    :content-type mtype
                                    :bucket "file-object-thumbnail"})]

        (db/exec-one! conn [sql:upsert-object-thumbnail-2 file-id object-id (:id media) (:id media)]))
      (db/delete! conn :file-object-thumbnail {:file-id file-id :object-id object-id}))))

;; FIXME: change it on validation refactor
(s/def ::data (s/nilable ::us/string))
(s/def ::media (s/nilable ::media/upload))
(s/def ::object-id ::us/string)

(s/def ::upsert-file-object-thumbnail
  (s/keys :req [::rpc/profile-id]
          :req-un [::file-id ::object-id]
          :opt-un [::data ::media]))

(sv/defmethod ::upsert-file-object-thumbnail
  {::doc/added "1.17"
   ::audit/skip true}
  [{:keys [::db/pool] :as cfg} {:keys [::rpc/profile-id file-id] :as params}]

  (assert (or (contains? params :data)
              (contains? params :media)))

  (db/with-atomic [conn pool]
    (files/check-edition-permissions! conn profile-id file-id)

    (when-not (db/read-only? conn)
      (let [cfg (-> cfg
                    (update ::sto/storage media/configure-assets-storage)
                    (assoc ::db/conn conn))]
        (upsert-file-object-thumbnail! cfg params)
        nil))))

;; --- MUTATION COMMAND: upsert-file-thumbnail

(def ^:private sql:upsert-file-thumbnail
  "insert into file_thumbnail (file_id, revn, data, media_id, props)
   values (?, ?, ?, ?, ?::jsonb)
       on conflict(file_id, revn) do
          update set data=?, media_id=?, props=?, updated_at=now();")

(defn- upsert-file-thumbnail!
  [{:keys [::db/conn ::sto/storage]} {:keys [file-id revn props] :as params}]
  (let [props (db/tjson (or props {}))]
    (cond
      (contains? params :data)
      (when-let [data (:data params)]
        (db/exec-one! conn [sql:upsert-file-thumbnail
                            file-id revn data nil props data nil props]))

      (contains? params :media)
      (when-let [{:keys [path mtype] :as media} (:media params)]
        (let [_     (media/validate-media-type! media)
              _     (media/validate-media-size! media)
              hash  (sto/calculate-hash path)
              data  (-> (sto/content path)
                        (sto/wrap-with-hash hash))
              media (sto/put-object! storage
                                     {::sto/content data
                                      ::sto/deduplicate? false
                                      :content-type mtype
                                      :bucket "file-thumbnail"})]
          (db/exec-one! conn [sql:upsert-file-thumbnail
                              file-id revn nil (:id media) props nil (:id media) props]))))))

(s/def ::revn ::us/integer)
(s/def ::props map?)
(s/def ::media ::media/upload)

(s/def ::upsert-file-thumbnail
  (s/keys :req [::rpc/profile-id]
          :req-un [::file-id ::revn ::props]
          :opt-un [::data ::media]))

(sv/defmethod ::upsert-file-thumbnail
  "Creates or updates the file thumbnail. Mainly used for paint the
  grid thumbnails."
  {::doc/added "1.17"
   ::audit/skip true}
  [{:keys [::db/pool] :as cfg} {:keys [::rpc/profile-id file-id] :as params}]
  (db/with-atomic [conn pool]
    (files/check-edition-permissions! conn profile-id file-id)
    (when-not (db/read-only? conn)
      (let [cfg (-> cfg
                    (update ::sto/storage media/configure-assets-storage)
                    (assoc ::db/conn conn))]
        (upsert-file-thumbnail! cfg params))
      nil)))
