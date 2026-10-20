;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS SUBSIDIARY SL

(ns app.jobs.storage
  "Storage helpers for the objects a job owns through `job.resource_id`.

  A job resource lives in the `job-resource` bucket and belongs to the
  profile that owns the job, so only that profile can read it back (the
  asset route checks the owner, not just the session).

  The job row is what keeps the object alive: `storage-gc-touched`
  freezes it while a row references it and deletes it once no row does,
  and the jobs GC marks it as touched when it removes the row. The object
  carries no expiry of its own: `expired-at` is stored as `deleted_at`,
  which makes the object unreadable from birth, so the retention of the
  artifact is the retention of its job."
  (:require
   [app.common.exceptions :as ex]
   [app.common.logging :as l]
   [app.common.time :as ct]
   [app.config :as cf]
   [app.db :as db]
   [app.storage :as sto]
   [app.storage.tmp :as tmp]))

(defn- resource-uri
  "The url a user downloads the artifact from. A string, because the
  descriptor ends up in the JSON result of the job."
  [id]
  (cf/get-public-uri "assets/by-id/" (str id)))

(defn put-resource
  "Store an artifact of a job in the `job-resource` bucket and return the
  descriptor that identifies it.

  Named options:

  - `:content`  the artifact, an `app.storage/content` value built from a
                temporary file or from bytes in memory
  - `:filename` the name the user will see
  - `:mtype`    the content type of the artifact

  `profile-id` is the owner of the object: the profile that owns the job,
  for both the package a job consumes and the artifact it produces.

  The object is marked as touched right away, so an artifact that no job
  row ever references (a crash between the upload and the completion) is
  reclaimed by the storage GC instead of leaking; while a row references
  it, `storage-gc-touched` freezes it."
  [cfg profile-id {:keys [content filename mtype]}]
  (assert (uuid? profile-id) "expected the profile that owns the resource")
  (assert (string? mtype) "expected the content type of the resource")
  (let [storage (sto/resolve cfg)
        object  (sto/put-object! storage
                                 {::sto/content    content
                                  ::sto/touched-at (ct/now)
                                  :bucket          sto/job-resource-bucket
                                  :profile-id      profile-id
                                  :content-type    mtype})]
    {:resource-id  (:id object)
     :resource-uri (resource-uri (:id object))
     :filename     filename
     :mtype        mtype
     :size         (:size object)}))

(defn- check-input
  "The object a job consumes must exist, be a job resource and belong to
  the profile that owns the job. Raises `:not-found` otherwise: a handler
  never reads another profile's package."
  [context object]
  (when (or (nil? object)
            (not= sto/job-resource-bucket (:bucket (meta object)))
            (not= (:profile-id context) (:profile-id (meta object))))
    (ex/raise :type :not-found
              :code :job-resource-not-found
              :hint "the input of the job is not available"
              :job-id (:id context)
              :resource-id (:resource-id context)))
  object)

(defn load-input
  "Download the package a job consumes to a local temporary file and
  return its path, ready for the binfile reader.

  An object that was released or expired is already invisible to
  `sto/get-object`, so it lands in the same `:not-found` as a missing
  one."
  [cfg context]
  (let [storage     (sto/resolve cfg)
        resource-id (:resource-id context)
        object      (check-input context
                                 (when (uuid? resource-id)
                                   (sto/get-object storage resource-id)))]
    (if-let [input (sto/get-object-data storage object)]
      (tmp/tempfile-from input)
      (ex/raise :type :not-found
                :code :job-resource-not-found
                :hint "the input of the job is not readable"
                :job-id (:id context)
                :resource-id resource-id))))

(defn release-resource
  "Release a job resource.

  The object is marked as deleted and, when it belongs to a job, the
  row stops pointing at it in the same transaction: a row never
  references an object that is already gone, and the foreign key is not
  what ends up clearing the reference. A resource no job owns (the
  package of a creation that never submitted) is released with a nil
  job id, and then no row is touched at all.

  Best effort: a failure is logged and never changes the terminal state
  of the job, and releasing without a resource is a no-op. Returns true
  when the object was marked as deleted or a stale reference was
  cleared."
  ([cfg resource-id]
   (release-resource cfg resource-id nil))
  ([cfg resource-id job-id]
   (when (uuid? resource-id)
     (try
       (db/tx-run!
        cfg
        (fn [tx-cfg]
          (let [storage  (sto/resolve tx-cfg ::db/reuse-conn true)
                deleted? (sto/del-object! storage resource-id)
                ;; the job no longer owns the package: the pointer goes with
                ;; it, so clearing it does not depend on the object being
                ;; physically deleted later
                cleared? (when (some? job-id)
                           (db/update! tx-cfg :job
                                       {:resource-id nil :modified-at (ct/now)}
                                       ["id = ? AND resource_id = ?"
                                        job-id resource-id]
                                       {::db/return-keys [:id]}))]
            (or deleted? (some? cleared?)))))
       (catch Throwable cause
         (l/wrn :hint "unable to release the input of a job"
                :job-id (str job-id)
                :resource-id (str resource-id)
                :cause cause)
         false)))))

(defn release-input
  "Release the package a job consumed.

  Delegates to `release-resource` with the id of the job: the object
  and the pointer are dropped together. Best effort, like there."
  [cfg context]
  (release-resource cfg (:resource-id context) (:id context)))
