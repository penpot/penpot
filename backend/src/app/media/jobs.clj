;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS SUBSIDIARY SL

(ns app.media.jobs
  "The shared contract of the media jobs.

  The RPC layer, the local runner and the media worker all read the same
  names, params and results from here, so the contract is written once. It
  also owns the data plane of a media job:

  - Every input is materialized to the `tempfile` bucket before the submit,
    in local and remote mode. Params carry the reference, never a local
    path, so a job survives the request that created it and a restart.
  - Every result is uploaded to `tempfile` (the local handler with
    `upload-result`, the worker with `upload-tempfile`) and the terminal
    hook moves it to its final bucket with `store-result`, which deletes the
    temporary object.

  Nothing here runs a job: the executors are the local handlers (which call
  `app.media.local`) and the media worker."
  (:require
   [app.common.exceptions :as ex]
   [app.common.logging :as l]
   [app.common.media :as cm]
   [app.common.schema :as sm]
   [app.common.time :as ct]
   [app.config :as cf]
   [app.db :as db]
   [app.jobs :as jobs]
   [app.media.local :as media.local]
   [app.storage :as sto]
   [app.storage.tmp :as tmp]
   [datoteka.fs :as fs]
   [datoteka.io :as io]
   [integrant.core :as ig])
  (:import
   java.io.InputStream))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; SCHEMAS
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(def schema:storage-input
  "The reference of an input object in the `tempfile` bucket: what a job
  receives instead of a local path."
  [:map {:closed true}
   [:id ::sm/uuid]
   [:mtype ::sm/text]
   [:size ::sm/int]])

(def schema:storage-result
  "A binary result the handler uploaded to `tempfile`. The worker answers
  exactly this shape from `upload-tempfile`, and `upload-result` mirrors it
  for the local handler."
  [:map {:closed true}
   [:id ::sm/uuid]
   [:uri ::sm/text]
   [:mtype ::sm/text]
   [:size ::sm/int]])

;; --- media-info

(def schema:media-info
  [:map {:closed true}
   [:input schema:storage-input]])

(def schema:media-info-result
  [:map {:closed true}
   [:width ::sm/int]
   [:height ::sm/int]
   [:mtype ::sm/text]
   [:size ::sm/int]
   [:orientation {:optional true} ::sm/int]])

;; --- media-thumbnail

(def schema:media-thumbnail
  [:map {:closed true}
   [:input schema:storage-input]
   ;; `file_media_object.media_id` of every row the hook updates.
   [:media-id ::sm/uuid]
   [:format [:enum :jpeg :webp :png]]
   [:quality [:int {:min 1 :max 100}]]
   [:width ::sm/int]
   [:height ::sm/int]])

(def schema:media-thumbnail-result
  [:map {:closed true}
   [:thumbnail schema:storage-result]])

;; --- profile-thumbnail

(def schema:profile-thumbnail
  [:map {:closed true}
   ;; The allowed photo formats are checked at the job boundary, before the
   ;; handler touches the file.
   [:input [:map {:closed true}
            [:id ::sm/uuid]
            [:mtype [:enum "image/jpeg" "image/png" "image/webp"]]
            [:size ::sm/int]]]])

(def schema:profile-thumbnail-result
  [:map {:closed true}
   [:photo schema:storage-result]])

;; --- font-variants

(def schema:font-variants
  [:map {:closed true}
   [:input schema:storage-input]
   ;; `team_font_variant.id` of the row the hook fills.
   [:variant-id ::sm/uuid]
   ;; Mime type of the uploaded source, which decides how to convert it.
   [:source-mtype ::sm/text]
   ;; Mime types to generate; never `font/woff2`.
   [:targets [:vector ::sm/text]]])

(def schema:font-variants-result
  [:map {:closed true}
   [:variants [:vector schema:storage-result]]])

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; FONT VARIANTS
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(def known-font-types
  "Font mime types the system can convert, in priority order: when an
  upload carries several variants, the first match is the conversion
  source (ttf gives the best coverage)."
  ["font/ttf" "font/otf" "font/woff" "font/woff2"])

