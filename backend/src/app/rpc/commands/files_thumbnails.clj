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
   [app.common.features :as cfeat]
   [app.common.geom.shapes :as gsh]
   [app.common.pages.helpers :as cph]
   [app.common.schema :as sm]
   [app.common.spec :as us]
   [app.common.thumbnails :as thc]
   [app.common.types.shape-tree :as ctt]
   [app.config :as cf]
   [app.db :as db]
   [app.db.sql :as sql]
   [app.loggers.audit :as-alias audit]
   [app.loggers.webhooks :as-alias webhooks]
   [app.media :as media]
   [app.rpc :as-alias rpc]
   [app.rpc.commands.files :as files]
   [app.rpc.commands.teams :as teams]
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

(defn- get-object-thumbnails-by-tag
  [conn file-id tag]
  (let [sql (str/concat
             "select object_id, data, media_id, tag "
             "  from file_object_thumbnail"
             " where file_id=? and tag=?")
        res (db/exec! conn [sql file-id tag])]
    (->> res
         (d/index-by :object-id (fn [row]
                                  (or (some-> row :media-id files/resolve-public-uri)
                                      (:data row))))
         (d/without-nils))))

(defn- get-object-thumbnails
  ([conn file-id]
   (let [sql (str/concat
              "select object_id, data, media_id, tag "
              "  from file_object_thumbnail"
              " where file_id=?")
         res (db/exec! conn [sql file-id])]
     (->> res
          (d/index-by :object-id (fn [row]
                                   (or (some-> row :media-id files/resolve-public-uri)
                                       (:data row))))
          (d/without-nils))))

  ([conn file-id object-ids]
   (let [sql (str/concat
              "select object_id, data, media_id, tag "
              "  from file_object_thumbnail"
              " where file_id=? and object_id = ANY(?)")
         ids (db/create-array conn "text" (seq object-ids))
         res (db/exec! conn [sql file-id ids])]
     (d/index-by :object-id
                 (fn [row]
                   (or (some-> row :media-id files/resolve-public-uri)
                       (:data row)))
                 res))))

(sv/defmethod ::get-file-object-thumbnails
  "Retrieve a file object thumbnails."
  {::doc/added "1.17"
   ::doc/module :files
   ::sm/params [:map {:title "get-file-object-thumbnails"}
                [:file-id ::sm/uuid]
                [:tag {:optional true} :string]]
   ::sm/result [:map-of :string :string]
   ::cond/get-object #(files/get-minimal-file %1 (:file-id %2))
   ::cond/reuse-key? true
   ::cond/key-fn files/get-file-etag}
  [{:keys [::db/pool] :as cfg} {:keys [::rpc/profile-id file-id tag] :as params}]
  (dm/with-open [conn (db/open pool)]
    (files/check-read-permissions! conn profile-id file-id)
    (if tag
      (get-object-thumbnails-by-tag conn file-id tag)
      (get-object-thumbnails conn file-id))))

;; --- COMMAND QUERY: get-file-thumbnail

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

    (when-not (:data row)
      (ex/raise :type :not-found
                :code :file-thumbnail-not-found))

    {:data (:data row)
     :props (some-> (:props row) db/decode-transit-pgobject)
     :revn (:revn row)
     :file-id (:file-id row)}))

(s/def ::revn ::us/integer)
(s/def ::file-id ::us/uuid)

(s/def ::get-file-thumbnail
  (s/keys :req [::rpc/profile-id]
          :req-un [::file-id]
          :opt-un [::revn]))

(sv/defmethod ::get-file-thumbnail
  {::doc/added "1.17"
   ::doc/module :files
   ::doc/deprecated "1.19"}
  [{:keys [::db/pool]} {:keys [::rpc/profile-id file-id revn]}]
  (dm/with-open [conn (db/open pool)]
    (files/check-read-permissions! conn profile-id file-id)
    (-> (get-file-thumbnail conn file-id revn)
        (rph/with-http-cache long-cache-duration))))

