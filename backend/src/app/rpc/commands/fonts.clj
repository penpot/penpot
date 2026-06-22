;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC Sucursal en España SL

(ns app.rpc.commands.fonts
  (:require
   [app.binfile.common :as bfc]
   [app.common.data.macros :as dm]
   [app.common.exceptions :as ex]
   [app.common.logging :as l]
   [app.common.media :as cm]
   [app.common.schema :as sm]
   [app.common.time :as ct]
   [app.common.types.font :as types.font]
   [app.common.uuid :as uuid]
   [app.db :as db]
   [app.db.sql :as-alias sql]
   [app.features.logical-deletion :as ldel]
   [app.http :as-alias http]
   [app.loggers.audit :as-alias audit]
   [app.loggers.webhooks :as-alias webhooks]
   [app.media :as media]
   [app.rpc :as-alias rpc]
   [app.rpc.climit :as-alias climit]
   [app.rpc.commands.files :as files]
   [app.rpc.commands.media :refer [assemble-chunks]]
   [app.rpc.commands.projects :as projects]
   [app.rpc.commands.teams :as teams]
   [app.rpc.doc :as-alias doc]
   [app.rpc.helpers :as rph]
   [app.rpc.quotes :as quotes]
   [app.storage :as sto]
   [app.storage.tmp :as tmp]
   [app.util.services :as sv]
   [cuerdas.core :as str]
   [datoteka.fs :as fs]
   [datoteka.io :as io])
  (:import
   java.io.InputStream
   java.io.OutputStream
   java.io.SequenceInputStream
   java.util.Collections
   java.util.zip.ZipEntry
   java.util.zip.ZipOutputStream))

(set! *warn-on-reflection* true)


