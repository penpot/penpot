;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC

(ns app.rpc.commands.media
  (:require
   [app.common.data :as d]
   [app.common.exceptions :as ex]
   [app.common.schema :as sm]
   [app.common.time :as ct]
   [app.common.uuid :as uuid]
   [app.config :as cf]
   [app.db :as db]
   [app.loggers.audit :as-alias audit]
   [app.media :as media]
   [app.rpc :as-alias rpc]
   [app.rpc.climit :as climit]
   [app.rpc.commands.files :as files]
   [app.rpc.doc :as-alias doc]
   [app.rpc.quotes :as quotes]
   [app.storage :as sto]
   [app.storage.tmp :as tmp]
   [app.util.services :as sv]
   [datoteka.io :as io])
  (:import
   java.io.OutputStream))

(def thumbnail-options
  {:width 100
   :height 100
   :quality 85
   :format :jpeg})

;; --- Create File Media object (upload)

(declare create-file-media-object)

(def ^:private schema:upload-file-media-object
  [:map {:title "upload-file-media-object"}
   [:id {:optional true} ::sm/uuid]
   [:file-id ::sm/uuid]
   [:is-local ::sm/boolean]
   [:name [:string {:max 250}]]
   [:content media/schema:upload]])

(sv/defmethod ::upload-file-media-object
  {::doc/added "1.17"
   ::sm/params schema:upload-file-media-object
   ::climit/id [[:process-image/by-profile ::rpc/profile-id]
                [:process-image/global]]}
  [{:keys [::db/pool] :as cfg} {:keys [::rpc/profile-id file-id content] :as params}]
  (files/check-edition-permissions! pool profile-id file-id)
  (media/validate-media-type! content)
  (media/validate-media-size! content)

  (db/run! cfg (fn [{:keys [::db/conn] :as cfg}]
                 ;; We get the minimal file for proper checking if
                 ;; file is not already deleted
                 (let [_     (files/get-minimal-file conn file-id)
                       mobj  (create-file-media-object cfg params)]

                   (db/update! conn :file
                               {:modified-at (ct/now)
                                :has-media-trimmed false}
                               {:id file-id}
                               {::db/return-keys false})

                   (with-meta mobj
                     {::audit/replace-props
                      {:name (:name params)
                       :file-id file-id
                       :is-local (:is-local params)
                       :size (:size content)
                       :mtype (:mtype content)}})))))

(defn- big-enough-for-thumbnail?
  "Checks if the provided image info is big enough for
  create a separate thumbnail storage object."
  [info]
  (or (> (:width info) (:width thumbnail-options))
      (> (:height info) (:height thumbnail-options))))

(defn- svg-image?
  [info]
  (= (:mtype info) "image/svg+xml"))

;; NOTE: we use the `on conflict do update` instead of `do nothing`
;; because postgresql does not returns anything if no update is
;; performed, the `do update` does the trick.