;; --- COMMAND QUERY: get-file-data-for-thumbnail

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
            (d/seek #(or (:use-for-thumbnail %)
                         (:use-for-thumbnail? %)) ; NOTE: backward comp (remove on v1.21)
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
                (let [frame-id  (:id frame)
                      object-id (thc/fmt-object-id (:id file) page-id frame-id "frame")
                      frame     (if-let [thumb (get thumbnails object-id)]
                                  (assoc frame :thumbnail thumb :shapes [])
                                  (dissoc frame :thumbnail))

                      children-ids
                      (cph/get-children-ids objects frame-id)

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

    (binding [pmap/*load-fn* (partial files/load-pointer conn id)]
      (let [frame     (get-thumbnail-frame data)
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
          :always
          (update :objects assoc-thumbnails page-id thumbs))))))

(def ^:private schema:get-file-data-for-thumbnail
  [:map {:title "get-file-data-for-thumbnail"}
   [:file-id ::sm/uuid]
   [:features {:optional true} ::cfeat/features]])

(def ^:private schema:partial-file
  [:map {:title "PartialFile"}
   [:id ::sm/uuid]
   [:revn {:min 0} :int]
   [:page :any]])

(sv/defmethod ::get-file-data-for-thumbnail
  "Retrieves the data for generate the thumbnail of the file. Used
  mainly for render thumbnails on dashboard."
  {::doc/added "1.17"
   ::doc/module :files
   ::sm/params schema:get-file-data-for-thumbnail
   ::sm/result schema:partial-file}
  [cfg {:keys [::rpc/profile-id file-id] :as params}]
  (db/run! cfg (fn [{:keys [::db/conn] :as cfg}]
                 (files/check-read-permissions! conn profile-id file-id)

                 (let [team     (teams/get-team cfg
                                                :profile-id profile-id
                                                :file-id file-id)

                       file     (files/get-file conn file-id)]

                   (-> (cfeat/get-team-enabled-features cf/flags team)
                       (cfeat/check-client-features! (:features params))
                       (cfeat/check-file-features! (:features file) (:features params)))

                   {:file-id file-id
                    :revn (:revn file)
                    :page (get-file-data-for-thumbnail conn file)}))))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; MUTATION COMMANDS
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

;; --- MUTATION COMMAND: upsert-file-object-thumbnail

(def sql:upsert-object-thumbnail
  "insert into file_object_thumbnail(file_id, tag, object_id, data)
   values (?, ?, ?, ?)
       on conflict(file_id, tag, object_id) do
          update set data = ?;")

(defn upsert-file-object-thumbnail!
  [conn {:keys [file-id tag object-id data]}]
  (if data
    (db/exec-one! conn [sql:upsert-object-thumbnail file-id (or tag "frame") object-id data data])
    (db/delete! conn :file-object-thumbnail {:file-id file-id :object-id object-id})))

(s/def ::data (s/nilable ::us/string))
(s/def ::object-id ::us/string)
(s/def ::tag ::us/string)

(s/def ::upsert-file-object-thumbnail
  (s/keys :req [::rpc/profile-id]
          :req-un [::file-id ::object-id]
          :opt-un [::data ::tag]))

(sv/defmethod ::upsert-file-object-thumbnail
  {::doc/added "1.17"
   ::doc/module :files
   ::doc/deprecated "1.19"
   ::audit/skip true}
  [{:keys [::db/pool] :as cfg} {:keys [::rpc/profile-id file-id] :as params}]
  (db/with-atomic [conn pool]
    (files/check-edition-permissions! conn profile-id file-id)

    (when-not (db/read-only? conn)
      (upsert-file-object-thumbnail! conn params)
      nil)))


;; --- MUTATION COMMAND: create-file-object-thumbnail

(def ^:private sql:create-object-thumbnail
  "insert into file_object_thumbnail(file_id, object_id, media_id, tag)
   values (?, ?, ?, ?)
       on conflict(file_id, tag, object_id) do
          update set media_id = ?;")

(defn- create-file-object-thumbnail!
  [{:keys [::db/conn ::sto/storage]} file-id object-id media tag]

  (let [path  (:path media)
        mtype (:mtype media)
        hash  (sto/calculate-hash path)
        data  (-> (sto/content path)
                  (sto/wrap-with-hash hash))
        media (sto/put-object! storage
                               {::sto/content data
                                ::sto/deduplicate? false
                                :content-type mtype
                                :bucket "file-object-thumbnail"})]

    (db/exec-one! conn [sql:create-object-thumbnail file-id object-id
                        (:id media) tag (:id media)])))


(def schema:create-file-object-thumbnail
  [:map {:title "create-file-object-thumbnail"}
   [:file-id ::sm/uuid]
   [:object-id :string]
   [:media ::media/upload]
   [:tag {:optional true} :string]])

(sv/defmethod ::create-file-object-thumbnail
  {:doc/added "1.19"
   ::doc/module :files
   ::audit/skip true
   ::sm/params schema:create-file-object-thumbnail}

  [{:keys [::db/pool] :as cfg} {:keys [::rpc/profile-id file-id object-id media tag]}]
  (db/with-atomic [conn pool]
    (files/check-edition-permissions! conn profile-id file-id)
    (media/validate-media-type! media)
    (media/validate-media-size! media)

    (when-not (db/read-only? conn)
      (-> cfg
          (update ::sto/storage media/configure-assets-storage)
          (assoc ::db/conn conn)
          (create-file-object-thumbnail! file-id object-id media (or tag "frame")))
      nil)))

;; --- MUTATION COMMAND: delete-file-object-thumbnail

(defn- delete-file-object-thumbnail!
  [{:keys [::db/conn ::sto/storage]} file-id object-id]
  (when-let [{:keys [media-id]} (db/get* conn :file-object-thumbnail
                                         {:file-id file-id
                                          :object-id object-id}
                                         {::db/for-update? true})]
    (when media-id
      (sto/del-object! storage media-id))

    (db/delete! conn :file-object-thumbnail
                {:file-id file-id
                 :object-id object-id})
    nil))

(s/def ::delete-file-object-thumbnail
  (s/keys :req [::rpc/profile-id]
          :req-un [::file-id ::object-id]))

(sv/defmethod ::delete-file-object-thumbnail
  {:doc/added "1.19"
   ::doc/module :files
   ::audit/skip true}
  [{:keys [::db/pool] :as cfg} {:keys [::rpc/profile-id file-id object-id]}]

  (db/with-atomic [conn pool]
    (files/check-edition-permissions! conn profile-id file-id)

    (when-not (db/read-only? conn)
      (-> cfg
          (update ::sto/storage media/configure-assets-storage)
          (assoc ::db/conn conn)
          (delete-file-object-thumbnail! file-id object-id))
      nil)))

;; --- MUTATION COMMAND: upsert-file-thumbnail

(def ^:private sql:upsert-file-thumbnail
  "insert into file_thumbnail (file_id, revn, data, props)
   values (?, ?, ?, ?::jsonb)
       on conflict(file_id, revn) do
          update set data = ?, props=?, updated_at=now();")

(defn- upsert-file-thumbnail!
  [conn {:keys [file-id revn data props]}]
  (let [props (db/tjson (or props {}))]
    (db/exec-one! conn [sql:upsert-file-thumbnail
                        file-id revn data props data props])))

(s/def ::revn ::us/integer)
(s/def ::props map?)

(s/def ::upsert-file-thumbnail
  (s/keys :req [::rpc/profile-id]
          :req-un [::file-id ::revn ::props ::data]))

(sv/defmethod ::upsert-file-thumbnail
  "Creates or updates the file thumbnail. Mainly used for paint the
  grid thumbnails."
  {::doc/added "1.17"
   ::doc/module :files
   ::doc/deprecated "1.19"
   ::audit/skip true}
  [{:keys [::db/pool] :as cfg} {:keys [::rpc/profile-id file-id] :as params}]
  (db/with-atomic [conn pool]
    (files/check-edition-permissions! conn profile-id file-id)
    (when-not (db/read-only? conn)
      (upsert-file-thumbnail! conn params)
      nil)))

;; --- MUTATION COMMAND: create-file-thumbnail

(def ^:private sql:create-file-thumbnail
  "insert into file_thumbnail (file_id, revn, media_id, props)
   values (?, ?, ?, ?::jsonb)
       on conflict(file_id, revn) do
          update set media_id=?, props=?, updated_at=now();")

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
        media (sto/put-object! storage
                               {::sto/content data
                                ::sto/deduplicate? false
                                :content-type mtype
                                :bucket "file-thumbnail"})]
    (db/exec-one! conn [sql:create-file-thumbnail file-id revn
                        (:id media) props
                        (:id media) props])
    media))

(sv/defmethod ::create-file-thumbnail
  "Creates or updates the file thumbnail. Mainly used for paint the
  grid thumbnails."
  {::doc/added "1.19"
   ::doc/module :files
   ::audit/skip true
   ::sm/params [:map {:title "create-file-thumbnail"}
                [:file-id ::sm/uuid]
                [:revn :int]
                [:media ::media/upload]]
   }

  [{:keys [::db/pool] :as cfg} {:keys [::rpc/profile-id file-id] :as params}]
  (db/with-atomic [conn pool]
    (files/check-edition-permissions! conn profile-id file-id)
    (when-not (db/read-only? conn)
      (let [media (-> cfg
                      (update ::sto/storage media/configure-assets-storage)
                      (assoc ::db/conn conn)
                      (create-file-thumbnail! params))]

        {:uri (files/resolve-public-uri (:id media))}))))
