;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC

(ns app.rpc.commands.media
  (:require
   [app.common.data :as d]
   [app.common.exceptions :as ex]
   [app.common.media :as cm]
   [app.common.schema :as sm]
   [app.common.uuid :as uuid]
   [app.config :as cf]
   [app.db :as db]
   [app.http.client :as http]
   [app.loggers.audit :as-alias audit]
   [app.media :as media]
   [app.rpc :as-alias rpc]
   [app.rpc.climit :as climit]
   [app.rpc.commands.files :as files]
   [app.rpc.doc :as-alias doc]
   [app.storage :as sto]
   [app.storage.tmp :as tmp]
   [app.util.services :as sv]
   [app.util.time :as dt]
   [app.worker :as-alias wrk]
   [cuerdas.core :as str]
   [datoteka.io :as io]
   [promesa.exec :as px]))

(def default-max-file-size
  (* 1024 1024 10)) ; 10 MiB

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
   [:content ::media/upload]])

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
                               {:modified-at (dt/now)
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
  [{:keys [::sto/storage ::db/conn ::wrk/executor] :as cfg}
   {:keys [id file-id is-local name content]}]
  (let [result (px/invoke! executor (partial process-image content))
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
                {:modified-at (dt/now)
                 :has-media-trimmed false}
                {:id file-id}
                {::db/return-keys false})

    mobj))

(defn download-image
  [{:keys [::http/client]} uri]
  (letfn [(parse-and-validate [{:keys [headers] :as response}]
            (let [size     (some-> (get headers "content-length") d/parse-integer)
                  mtype    (get headers "content-type")
                  format   (cm/mtype->format mtype)
                  max-size (cf/get :media-max-file-size default-max-file-size)]

              (when-not size
                (ex/raise :type :validation
                          :code :unknown-size
                          :hint "seems like the url points to resource with unknown size"))

              (when (> size max-size)
                (ex/raise :type :validation
                          :code :file-too-large
                          :hint (str/ffmt "the file size % is greater than the maximum %"
                                          size
                                          default-max-file-size)))

              (when (nil? format)
                (ex/raise :type :validation
                          :code :media-type-not-allowed
                          :hint "seems like the url points to an invalid media object"))

              {:size size :mtype mtype :format format}))]

    (let [{:keys [body] :as response} (http/req! client
                                                 {:method :get :uri uri}
                                                 {:response-type :input-stream :sync? true})
          {:keys [size mtype]} (parse-and-validate response)
          path    (tmp/tempfile :prefix "penpot.media.download.")
          written (io/write* path body :size size)]

      (when (not= written size)
        (ex/raise :type :internal
                  :code :mismatch-write-size
                  :hint "unexpected state: unable to write to file"))

      {:filename "tempfile"
       :size size
       :path path
       :mtype mtype})))

(defn- create-file-media-object-from-url
  [cfg {:keys [url name] :as params}]
  (let [content (download-image cfg url)
        params  (-> params
                    (assoc :content content)
                    (assoc :name (or name (:filename content))))]

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
