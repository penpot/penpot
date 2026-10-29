;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS SUBSIDIARY SL

(ns app.media
  "Media & Font postprocessing helpers.

  The processing itself is a job: the handlers of `app.media.jobs` call the
  local implementations in `app.media.local` (ImageMagick and FontForge).
  SVG sanitization is the one operation that is not a job: it always runs
  the local filter in `app.media.svg`, in this process.

  Validation and schemas live in app.media.validation (leaf namespace, no
  circular dep)."
  (:require
   [app.common.data :as d]
   [app.common.exceptions :as ex]
   [app.common.time :as ct]
   [app.common.uuid :as uuid]
   [app.config :as cf]
   [app.db :as-alias db]
   [app.http.client :as http]
   [app.jobs :as jobs]
   [app.media.jobs :as media.jobs]
   [app.media.sanitize :as sanitize]
   [app.media.svg :as svg]
   [app.media.validation :as validation]
   [app.storage :as-alias sto]
   [app.storage.tmp :as tmp]
   [cuerdas.core :as str]
   [datoteka.fs :as fs]
   [datoteka.io :as io]))

(defn sanitize-svg
  "Sanitize SVG `content` (a UTF-8 string) with the local filter and return
  it as text.

  SVG sanitization is deliberately local: it is not a job and the worker
  never sees it. A failure propagates, so the upload or the import fails
  instead of storing an unsanitized document."
  [_system content]
  (svg/sanitize-svg content))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; MEDIA JOBS
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(defn- svg-info
  "Read the dimensions of an SVG from its `viewBox`, `width` and `height`.

  The worker's image library cannot read an SVG, so this never becomes a
  job: the file is parsed here, where it already is a local file, exactly
  like the local executor does."
  [{:keys [path mtype size]}]
  (let [info (some-> path slurp svg/parse-svg svg/get-basic-info-from-svg)]
    (when-not info
      (ex/raise :type :validation
                :code :invalid-svg-file
                :hint "uploaded svg does not provides dimensions"))
    (merge {:mtype mtype}
           info
           {:size (long (or size (fs/size path)))
            :ts (ct/now)})))

(defn info
  "Resolve the info of a media `content`.

  An SVG is parsed here: the worker cannot read it and a dimension header
  does not deserve a round trip. Everything else runs `:media-info` as a
  durable job and waits for its result.

  The input is materialized to `tempfile` before the submit, in local and
  remote mode, and released whatever the outcome: the caller owns the
  reference and the job reads it. Answers the info map with `:ts`, the
  moment the caller learned it."
  [cfg content]
  (if (= "image/svg+xml" (:mtype content))
    (svg-info content)
    (let [input (media.jobs/materialize-input cfg content)]
      (try
        (-> (jobs/submit-and-wait cfg
                                  {::jobs/name   :media-info
                                   ::jobs/params {:input input}
                                   ::jobs/queue  (:fast (media.jobs/queue-pair))})
            (assoc :ts (ct/now)))
        (finally
          (media.jobs/release-temporary! cfg (:id input)))))))

(defn submit-thumbnail
  "Submit the `:media-thumbnail` job of a media object and answer its id, or
  nil when an active job of the same label already covers it.

  The input must be already materialized to `tempfile`: the job outlives
  the request. The caller releases it when the submit is skipped."
  [cfg {:keys [input media-id format quality width height]}]
  (jobs/submit cfg
               {::jobs/name   :media-thumbnail
                ::jobs/params {:input    input
                               :media-id media-id
                               :format   format
                               :quality  quality
                               :width    width
                               :height   height}
                ::jobs/queue  (:slow (media.jobs/queue-pair))
                ::jobs/label  (media.jobs/object-label media-id)
                ::jobs/skip-if-active true}))

(defn profile-photo
  "Run `:profile-thumbnail` and wait for the result.

  With a `profile-id` the job owns the profile and its hook makes the new
  photo the current one. Without it (profile creation imports the picture
  before the row exists) the hook leaves the result in `tempfile` and the
  caller stores it."
  [cfg {:keys [input profile-id]}]
  (jobs/submit-and-wait cfg
                        (cond-> {::jobs/name   :profile-thumbnail
                                 ::jobs/params {:input input}
                                 ::jobs/queue  (:fast (media.jobs/queue-pair))}
                          (uuid? profile-id)
                          (assoc ::jobs/profile-id profile-id))))

(defn submit-fonts
  "Submit the `:font-variants` job of a variant row and answer its id.

  The input must be already materialized. There is no `skip-if-active`: the
  caller cancels the previous job of the row by label and submits the new
  one, so a re-upload always wins."
  [cfg {:keys [input variant-id source-mtype targets]}]
  (jobs/submit cfg
               {::jobs/name   :font-variants
                ::jobs/params {:input        input
                               :variant-id   variant-id
                               :source-mtype source-mtype
                               :targets      targets}
                ::jobs/queue  (:slow (media.jobs/queue-pair))
                ::jobs/label  (media.jobs/font-label variant-id)}))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; IMAGE HELPERS
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(defn download-image
  "Download an image from the provided URI and return the media input object"
  [{:keys [::http/client]} uri]
  (letfn [(parse-and-validate [{:keys [status headers] :as response}]
            (let [size     (some-> (get headers "content-length") d/parse-integer)
                  mtype    (get headers "content-type")]

              (when-not (<= 200 status 299)
                (ex/raise :type :validation
                          :code :unable-to-download-image
                          :hint (str/ffmt "unable to download image from '%': unexpected status code %" uri status)))

              (when-not size
                (ex/raise :type :validation
                          :code :unknown-size
                          :hint "seems like the url points to resource with unknown size"))

              (-> {:size size :mtype mtype}
                  (validation/validate-media-type!)
                  (validation/validate-media-size!))))]

    (let [{:keys [body] :as response}
          (try
            (http/req-with-redirects
             client
             {:method :get :uri uri}
             {:response-type :input-stream
              :max-redirects 3})
            (catch java.net.ConnectException cause
              (ex/raise :type :validation
                        :code :unable-to-download-image
                        :hint (str/ffmt "unable to download image from '%': connection refused or host unreachable" uri)
                        :cause cause))
            (catch java.net.http.HttpConnectTimeoutException cause
              (ex/raise :type :validation
                        :code :unable-to-download-image
                        :hint (str/ffmt "unable to download image from '%': connection timeout" uri)
                        :cause cause))
            (catch java.net.http.HttpTimeoutException cause
              (ex/raise :type :validation
                        :code :unable-to-download-image
                        :hint (str/ffmt "unable to download image from '%': request timeout" uri)
                        :cause cause))
            (catch java.io.IOException cause
              (ex/raise :type :validation
                        :code :unable-to-download-image
                        :hint (str/ffmt "unable to download image from '%': I/O error" uri)
                        :cause cause)))]

      (if body
        (with-open [body body]
          (let [{:keys [size mtype]} (parse-and-validate response)
                path    (tmp/tempfile :prefix "penpot.media.download.")
                written (io/write* path body :size size)]

            (when (not= written size)
              (ex/raise :type :internal
                        :code :mismatch-write-size
                        :hint "unexpected state: unable to write to file"))

            ;; Sanitize: strip trailing data after image EOF markers
            (let [new-size (sanitize/truncate-after-eof path mtype)]
              {:path  path
               :mtype mtype
               :size  new-size})))

        ;; No body - validation will raise appropriate error
        (parse-and-validate response)))))
