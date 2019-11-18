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
   [datoteka.proto :as pt]
   [datoteka.storages :as st]
   [uxbox.media :as media]
   [uxbox.util.data :refer (dissoc-in)]
   [uxbox.util.spec :as us])
  (:import
   java.io.ByteArrayInputStream
   java.io.InputStream
   org.im4java.core.ConvertCmd
   org.im4java.core.IMOperation))

;; TODO: make this module non-blocking

;; --- Thumbnails Generation

(s/def ::width integer?)
(s/def ::height integer?)
(s/def ::quality #(< 0 % 101))
(s/def ::format #{"jpg" "webp"})
(s/def ::thumbnail-opts
  (s/keys :opt-un [::format ::quality ::width ::height]))

;; Related info on how thumbnails generation
;;  http://www.imagemagick.org/Usage/thumbnails/

(defn generate-thumbnail
  ([input] (generate-thumbnail input nil))
  ([input {:keys [size quality format width height]
           :or {format "jpg"
                quality 92
                width 200
                height 200}
           :as opts}]
   {:pre [(us/valid? ::thumbnail-opts opts)
          (fs/path? input)]}
   (let [tmp (fs/create-tempfile :suffix (str "." format))
         opr (doto (IMOperation.)
               (.addImage)
               (.autoOrient)
               (.resize (int width) (int height) "^")
               (.quality (double quality))
               (.addImage))]
     (doto (ConvertCmd.)
       (.run opr (into-array (map str [input tmp]))))
     (let [thumbnail-data (fs/slurp-bytes tmp)]
       (fs/delete tmp)
       (ByteArrayInputStream. thumbnail-data)))))

(defn make-thumbnail
  [input {:keys [width height format quality] :as opts}]
  {:pre [(us/valid? ::thumbnail-opts opts)
         (or (string? input)
             (fs/path input))]}
  (let [[filename ext] (fs/split-ext (fs/name input))
        suffix (->> [width height quality format]
                    (interpose ".")
                    (apply str))
        thumbnail-path (fs/path input (str "thumb-" suffix))
        images-storage media/images-storage
        thumbs-storage media/thumbnails-storage]
    (if @(st/exists? thumbs-storage thumbnail-path)
      (str (st/public-url thumbs-storage thumbnail-path))
      (if @(st/exists? images-storage input)
        (let [datapath @(st/lookup images-storage input)
              thumbnail (generate-thumbnail datapath opts)
              path @(st/save thumbs-storage thumbnail-path thumbnail)]
          (str (st/public-url thumbs-storage path)))
        nil))))

(defn populate-thumbnail
  [entry {:keys [src dst] :as opts}]
  {:pre [(map? entry)]}
  (let [src (if (vector? src) src [src])
        dst (if (vector? dst) dst [dst])
        src (get-in entry src)]
     (if (empty? src)
       entry
       (assoc-in entry dst (make-thumbnail src opts)))))

(defn populate-thumbnails
  [entry & settings]
  (reduce populate-thumbnail entry settings))

(defn populate-urls
  [entry storage src dst]
  {:pre [(map? entry)
         (st/storage? storage)]}
  (let [src (if (vector? src) src [src])
        dst (if (vector? dst) dst [dst])
        value (get-in entry src)]
    (if (empty? value)
      entry
      (let [url (str (st/public-url storage value))]
        (-> entry
            (dissoc-in src)
            (assoc-in dst url))))))

