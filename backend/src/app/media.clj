;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; This Source Code Form is "Incompatible With Secondary Licenses", as
;; defined by the Mozilla Public License, v. 2.0.
;;
;; Copyright (c) 2020 UXBOX Labs SL

(ns app.media
  "Media postprocessing."
  (:require
   [app.common.exceptions :as ex]
   [app.common.media :as cm]
   [app.common.spec :as us]
   [app.config :as cfg]
   [app.util.http :as http]
   [clojure.core.async :as a]
   [clojure.java.io :as io]
   [clojure.spec.alpha :as s]
   [datoteka.core :as fs])
  (:import
   java.io.ByteArrayInputStream
   java.util.concurrent.Semaphore
   org.im4java.core.ConvertCmd
   org.im4java.core.IMOperation
   org.im4java.core.Info))

(def semaphore (Semaphore. (:image-process-max-threads cfg/config 1)))

;; --- Generic specs

(s/def :internal.http.upload/filename ::us/string)
(s/def :internal.http.upload/size ::us/integer)
(s/def :internal.http.upload/content-type cm/valid-media-types)
(s/def :internal.http.upload/tempfile any?)

(s/def ::upload
  (s/keys :req-un [:internal.http.upload/filename
                   :internal.http.upload/size
                   :internal.http.upload/tempfile
                   :internal.http.upload/content-type]))


;; --- Thumbnails Generation

(s/def ::cmd keyword?)

(s/def ::path (s/or :path fs/path?
                    :string string?
                    :file fs/file?))

(s/def ::input
  (s/keys :req-un [::path]
          :opt-un [::cm/mtype]))

(s/def ::width integer?)
(s/def ::height integer?)
(s/def ::format #{:jpeg :webp :png})
(s/def ::quality #(< 0 % 101))

(s/def ::thumbnail-params
  (s/keys :req-un [::cmd ::input ::format ::width ::height]))

;; Related info on how thumbnails generation
;;  http://www.imagemagick.org/Usage/thumbnails/

(defn- generic-process
  [{:keys [input format operation] :as params}]
  (let [{:keys [path mtype]} input
        format (or (cm/mtype->format mtype) format)
        ext    (cm/format->extension format)
        tmp (fs/create-tempfile :suffix ext)]

    (doto (ConvertCmd.)
      (.run operation (into-array (map str [path tmp]))))

    (let [thumbnail-data (fs/slurp-bytes tmp)]
      (fs/delete tmp)
      (assoc params
             :format format
             :mtype  (cm/format->mtype format)
             :data   (ByteArrayInputStream. thumbnail-data)))))

(defmulti process :cmd)

(defmethod process :generic-thumbnail
  [{:keys [quality width height] :as params}]
  (us/assert ::thumbnail-params params)
  (let [op (doto (IMOperation.)
             (.addImage)
             (.autoOrient)
             (.strip)
             (.thumbnail (int width) (int height) ">")
             (.quality (double quality))
             (.addImage))]
    (generic-process (assoc params :operation op))))

(defmethod process :profile-thumbnail
  [{:keys [quality width height] :as params}]
  (us/assert ::thumbnail-params params)
  (let [op (doto (IMOperation.)
             (.addImage)
             (.autoOrient)
             (.strip)
             (.thumbnail (int width) (int height) "^")
             (.gravity "center")
             (.extent (int width) (int height))
             (.quality (double quality))
             (.addImage))]
    (generic-process (assoc params :operation op))))

(defmethod process :info
  [{:keys [input] :as params}]
  (us/assert ::input input)
  (let [{:keys [path mtype]} input]
    (let [instance (Info. (str path))

          ;; SVG files are converted to PNG to extract their properties
          mtype (if (= mtype "image/svg+xml") "image/png" mtype)
          mtype'   (.getProperty instance "Mime type")]
      (when (and (string? mtype)
                 (not= mtype mtype'))
        (ex/raise :type :validation
                  :code :media-type-mismatch
                  :hint (str "Seems like you are uploading a file whose content does not match the extension."
                             "Expected: " mtype "Got: " mtype')))
      {:width  (.getImageWidth instance)
       :height (.getImageHeight instance)
       :mtype  mtype'})))

(defmethod process :default
  [{:keys [cmd] :as params}]
  (ex/raise :type :internal
            :code :not-implemented
            :hint (str "No impl found for process cmd:" cmd)))

(defn run
  [params]
  (try
    (.acquire semaphore)
    (let [res (a/<!! (a/thread
                       (try
                         (process params)
                         (catch Throwable e
                           e))))]
      (if (instance? Throwable res)
        (throw res)
        res))
    (finally
      (.release semaphore))))


;; --- Utility functions

(defn validate-media-type
  [media-type]
  (when-not (cm/valid-media-types media-type)
    (ex/raise :type :validation
              :code :media-type-not-allowed
              :hint "Seems like you are uploading an invalid media object")))


;; TODO: rewrite using jetty http client instead of jvm
;; builtin (because builtin http client uses a lot of memory for the
;; same operation.

(defn download-media-object
  [url]
  (let [result (http/get! url {:as :byte-array})
        data (:body result)
        content-type (get (:headers result) "content-type")
        format (cm/mtype->format content-type)]
    (if (nil? format)
      (ex/raise :type :validation
                :code :media-type-not-allowed
                :hint "Seems like the url points to an invalid media object.")
      (let [tempfile (fs/create-tempfile)
            base-filename (first (fs/split-ext (fs/name tempfile)))
            filename (str base-filename (cm/format->extension format))]
        (with-open [ostream (io/output-stream tempfile)]
          (.write ostream data))
        {:filename filename
         :size (count data)
         :tempfile tempfile
         :content-type content-type}))))