(def sql:create-file-media-object
  "insert into file_media_object (id, file_id, is_local, name, media_id, thumbnail_id, width, height, mtype)
   values (?, ?, ?, ?, ?, ?, ?, ?, ?)
       on conflict (id) do update set created_at=file_media_object.created_at
       returning *")

;; NOTE: the following function executes without a transaction, this
;; means that if something fails in the middle of this function, it
;; will probably leave leaked/unreferenced objects in the database and
;; probably in the storage layer. For handle possible object leakage,
;; we create all media objects marked as touched, this ensures that if
;; something fails, all leaked (already created storage objects) will
;; be eventually marked as deleted by the touched-gc task.
;;
;; The touched-gc task, performs periodic analysis of all touched
;; storage objects and check references of it. This is the reason why
;; `reference` metadata exists: it indicates the name of the table
;; witch holds the reference to storage object (it some kind of
;; inverse, soft referential integrity).

(defn- process-main-image
  [info]
  (let [hash (sto/calculate-hash (:path info))
        data (-> (sto/content (:path info))
                 (sto/wrap-with-hash hash))]
    {::sto/content data
     ::sto/deduplicate? true
     ::sto/touched-at (:ts info)
     :content-type (:mtype info)
     :bucket "file-media-object"}))

(defn- process-thumb-image
  [info]
  (let [thumb (-> thumbnail-options
                  (assoc :cmd :generic-thumbnail)
                  (assoc :input info)
                  (media/run))
        hash  (sto/calculate-hash (:data thumb))
        data  (-> (sto/content (:data thumb) (:size thumb))
                  (sto/wrap-with-hash hash))]
    {::sto/content data
     ::sto/deduplicate? true
     ::sto/touched-at (:ts info)
     :content-type (:mtype thumb)
     :bucket "file-media-object"}))

(defn- process-image
  [content]
  (let [info (media/run {:cmd :info :input content})]
    (cond-> info
      (and (not (svg-image? info))
           (big-enough-for-thumbnail? info))
      (assoc ::thumb (process-thumb-image info))

      :always
      (assoc ::image (process-main-image info)))))

(defn- create-file-media-object
  [{:keys [::sto/storage ::db/conn] :as cfg}
   {:keys [id file-id is-local name content]}]
  (let [result (process-image content)
        image  (sto/put-object! storage (::image result))
        thumb  (when-let [params (::thumb result)]
                 (sto/put-object! storage params))]

    (db/exec-one! conn [sql:create-file-media-object
                        (or id (uuid/next))
                        file-id is-local name
                        (:id image)
                        (:id thumb)
                        (:width result)
                        (:height result)
                        (:mtype result)])))

;; --- Create File Media Object (from URL)

(declare ^:private create-file-media-object-from-url)

(def ^:private schema:create-file-media-object-from-url
  [:map {:title "create-file-media-object-from-url"}
   [:file-id ::sm/uuid]
   [:is-local ::sm/boolean]
   [:url ::sm/uri]
   [:id {:optional true} ::sm/uuid]
   [:name {:optional true} [:string {:max 250}]]])

(sv/defmethod ::create-file-media-object-from-url
  {::doc/added "1.17"
   ::sm/params schema:create-file-media-object-from-url}
  [{:keys [::db/pool] :as cfg} {:keys [::rpc/profile-id file-id] :as params}]
  (files/check-edition-permissions! pool profile-id file-id)
  ;; We get the minimal file for proper checking if file is not
  ;; already deleted
  (let [_    (files/get-minimal-file cfg file-id)
        mobj (create-file-media-object-from-url cfg (assoc params :profile-id profile-id))]

    (db/update! pool :file
                {:modified-at (ct/now)
                 :has-media-trimmed false}
                {:id file-id}
                {::db/return-keys false})

    mobj))

(defn- create-file-media-object-from-url
  [cfg {:keys [url name] :as params}]
  (let [content (media/download-image cfg url)
        params  (-> params
                    (assoc :content content)
                    (assoc :name (d/nilv name "unknown")))]

    ;; NOTE: we use the climit here in a dynamic invocation because we
    ;; don't want saturate the process-image limit with IO (download
    ;; of external image)

    (-> cfg
        (assoc ::climit/id [[:process-image/by-profile (:profile-id params)]
                            [:process-image/global]])
        (assoc ::climit/label "create-file-media-object-from-url")
        (climit/invoke! #(db/run! %1 create-file-media-object %2) params))))


;; --- Clone File Media object (Upload and create from url)

(declare clone-file-media-object)

(def ^:private schema:clone-file-media-object
  [:map {:title "clone-file-media-object"}
   [:file-id ::sm/uuid]
   [:is-local ::sm/boolean]
   [:id ::sm/uuid]])

(sv/defmethod ::clone-file-media-object
  {::doc/added "1.17"
   ::sm/params schema:clone-file-media-object
   ::db/transaction true}
  [{:keys [::db/conn] :as cfg} {:keys [::rpc/profile-id file-id] :as params}]
  (files/check-edition-permissions! conn profile-id file-id)
  (clone-file-media-object cfg params))

(defn clone-file-media-object
  [{:keys [::db/conn]} {:keys [id file-id is-local]}]
  (let [mobj (db/get-by-id conn :file-media-object id)]
    (db/insert! conn :file-media-object
                {:id (uuid/next)
                 :file-id file-id
                 :is-local is-local
                 :name (:name mobj)
                 :media-id (:media-id mobj)
                 :thumbnail-id (:thumbnail-id mobj)
                 :width  (:width mobj)
                 :height (:height mobj)
                 :mtype  (:mtype mobj)})))

;; --- Chunked Upload: Create an upload session

(def ^:private schema:create-upload-session
  [:map {:title "create-upload-session"}
   [:total-chunks ::sm/int]])

(def ^:private schema:create-upload-session-result
  [:map {:title "create-upload-session-result"}
   [:session-id ::sm/uuid]])

(sv/defmethod ::create-upload-session
  {::doc/added "2.16"
   ::sm/params schema:create-upload-session
   ::sm/result schema:create-upload-session-result}
  [{:keys [::db/pool] :as cfg}
   {:keys [::rpc/profile-id total-chunks]}]

  (let [max-chunks (cf/get :quotes-upload-chunks-per-session)]
    (when (> total-chunks max-chunks)
      (ex/raise :type :restriction
                :code :max-quote-reached
                :target "upload-chunks-per-session"
                :quote max-chunks
                :count total-chunks)))

  (quotes/check! cfg {::quotes/id ::quotes/upload-sessions-per-profile
                      ::quotes/profile-id profile-id})

  (let [session-id (uuid/next)]
    (db/insert! pool :upload-session
                {:id           session-id
                 :profile-id   profile-id
                 :total-chunks total-chunks})
    {:session-id session-id}))

;; --- Chunked Upload: Upload a single chunk

(def ^:private schema:upload-chunk
  [:map {:title "upload-chunk"}
   [:session-id ::sm/uuid]
   [:index      ::sm/int]
   [:content    media/schema:upload]])

(def ^:private schema:upload-chunk-result
  [:map {:title "upload-chunk-result"}
   [:session-id ::sm/uuid]
   [:index      ::sm/int]])

(sv/defmethod ::upload-chunk
  {::doc/added "2.16"
   ::sm/params schema:upload-chunk
   ::sm/result schema:upload-chunk-result}
  [{:keys [::db/pool] :as cfg}
   {:keys [::rpc/profile-id session-id index content] :as _params}]
  (let [session (db/get pool :upload-session {:id session-id :profile-id profile-id})]
    (when (or (neg? index) (>= index (:total-chunks session)))
      (ex/raise :type :validation
                :code :invalid-chunk-index
                :hint "chunk index is out of range for this session"
                :session-id session-id
                :total-chunks (:total-chunks session)
                :index index)))

  (let [storage (sto/resolve cfg)
        data    (sto/content (:path content))]
    (sto/put-object! storage
                     {::sto/content      data
                      ::sto/deduplicate? false
                      ::sto/touch        true
                      :content-type      (:mtype content)
                      :bucket            "tempfile"
                      :upload-id         (str session-id)
                      :chunk-index       index}))

  {:session-id session-id
   :index      index})

;; --- Chunked Upload: shared helpers

(def ^:private sql:get-upload-chunks
  "SELECT id, size, (metadata->>'~:chunk-index')::integer AS chunk_index
     FROM storage_object
    WHERE (metadata->>'~:upload-id') = ?::text
      AND deleted_at IS NULL
    ORDER BY (metadata->>'~:chunk-index')::integer ASC")

(defn- get-upload-chunks
  [conn session-id]
  (db/exec! conn [sql:get-upload-chunks (str session-id)]))

(defn- concat-chunks
  "Reads all chunk storage objects in order and writes them to a single
  temporary file on the local filesystem. Returns a path to that file."
  [storage chunks]
  (let [tmp (tmp/tempfile :prefix "penpot.chunked-upload.")]
    (with-open [^OutputStream out (io/output-stream tmp)]
      (doseq [{:keys [id]} chunks]
        (let [sobj  (sto/get-object storage id)
              bytes (sto/get-object-bytes storage sobj)]
          (.write out ^bytes bytes))))
    tmp))

(defn assemble-chunks
  "Validates that all expected chunks are present for `session-id` and
  concatenates them into a single temporary file.  Returns a map
  conforming to `media/schema:upload` with `:filename`, `:path` and
  `:size`.

  Raises a :validation/:missing-chunks error when the number of stored
  chunks does not match `:total-chunks` recorded in the session row.
  Deletes the session row from `upload_session` on success."
  [{:keys [::db/conn] :as cfg} session-id]
  (let [session (db/get conn :upload-session {:id session-id})
        chunks  (get-upload-chunks conn session-id)]

    (when (not= (count chunks) (:total-chunks session))
      (ex/raise :type :validation
                :code :missing-chunks
                :hint "number of stored chunks does not match expected total"
                :session-id session-id
                :expected   (:total-chunks session)
                :found      (count chunks)))

    (let [storage (sto/resolve cfg ::db/reuse-conn true)
          path    (concat-chunks storage chunks)
          size    (reduce #(+ %1 (:size %2)) 0 chunks)]

      (db/delete! conn :upload-session {:id session-id})

      {:filename "upload"
       :path     path
       :size     size})))

;; --- Chunked Upload: Assemble all chunks into a final media object

(def ^:private schema:assemble-file-media-object
  [:map {:title "assemble-file-media-object"}
   [:session-id ::sm/uuid]
   [:file-id    ::sm/uuid]
   [:is-local   ::sm/boolean]
   [:name       [:string {:max 250}]]
   [:mtype      :string]
   [:id         {:optional true} ::sm/uuid]])

(sv/defmethod ::assemble-file-media-object
  {::doc/added "2.16"
   ::sm/params schema:assemble-file-media-object
   ::climit/id [[:process-image/by-profile ::rpc/profile-id]
                [:process-image/global]]}
  [{:keys [::db/pool] :as cfg}
   {:keys [::rpc/profile-id session-id file-id is-local name mtype id] :as params}]
  (files/check-edition-permissions! pool profile-id file-id)

  (db/tx-run! cfg
              (fn [{:keys [::db/conn] :as cfg}]
                (let [{:keys [path size]} (assemble-chunks cfg session-id)
                      content {:filename "upload"
                               :size     size
                               :path     path
                               :mtype    mtype}
                      _       (media/validate-media-type! content)
                      mobj    (create-file-media-object cfg (assoc params
                                                                   :id (or id (uuid/next))
                                                                   :content content))]

                  (db/update! conn :file
                              {:modified-at      (ct/now)
                               :has-media-trimmed false}
                              {:id file-id}
                              {::db/return-keys false})

                  (with-meta mobj
                    {::audit/replace-props
                     {:name      name
                      :file-id   file-id
                      :is-local  is-local
                      :mtype     mtype}})))))

