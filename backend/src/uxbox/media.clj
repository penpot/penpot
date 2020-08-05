;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; This Source Code Form is "Incompatible With Secondary Licenses", as
;; defined by the Mozilla Public License, v. 2.0.
;;
;; Copyright (c) 2020 UXBOX Labs SL

(ns uxbox.media
  "Media postprocessing."
  (:require
   [clojure.core.async :as a]
   [clojure.java.io :as io]
   [clojure.spec.alpha :as s]
   [datoteka.core :as fs]
   [mount.core :refer [defstate]]
   [uxbox.config :as cfg]
   [uxbox.common.data :as d]
   [uxbox.common.exceptions :as ex]
   [uxbox.common.spec :as us]
   [uxbox.media-storage :as mst]
   [uxbox.util.storage :as ust]
   [uxbox.util.http :as http])
  (:import
   java.io.ByteArrayInputStream
   java.io.InputStream
   java.util.concurrent.Semaphore
   org.im4java.core.ConvertCmd
   org.im4java.core.Info
   org.im4java.core.IMOperation))

(defstate semaphore
  :start (Semaphore. (:image-process-max-threads cfg/config 1)))

;; --- Thumbnails Generation

(s/def ::cmd keyword?)

(s/def ::path (s/or :path fs/path?
                    :string string?
                    :file fs/file?))
(s/def ::mtype string?)

(s/def ::input
  (s/keys :req-un [::path]
          :opt-un [::mtype]))

(s/def ::width integer?)
(s/def ::height integer?)
(s/def ::format #{:jpeg :webp :png :svg})
(s/def ::quality #(< 0 % 101))

(s/def ::thumbnail-params
  (s/keys :req-un [::cmd ::input ::format ::width ::height]))

;; Related info on how thumbnails generation
;;  http://www.imagemagick.org/Usage/thumbnails/

(defn format->extension
  [format]
  (case format
    :png  ".png"
    :jpeg ".jpg"
    :webp ".webp"
    :svg  ".svg"))

(defn format->mtype
  [format]
  (case format
    :png  "image/png"
    :jpeg "image/jpeg"
    :webp "image/webp"
    :svg  "image/svg+xml"))

(defn mtype->format
  [mtype]
  (case mtype
    "image/png"     :png
    "image/jpeg"    :jpeg
    "image/webp"    :webp
    "image/svg+xml" :svg
    nil))

(defn- generic-process
  [{:keys [input format quality operation] :as params}]
  (let [{:keys [path mtype]} input
        format (or (mtype->format mtype) format)
        ext    (format->extension format)
        tmp (fs/create-tempfile :suffix ext)]

    (doto (ConvertCmd.)
      (.run operation (into-array (map str [path tmp]))))

    (let [thumbnail-data (fs/slurp-bytes tmp)]
      (fs/delete tmp)
      (assoc params
             :format format
             :mtype  (format->mtype format)
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
    (if (= mtype "image/svg+xml")
      {:width 100
       :height 100
       :mtype mtype}
      (let [instance (Info. (str path))
            mtype'   (.getProperty instance "Mime type")]
        (when (and (string? mtype)
                   (not= mtype mtype'))
          (ex/raise :type :validation
                    :code :image-type-mismatch
                    :hint "Seems like you are uploading a file whose content does not match the extension."))
        {:width  (.getImageWidth instance)
         :height (.getImageHeight instance)
         :mtype  mtype'}))))

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

(defn resolve-urls
  [row src dst]
  (s/assert map? row)
  (if (and src dst)
    (let [src (if (vector? src) src [src])
          dst (if (vector? dst) dst [dst])
          value (get-in row src)]
      (if (empty? value)
        row
        (let [url (ust/public-uri mst/media-storage value)]
          (assoc-in row dst (str url)))))
    row))

(defn- resolve-uri
  [storage row src dst]
  (let [src (if (vector? src) src [src])
        dst (if (vector? dst) dst [dst])
        value (get-in row src)]
    (if (empty? value)
      row
      (let [url (ust/public-uri mst/media-storage value)]
        (assoc-in row dst (str url))))))

(defn resolve-media-uris
  [row & pairs]
  (us/assert map? row)
  (us/assert (s/coll-of vector?) pairs)
  (reduce #(resolve-uri mst/media-storage %1 (nth %2 0) (nth %2 1)) row pairs))

(defn download-media-object
  [url]
  (let [result (http/get! url {:as :byte-array})
        data (:body result)
        content-type (get (:headers result) "content-type")
        format (mtype->format content-type)]
    (if (nil? format)
      (ex/raise :type :validation
                :code :media-type-not-allowed
                :hint "Seems like the url points to an invalid media object.")
      (let [tempfile (fs/create-tempfile)
            base-filename (first (fs/split-ext (fs/name tempfile)))
            filename (str base-filename (format->extension format))]
        (with-open [ostream (io/output-stream tempfile)]
          (.write ostream data))
        {:filename filename
         :size (count data)
         :tempfile tempfile
         :content-type content-type}))))