(defn missing-variants
  "Target mime types to generate from `source-mtype`. `font/woff2` is never
  generated: it is only ever an uploaded source."
  [source-mtype]
  (case source-mtype
    "font/ttf"   #{"font/otf" "font/woff"}
    "font/otf"   #{"font/ttf" "font/woff"}
    "font/woff"  #{"font/ttf" "font/otf"}
    "font/woff2" #{"font/ttf" "font/otf" "font/woff"}
    #{}))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; QUEUES AND LABELS
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(defn queue-pair
  "The fast (synchronous) and slow (asynchronous) queues of the media jobs.

  The remote flag changes the executor, never the contract: with remote
  processing on the worker consumes the jobs, with it off the backend
  runners do. `:media-info` and `:profile-thumbnail` are synchronous and go
  to the fast queue; `:media-thumbnail` and `:font-variants` are
  asynchronous and go to the slow one, so a fan-out cannot block a upload."
  []
  (if (contains? cf/flags :remote-media-processing)
    {:fast :media-fast
     :slow :media}
    {:fast :media-local-fast
     :slow :media-local}))

(defn object-label
  "Label of the thumbnail job of a media object: one per storage object, so
  a re-upload of the same object can find the job in flight."
  [storage-object-id]
  (str "media:" storage-object-id))

(defn font-label
  "Label of the variant generation job of a font variant row."
  [variant-id]
  (str "font:" variant-id))

(defn profile-label
  "Label of the photo job of a profile."
  [profile-id]
  (str "profile:" profile-id))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; THUMBNAIL FORMAT
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(def ^:private output-formats
  #{:jpeg :webp :png})

(defn normalize-thumbnail-format
  "The output format a thumbnail job may ask for. The worker only knows
  `jpeg`, `webp` and `png`: a missing format falls back to the natural one
  of the input when it is one of them, and to `jpeg` otherwise (an animated
  GIF or an SVG has no still output format of its own)."
  ([source-mtype]
   (normalize-thumbnail-format source-mtype nil))
  ([source-mtype format]
   (let [format (or format (cm/mtype->format source-mtype))]
     (if (contains? output-formats format)
       format
       :jpeg))))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; DATA PLANE
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(def ^:private temporary-touch-window
  "How long an unreferenced temporary object lives before `touched-gc`
  reclaims it. It covers a queued job plus one retry."
  (ct/duration {:minutes 10}))

(defn materialize-input
  "Upload a local file to the `tempfile` bucket and answer the reference a
  job receives.

  Every job input goes through here, in local and remote mode: the params
  carry the reference and never a local path. The object is ownerless and
  short-lived; the terminal hook releases it and `touched-gc` is the safety
  net."
  [system {:keys [path mtype size]}]
  (let [storage (sto/resolve system)
        object  (sto/put-object! storage
                                 {::sto/content      (sto/content path)
                                  ::sto/deduplicate? false
                                  ::sto/touched-at   (ct/in-future temporary-touch-window)
                                  :content-type      mtype
                                  :bucket            sto/tempfile-bucket})]
    {:id    (:id object)
     :mtype mtype
     :size  (long (or size (fs/size path)))}))

(defn upload-result
  "Upload the artifact a local handler produced to the `tempfile` bucket and
  answer `{id, uri, mtype, size}`: the same shape the worker answers from
  `upload-tempfile`, so the terminal hook does not care which executor ran
  the job.

  Accepts a path or a byte array: the local font conversion answers some
  variants as bytes, not as a temporary file."
  [system resource {:keys [mtype]}]
  (let [storage (sto/resolve system)
        size    (if (bytes? resource)
                  (long (alength ^bytes resource))
                  (long (fs/size resource)))
        object  (sto/put-object! storage
                                 {::sto/content      (sto/content resource)
                                  ::sto/deduplicate? false
                                  ::sto/touched-at   (ct/in-future temporary-touch-window)
                                  :content-type      mtype
                                  :bucket            sto/tempfile-bucket})]
    {:id    (:id object)
     :uri   (cf/get-public-uri "assets/by-id/" (str (:id object)))
     :mtype mtype
     :size  size}))

(defn release-temporary!
  "Mark a storage object as deleted. Idempotent and never fatal: a leftover
  object is reclaimed by `touched-gc`, so cleanup never decides the outcome
  of a job. Used for temporary objects and for a final object that nobody
  ended up referencing."
  [system id]
  (when (some? id)
    (try
      (sto/del-object! (sto/resolve system) id)
      (catch Throwable cause
        (l/wrn :hint "unable to release a temporary media object"
               :id (str id)
               :cause cause)))))

(defn- media-result-expired!
  [id]
  (ex/raise :type :media-result-expired
            :code :media-result-expired
            :hint "the media result object is no longer readable"
            :id (str id)))

(defn store-result
  "Move the artifact a job produced from `tempfile` to its final `bucket`
  and delete the temporary object.

  `metadata` carries the bucket metadata the final object needs, at least
  `:content-type` (plus the reference ids the bucket requires). Answers the
  new storage object, or raises `:media-result-expired` when the temporary
  object is gone or expired: the writer turns that into a terminal failure
  instead of leaving the job running."
  [system result-id bucket metadata]
  (let [storage (sto/resolve system)
        object  (some-> (sto/get-object storage result-id))]
    (if (nil? object)
      (media-result-expired! result-id)
      ;; Stream the artifact through a local file instead of loading the
      ;; whole object in memory; the hook may run with a row locked.
      (let [input (or (sto/get-object-data storage object)
                      (media-result-expired! result-id))
            path  (tmp/tempfile :prefix "penpot.media.result." :min-age "1h")]
        (try
          (io/write* path input)
          (sto/put-object! storage
                           (merge {::sto/content      (sto/content path)
                                   ::sto/deduplicate? false
                                   ::sto/touched-at   (ct/now)}
                                  metadata
                                  {:bucket bucket}))
          (finally
            (.close ^InputStream input)
            (fs/delete path)
            (release-temporary! system result-id)))))))

(defn fetch-input
  "Copy the referenced `tempfile` object to a local file the local handlers
  can read. The caller owns the file and deletes it.

  A missing or expired object is an internal failure: the job cannot run,
  and the retry budget is what stops it."
  [system {:keys [id mtype]}]
  (let [storage (sto/resolve system)
        object  (some-> (sto/get-object storage id))]
    (when (nil? object)
      (ex/raise :type :internal
                :code :media-input-not-found
                :hint "the media input object does not exist"
                :id (str id)))
    (if-let [^InputStream input (sto/get-object-data storage object)]
      (let [path (tmp/tempfile :prefix "penpot.media."
                               :suffix (cm/mtype->extension mtype))]
        (try
          (io/write* path input)
          (finally
            (.close input)))
        path)
      (ex/raise :type :internal
                :code :media-input-expired
                :hint "the media input object is expired or unreadable"
                :id (str id)))))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; JOB: media-info
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(defn execute-media-info
  "Handler of the `:media-info` job.

  Resolves the input reference to a local file, extracts the info with the
  local implementation (an SVG is parsed, everything else goes through
  ImageMagick) and answers the typed result. It produces no artifact and
  has no terminal hook: the job row is the whole story."
  [cfg {:keys [input]}]
  (let [path (fetch-input cfg input)]
    (try
      (let [info (media.local/process cfg {:cmd   :info
                                           :input {:path  path
                                                   :mtype (:mtype input)}})]
        (select-keys info [:width :height :mtype :size :orientation]))
      (finally
        (fs/delete path)))))

(defmethod ig/init-key ::media-info-job
  [_ cfg]
  {::jobs/name          :media-info
   ::jobs/schema        schema:media-info
   ::jobs/handler       (fn [_context params]
                          (execute-media-info cfg params))
   ::jobs/decoder       (sm/decoder schema:media-info sm/json-transformer)
   ::jobs/validator     (sm/validator schema:media-info)
   ::jobs/result-schema schema:media-info-result
   ;; synchronous: the caller waits for the result on the reply key
   ::jobs/notifies?     true})

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; JOB: media-thumbnail
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(defn execute-media-thumbnail
  "Handler of the `:media-thumbnail` job.

  Generates the thumbnail from the materialized input, uploads it to
  `tempfile` and answers `{thumbnail: result}`. The terminal hook moves it
  to the final bucket."
  [cfg {:keys [input format quality width height]}]
  (let [path (fetch-input cfg input)]
    (try
      (let [thumb (media.local/process cfg {:cmd    :generic-thumbnail
                                            :input  {:path  path
                                                     :mtype (:mtype input)}
                                            :format format
                                            :quality quality
                                            :width  width
                                            :height height})]
        (try
          {:thumbnail (upload-result cfg (:data thumb) {:mtype (:mtype thumb)})}
          (finally
            (fs/delete (:data thumb)))))
      (finally
        (fs/delete path)))))

(def ^:private sql:attach-thumbnail
  "UPDATE file_media_object
      SET thumbnail_id = ?
    WHERE media_id = ?
      AND thumbnail_id IS NULL
    RETURNING id")

(defn- attach-thumbnail!
  "Move the result to the final bucket and attach it to every live row of
  the media object that still has no thumbnail.

  When no row needs it (the media object was deleted, or another attempt
  won the race) the new object is deleted too, so a stale artifact never
  leaks. Answers how many rows were updated."
  [system tx-cfg media-id result]
  (let [thumbnail (get result :thumbnail)
        object    (store-result system (:id thumbnail) "file-media-object"
                                {:content-type (:mtype thumbnail)})
        updated   (db/exec! tx-cfg [sql:attach-thumbnail (:id object) media-id])]
    (when (empty? updated)
      (release-temporary! system (:id object)))
    (count updated)))

(defn media-thumbnail-on-terminal
  "Terminal hook of `:media-thumbnail`.

  On completion it applies the result; on failure or cancellation there is
  nothing to apply. The input is released on every outcome. A result object
  that is gone raises `:media-result-expired`, which `complete` turns into a
  terminal failure."
  [system tx-cfg {:keys [input media-id]} {:keys [outcome result]}]
  (try
    (when (= :completed outcome)
      (attach-thumbnail! system tx-cfg media-id result))
    (finally
      (release-temporary! system (:id input)))))

(defmethod ig/init-key ::media-thumbnail-job
  [_ cfg]
  {::jobs/name          :media-thumbnail
   ::jobs/schema        schema:media-thumbnail
   ::jobs/handler       (fn [_context params]
                          (execute-media-thumbnail cfg params))
   ::jobs/decoder       (sm/decoder schema:media-thumbnail sm/json-transformer)
   ::jobs/validator     (sm/validator schema:media-thumbnail)
   ::jobs/result-schema schema:media-thumbnail-result
   ::jobs/on-terminal   (fn [tx-cfg _job params outcome]
                          (media-thumbnail-on-terminal cfg tx-cfg params outcome))})

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; JOB: font-variants
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(defn execute-font-variants
  "Handler of the `:font-variants` job.

  Generates the requested variants from the materialized source, uploads
  each one to `tempfile` and answers `{variants: [result]}`. The terminal
  hook fills the null columns of the row."
  [cfg {:keys [input source-mtype targets]}]
  (let [path (fetch-input cfg input)]
    (try
      (let [data (media.local/process cfg {:cmd   :generate-fonts
                                           :input {source-mtype path}})]
        (try
          {:variants
           (into []
                 (keep (fn [mtype]
                         (when-let [resource (get data mtype)]
                           (let [result (upload-result cfg resource {:mtype mtype})]
                             (when (and (not (bytes? resource))
                                        (fs/exists? resource))
                               (fs/delete resource))
                             result))))
                 targets)}
          (finally
            ;; the files already uploaded were deleted in place; do not fail
            ;; on one that is gone (and skip the byte results)
            (doseq [[mtype resource] data]
              (when (and (some? resource)
                         (not (bytes? resource))
                         (not= mtype source-mtype)
                         (fs/exists? resource))
                (fs/delete resource))))))
      (finally
        (fs/delete path)))))

(def ^:private variant-columns
  "Mime type of a variant and the `team_font_variant` column that holds it."
  {"font/ttf"   :ttf-file-id
   "font/otf"   :otf-file-id
   "font/woff"  :woff1-file-id
   "font/woff2" :woff2-file-id})

(def ^:private sql:attach-font-variants
  "UPDATE team_font_variant
      SET ttf_file_id   = COALESCE(ttf_file_id, ?),
          otf_file_id   = COALESCE(otf_file_id, ?),
          woff1_file_id = COALESCE(woff1_file_id, ?),
          woff2_file_id = COALESCE(woff2_file_id, ?)
    WHERE id = ?
      AND deleted_at IS NULL
    RETURNING ttf_file_id, otf_file_id, woff1_file_id, woff2_file_id")

(defn- attach-font-variants!
  "Move every variant to the final bucket and fill the null columns of the
  row, never overwriting one that already has a file.

  A missing row and a column that was already filled are not errors: the
  new object is deleted so a stale artifact never leaks. A failure halfway
  deletes everything this run moved before it rethrows."
  [system tx-cfg variant-id result]
  (let [variants (:variants result)
        stored   (volatile! {})]
    (try
      (doseq [{:keys [mtype id]} variants]
        (if-let [column (get variant-columns mtype)]
          (let [object (store-result system id "team-font-variant"
                                     {:content-type mtype})]
            (vswap! stored assoc column (:id object)))
          (release-temporary! system id)))

      (if-let [row (db/exec-one! tx-cfg
                                 [sql:attach-font-variants
                                  (get @stored :ttf-file-id)
                                  (get @stored :otf-file-id)
                                  (get @stored :woff1-file-id)
                                  (get @stored :woff2-file-id)
                                  variant-id])]
        (doseq [[column id] @stored]
          (when (not= id (get row column))
            (release-temporary! system id)))
        (doseq [id (vals @stored)]
          (release-temporary! system id)))

      (catch Throwable cause
        ;; The transaction rolls back, but the storage writes do not: do not
        ;; leave what this run moved nor the results it did not consume.
        (doseq [id (vals @stored)]
          (release-temporary! system id))
        (doseq [{:keys [id]} variants]
          (release-temporary! system id))
        (throw cause)))))

(defn font-variants-on-terminal
  "Terminal hook of `:font-variants`.

  On completion it fills the row; on failure or cancellation there is
  nothing to fill. The input is released on every outcome."
  [system tx-cfg {:keys [input variant-id]} {:keys [outcome result]}]
  (try
    (when (= :completed outcome)
      (attach-font-variants! system tx-cfg variant-id result))
    (finally
      (release-temporary! system (:id input)))))

(defmethod ig/init-key ::font-variants-job
  [_ cfg]
  {::jobs/name          :font-variants
   ::jobs/schema        schema:font-variants
   ::jobs/handler       (fn [_context params]
                          (execute-font-variants cfg params))
   ::jobs/decoder       (sm/decoder schema:font-variants sm/json-transformer)
   ::jobs/validator     (sm/validator schema:font-variants)
   ::jobs/result-schema schema:font-variants-result
   ::jobs/on-terminal   (fn [tx-cfg _job params outcome]
                          (font-variants-on-terminal cfg tx-cfg params outcome))})

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; JOB: profile-thumbnail
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(def ^:private profile-thumbnail-options
  {:format :jpeg
   :quality 85
   :width 256
   :height 256})

(defn execute-profile-thumbnail
  "Handler of the `:profile-thumbnail` job.

  Generates the 256×256 JPEG of the materialized photo, uploads it to
  `tempfile` and answers `{photo: result}`. The terminal hook moves it to
  the `profile` bucket and updates the profile."
  [cfg {:keys [input]}]
  (let [path (fetch-input cfg input)]
    (try
      (let [thumb (media.local/process cfg (assoc profile-thumbnail-options
                                                  :cmd   :profile-thumbnail
                                                  :input {:path  path
                                                          :mtype (:mtype input)}))]
        (try
          {:photo (upload-result cfg (:data thumb) {:mtype (:mtype thumb)})}
          (finally
            (fs/delete (:data thumb)))))
      (finally
        (fs/delete path)))))

(def ^:private sql:lock-profile
  "SELECT id, photo_id FROM profile
    WHERE id = ?
    FOR UPDATE")

(defn- attach-profile-photo!
  "Move the new photo to the `profile` bucket and make it the photo of the
  profile, touching the previous one.

  The profile row is locked before the artifact is moved: when the profile
  is gone the temporary result is released and nothing is stored."
  [system tx-cfg profile-id result]
  (let [row (db/exec-one! tx-cfg [sql:lock-profile profile-id])]
    (if (nil? row)
      (release-temporary! system (get-in result [:photo :id]))
      (let [photo  (get result :photo)
            object (store-result system (:id photo) "profile"
                                 {:content-type (:mtype photo)})]
        (when-let [old-id (:photo-id row)]
          (sto/touch-object! (sto/resolve system) old-id))
        (db/update! tx-cfg :profile
                    {:photo-id (:id object)}
                    {:id profile-id})
        (:id object)))))

(defn profile-thumbnail-on-terminal
  "Terminal hook of `:profile-thumbnail`.

  On completion it makes the new photo the profile photo; on failure or
  cancellation there is nothing to apply. The input is released on every
  outcome and the profile is never touched by a non-completed job."
  [system tx-cfg job {:keys [input]} {:keys [outcome result]}]
  (try
    (when (= :completed outcome)
      ;; Without an owner (profile creation imports the picture before the
      ;; row exists) the result stays in `tempfile` for the caller to store.
      (when-let [profile-id (:profile-id job)]
        (attach-profile-photo! system tx-cfg profile-id result)))
    (finally
      (release-temporary! system (:id input)))))

(defmethod ig/init-key ::profile-thumbnail-job
  [_ cfg]
  {::jobs/name          :profile-thumbnail
   ::jobs/schema        schema:profile-thumbnail
   ::jobs/handler       (fn [_context params]
                          (execute-profile-thumbnail cfg params))
   ::jobs/decoder       (sm/decoder schema:profile-thumbnail sm/json-transformer)
   ::jobs/validator     (sm/validator schema:profile-thumbnail)
   ::jobs/result-schema schema:profile-thumbnail-result
   ::jobs/notifies?     true
   ::jobs/on-terminal   (fn [tx-cfg job params outcome]
                          (profile-thumbnail-on-terminal cfg tx-cfg job params outcome))})