(def valid-weight #{100 200 300 400 500 600 700 800 900 950})
(def valid-style #{"normal" "italic"})

;; --- QUERY: Get font variants

(def ^:private
  schema:get-font-variants
  [:and
   [:map {:title "get-font-variants"}
    [:team-id {:optional true} ::sm/uuid]
    [:file-id {:optional true} ::sm/uuid]
    [:project-id {:optional true} ::sm/uuid]
    [:share-id {:optional true} ::sm/uuid]]
   [::sm/contains-any #{:team-id :file-id :project-id}]])

(sv/defmethod ::get-font-variants
  {::doc/added "1.18"
   ::sm/params schema:get-font-variants}
  [{:keys [::db/pool] :as cfg} {:keys [::rpc/profile-id team-id file-id project-id share-id] :as params}]
  (dm/with-open [conn (db/open pool)]
    (cond
      (uuid? team-id)
      (do
        (teams/check-read-permissions! conn profile-id team-id)
        (db/query conn :team-font-variant
                  {:team-id team-id
                   :deleted-at nil}))

      (uuid? project-id)
      (let [project (db/get-by-id conn :project project-id {:columns [:id :team-id]})]
        (projects/check-read-permissions! conn profile-id project-id)
        (db/query conn :team-font-variant
                  {:team-id (:team-id project)
                   :deleted-at nil}))

      (uuid? file-id)
      (let [file    (db/get-by-id conn :file file-id {:columns [:id :project-id]})
            project (db/get-by-id conn :project (:project-id file) {:columns [:id :team-id]})
            perms   (bfc/get-file-permissions conn profile-id file-id share-id)]
        (files/check-read-permissions! perms)
        (db/query conn :team-font-variant
                  {:team-id (:team-id project)
                   :deleted-at nil})))))


(declare create-font-variant)

(def ^:private schema:create-font-variant
  [:and
   [:map {:title "create-font-variant"}
    [:team-id    ::sm/uuid]
    [:font-id    ::sm/uuid]
    [:font-family types.font/schema:font-family]
    [:font-weight [::sm/one-of {:format "number"} valid-weight]]
    [:font-style  [::sm/one-of {:format "string"} valid-style]]
    [:data    {:optional true} [:map-of ::sm/text [:or ::sm/bytes [::sm/vec ::sm/bytes]]]]
    [:uploads {:optional true} [:map-of ::sm/text ::sm/uuid]]]
   [:fn {:error/message "one of :data or :uploads is required"}
    (fn [{:keys [data uploads]}]
      (or (seq data) (seq uploads)))]])

(defn- prepare-font-data-from-uploads
  "Assembles each chunked-upload session in `uploads` (a `{mtype →
  session-id}` map) into a temp file, validates the media type and
  size of every entry, and returns a `{mtype → path}` data map."
  [cfg {:keys [uploads] :as params}]
  (let [data (reduce-kv
              (fn [acc mtype session-id]
                (let [assembled (assemble-chunks cfg session-id)]
                  (-> {:mtype mtype :size (:size assembled)}
                      (media/validate-media-type! cm/font-types)
                      (media/validate-font-size!))
                  (assoc acc mtype (:path assembled))))
              {}
              uploads)]

    (-> params
        (assoc :data data)
        (dissoc :uploads))))

(defn- prepare-font-data-from-legacy
  "Validates the media type and size of every entry in the legacy
  `:data` map (a `{mtype → bytes | [bytes]}` map). Normalises every
  entry to a tempfile. Returns params with a normalised
  `{mtype → path}` data map."
  [{:keys [data] :as params}]
  (let [data (reduce-kv
              (fn [acc mtype content]
                (let [tmp     (tmp/tempfile :prefix "penpot.tempfont." :suffix "")
                      chunks  (if (vector? content) content [content])
                      streams (map io/input-stream chunks)
                      streams (Collections/enumeration streams)]

                  ;; Generate the tempfile from all chunks
                  (with-open [^OutputStream output (io/output-stream tmp)
                              ^InputStream input (SequenceInputStream. streams)]
                    (io/copy input output))

                  ;; Validate
                  (-> {:mtype mtype :size (fs/size tmp)}
                      (media/validate-media-type! cm/font-types)
                      (media/validate-font-size!))

                  (assoc acc mtype tmp)))
              {}
              data)]
    (assoc params :data data)))

(sv/defmethod ::create-font-variant
  "Upload a font variant.  Font data may be provided either as a
  Transit-encoded `:data` map (keyed by mime-type) for small fonts, or
  as an `:uploads` map (keyed by mime-type, values are upload-session
  UUIDs from the chunked-upload API) for large fonts.  Exactly one of
  the two must be present."
  {::doc/added "1.18"
   ::doc/changes ["2.16" "Add :uploads param for chunked upload support"]
   ::climit/id [[:process-font/by-profile ::rpc/profile-id]
                [:process-font/global]]
   ::webhooks/event? true
   ::sm/params schema:create-font-variant}
  [{:keys [::db/pool] :as cfg} {:keys [::rpc/profile-id team-id uploads] :as params}]
  (teams/check-edition-permissions! pool profile-id team-id)
  (quotes/check! cfg {::quotes/id ::quotes/font-variants-per-team
                      ::quotes/profile-id profile-id
                      ::quotes/team-id team-id})
  (let [params (if (some? uploads)
                 (db/tx-run! cfg prepare-font-data-from-uploads params)
                 (prepare-font-data-from-legacy params))]
    (create-font-variant cfg (assoc params :profile-id profile-id))))

(defn create-font-variant
  [{:keys [::sto/storage] :as cfg} {:keys [data] :as params}]
  (letfn [(generate-missing [data]
            (let [data (media/run cfg {:cmd :generate-fonts :input data})]
              (when (and (not (contains? data "font/otf"))
                         (not (contains? data "font/ttf"))
                         (not (contains? data "font/woff"))
                         (not (contains? data "font/woff2")))
                (ex/raise :type :validation
                          :code :invalid-font-upload
                          :hint "invalid font upload, unable to generate missing font assets"))
              data))

          (prepare-font [data mtype]
            (when-let [resource (get data mtype)]

              (let [hash    (sto/calculate-hash resource)
                    content (-> (sto/content resource)
                                (sto/wrap-with-hash hash))]
                {::sto/content content
                 ::sto/touched-at (ct/now)
                 ::sto/deduplicate? true
                 :content-type mtype
                 :bucket "team-font-variant"})))

          (persist-fonts-files! [data]
            (into {} (keep (fn [[kind mtype]]
                             (when-let [params (prepare-font data mtype)]
                               [kind (sto/put-object! storage params)])))
                  [[:otf "font/otf"]
                   [:ttf "font/ttf"]
                   [:woff1 "font/woff"]
                   [:woff2 "font/woff2"]]))

          (insert-font-variant! [conn {:keys [woff1 woff2 otf ttf]}]
            (db/insert! conn :team-font-variant
                        {:id (uuid/next)
                         :team-id (:team-id params)
                         :font-id (:font-id params)
                         :font-family (:font-family params)
                         :font-weight (:font-weight params)
                         :font-style (:font-style params)
                         :variant-name (:variant-name params)
                         :woff1-file-id (:id woff1)
                         :woff2-file-id (:id woff2)
                         :otf-file-id (:id otf)
                         :ttf-file-id (:id ttf)}))]

    (let [tpoint     (ct/tpoint)
          mtypes     (vec (keys data))
          total-size (reduce-kv (fn [acc _ content]
                                  (+ acc (if (bytes? content)
                                           (alength ^bytes content)
                                           (fs/size content))))
                                0
                                data)]

      (l/dbg :hint "create-font-variant"
             :step "init"
             :font-family (:font-family params)
             :font-weight (:font-weight params)
             :font-style  (:font-style params)
             :mtypes      (str/join mtypes ",")
             :size        total-size)

      (let [data    (generate-missing data)
            assets  (persist-fonts-files! data)
            result  (db/tx-run! cfg #(insert-font-variant! (::db/conn %) assets))
            elapsed (tpoint)]

        (l/dbg :hint "create-font-variant"
               :step "end"
               :font-family (:font-family params)
               :font-weight (:font-weight params)
               :font-style  (:font-style params)
               :mtypes      (str/join mtypes ",")
               :size        total-size
               :elapsed     (ct/format-duration elapsed))

        (vary-meta result assoc ::audit/replace-props (update params :data (comp vec keys)))))))

;; --- UPDATE FONT FAMILY

(def ^:private
  schema:update-font
  [:map {:title "update-font"}
   [:team-id ::sm/uuid]
   [:id ::sm/uuid]
   [:name types.font/schema:font-family]])

(sv/defmethod ::update-font
  {::doc/added "1.18"
   ::webhooks/event? true
   ::sm/params schema:update-font}
  [cfg {:keys [::rpc/profile-id team-id id name]}]
  (db/tx-run! cfg
              (fn [{:keys [::db/conn]}]
                (teams/check-edition-permissions! conn profile-id team-id)

                (db/update! conn :team-font-variant
                            {:font-family name}
                            {:font-id id
                             :team-id team-id})

                (rph/with-meta (rph/wrap nil)
                  {::audit/replace-props {:id id
                                          :name name
                                          :team-id team-id
                                          :profile-id profile-id}}))))

;; --- DELETE FONT

(def ^:private
  schema:delete-font
  [:map {:title "delete-font"}
   [:team-id ::sm/uuid]
   [:id ::sm/uuid]])

(sv/defmethod ::delete-font
  {::doc/added "1.18"
   ::webhooks/event? true
   ::sm/params schema:delete-font
   ::db/transaction true}
  [{:keys [::db/conn] :as cfg} {:keys [::rpc/profile-id id team-id]}]
  (let [team  (teams/get-team conn
                              :profile-id profile-id
                              :team-id team-id)

        fonts (db/query conn :team-font-variant
                        {:team-id team-id
                         :font-id id
                         :deleted-at nil}
                        {::sql/for-update true})

        delay (ldel/get-deletion-delay team)
        tnow  (ct/in-future delay)]

    (teams/check-edition-permissions! (:permissions team))

    (when-not (seq fonts)
      (ex/raise :type :not-found
                :code :object-not-found))


    (doseq [font fonts]
      (db/update! conn :team-font-variant
                  {:deleted-at tnow}
                  {:id (:id font)}
                  {::db/return-keys false}))

    (rph/with-meta (rph/wrap)
      {::audit/props {:id id
                      :team-id team-id
                      :name (:font-family (peek fonts))
                      :profile-id profile-id}})))

;; --- DELETE FONT VARIANT

(def ^:private schema:delete-font-variant
  [:map {:title "delete-font-variant"}
   [:team-id ::sm/uuid]
   [:id ::sm/uuid]])

(sv/defmethod ::delete-font-variant
  {::doc/added "1.18"
   ::webhooks/event? true
   ::sm/params schema:delete-font-variant
   ::db/transaction true}
  [{:keys [::db/conn] :as cfg} {:keys [::rpc/profile-id id team-id]}]
  (let [team    (teams/get-team conn
                                :profile-id profile-id
                                :team-id team-id)
        variant (db/get conn :team-font-variant
                        {:id id :team-id team-id}
                        {::sql/for-update true})
        delay   (ldel/get-deletion-delay team)]

    (teams/check-edition-permissions! (:permissions team))
    (db/update! conn :team-font-variant
                {:deleted-at (ct/in-future delay)}
                {:id (:id variant)}
                {::db/return-keys false})

    (rph/with-meta (rph/wrap)
      {::audit/props {:font-family (:font-family variant)
                      :font-id (:font-id variant)}})))

;; --- DOWNLOAD FONT

(defn- make-temporal-storage-object
  [cfg profile-id content]
  (let [storage (sto/resolve cfg)
        content (media/check-input content)
        hash    (sto/calculate-hash (:path content))
        data    (-> (sto/content (:path content))
                    (sto/wrap-with-hash hash))
        mtype   (:mtype content "application/octet-stream")
        content {::sto/content data
                 ::sto/deduplicate? true
                 ::sto/touched-at (ct/in-future {:minutes 30})
                 :profile-id profile-id
                 :content-type mtype
                 :bucket "tempfile"}]

    (sto/put-object! storage content)))

(defn- make-variant-filename
  [v mtype]
  (str (:font-family v) "-" (:font-weight v)
       (when-not (= "normal" (:font-style v)) (str "-" (:font-style v)))
       (cm/mtype->extension mtype)))

(def ^:private schema:download-font
  [:map {:title "download-font"}
   [:id ::sm/uuid]])

(sv/defmethod ::download-font
  "Download the font file. Returns a http redirect to the asset resource uri."
  {::doc/added "2.15"
   ::sm/params schema:download-font}
  [{:keys [::sto/storage ::db/pool] :as cfg} {:keys [::rpc/profile-id id]}]
  (let [variant (db/get pool :team-font-variant {:id id})]
    (teams/check-read-permissions! pool profile-id (:team-id variant))

    ;; Try to get the best available font format (prefer TTF for broader compatibility).
    (let [media-id (or (:ttf-file-id variant)
                       (:otf-file-id variant)
                       (:woff2-file-id variant)
                       (:woff1-file-id variant))
          sobj     (sto/get-object storage media-id)
          mtype    (-> sobj meta :content-type)]

      {:id (:id sobj)
       :uri (files/resolve-public-uri (:id sobj))
       :name (make-variant-filename variant mtype)})))

(def ^:private schema:download-font-family
  [:map {:title "download-font-family"}
   [:font-id ::sm/uuid]])

(sv/defmethod ::download-font-family
  "Download the entire font family as a zip file. Returns the zip
  bytes on the body, without encoding it on transit or json."
  {::doc/added "2.15"
   ::sm/params schema:download-font-family}
  [{:keys [::sto/storage ::db/pool] :as cfg} {:keys [::rpc/profile-id font-id]}]
  (let [variants (db/query pool :team-font-variant
                           {:font-id font-id
                            :deleted-at nil})]

    (when-not (seq variants)
      (ex/raise :type :not-found
                :code :object-not-found))

    (teams/check-read-permissions! pool profile-id (:team-id (first variants)))

    (let [tempfile (tmp/tempfile :suffix ".zip")
          ffamily  (-> variants first :font-family)]

      (with-open [^OutputStream output (io/output-stream tempfile)
                  ^OutputStream output (ZipOutputStream. output)]
        (doseq [v variants]
          (let [media-id (or (:ttf-file-id v)
                             (:otf-file-id v)
                             (:woff2-file-id v)
                             (:woff1-file-id v))
                sobj     (sto/get-object storage media-id)
                mtype    (-> sobj meta :content-type)
                name     (make-variant-filename v mtype)]

            (with-open [input (sto/get-object-data storage sobj)]
              (.putNextEntry ^ZipOutputStream output (ZipEntry. ^String name))
              (io/copy input output :size (:size sobj))
              (.closeEntry ^ZipOutputStream output)))))

      (let [{:keys [id] :as sobj} (make-temporal-storage-object cfg profile-id
                                                                {:mtype "application/zip"
                                                                 :path tempfile})]
        {:id id
         :uri (files/resolve-public-uri id)
         :name (str ffamily ".zip")}))))
