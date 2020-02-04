;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) 2016-2017 Andrey Antukh <niwi@niwi.nz>

(ns uxbox.images
  "Image postprocessing."
  (:require
   [clojure.java.io :as io]
   [clojure.spec.alpha :as s]
   [datoteka.core :as fs]
   [uxbox.common.data :as d]
   [uxbox.common.spec :as us]
   [uxbox.util.storage :as ust]
   [uxbox.media :as media])
  (:import
   java.io.ByteArrayInputStream
   java.io.InputStream
   org.im4java.core.ConvertCmd
   org.im4java.core.Info
   org.im4java.core.IMOperation))

;; --- Helpers

(defn format->extension
  [format]
  (case format
    "jpeg" ".jpg"
    "webp" ".webp"))

(defn format->mtype
  [format]
  (case format
    "jpeg" "image/jpeg"
    "webp" "image/webp"))

;; --- Thumbnails Generation

(s/def ::width integer?)
(s/def ::height integer?)
(s/def ::quality #(< 0 % 101))
(s/def ::format #{"jpeg" "webp"})
(s/def ::thumbnail-opts
  (s/keys :opt-un [::format ::quality ::width ::height]))

;; Related info on how thumbnails generation
;;  http://www.imagemagick.org/Usage/thumbnails/

(defn generate-thumbnail
  ([input] (generate-thumbnail input nil))
  ([input {:keys [quality format width height]
           :or {format "jpeg"
                quality 92
                width 200
                height 200}
           :as opts}]
   (us/assert ::thumbnail-opts opts)
   (us/assert fs/path? input)
   (let [ext (format->extension format)
         tmp (fs/create-tempfile :suffix ext)
         opr (doto (IMOperation.)
               (.addImage)

               (.autoOrient)
               (.strip)
               (.thumbnail (int width) (int height) ">")
               (.quality (double quality))

               ;; (.autoOrient)
               ;; (.strip)
               ;; (.thumbnail (int width) (int height) "^")
               ;; (.gravity "center")
               ;; (.extent (int width) (int height))
               ;; (.quality (double quality))
               (.addImage))]
     (doto (ConvertCmd.)
       (.run opr (into-array (map str [input tmp]))))
     (let [thumbnail-data (fs/slurp-bytes tmp)]
       (fs/delete tmp)
       (ByteArrayInputStream. thumbnail-data)))))

(defn generate-thumbnail2
  ([input] (generate-thumbnail input nil))
  ([input {:keys [quality format width height]
           :or {format "jpeg"
                quality 92
                width 200
                height 200}
           :as opts}]
   (us/assert ::thumbnail-opts opts)
   (us/assert fs/path? input)
   (let [ext (format->extension format)
         tmp (fs/create-tempfile :suffix ext)
         opr (doto (IMOperation.)
               (.addImage)
               (.autoOrient)
               (.strip)
               (.thumbnail (int width) (int height) "^")
               (.gravity "center")
               (.extent (int width) (int height))
               (.quality (double quality))
               (.addImage))]
     (doto (ConvertCmd.)
       (.run opr (into-array (map str [input tmp]))))
     (let [thumbnail-data (fs/slurp-bytes tmp)]
       (fs/delete tmp)
       (ByteArrayInputStream. thumbnail-data)))))

(defn info
  [path]
  (let [instance (Info. (str path))]
    {:width (.getImageWidth instance)
     :height (.getImageHeight instance)}))

(defn resolve-urls
  [row src dst]
  (s/assert map? row)
  (let [src (if (vector? src) src [src])
        dst (if (vector? dst) dst [dst])
        value (get-in row src)]
    (if (empty? value)
      row
      (let [url (ust/public-uri media/media-storage value)]
        (assoc-in row dst (str url))))))

(defn- resolve-uri
  [storage row src dst]
  (let [src (if (vector? src) src [src])
        dst (if (vector? dst) dst [dst])
        value (get-in row src)]
    (if (empty? value)
      row
      (let [url (ust/public-uri media/media-storage value)]
        (assoc-in row dst (str url))))))

(defn resolve-media-uris
  [row & pairs]
  (us/assert map? row)
  (us/assert (s/coll-of vector?) pairs)
  (reduce #(resolve-uri media/media-storage %1 (nth %2 0) (nth %2 1)) row pairs))
