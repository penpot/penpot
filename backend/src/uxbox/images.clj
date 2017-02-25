;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) 2016 Andrey Antukh <niwi@niwi.nz>

(ns uxbox.images
  "Image postprocessing."
  (:require [clojure.spec :as s]
            [clojure.java.io :as io]
            [datoteka.storages :as st]
            [datoteka.core :as fs]
            [datoteka.proto :as pt]
            [uxbox.util.spec :as us]
            [uxbox.media :as media]
            [uxbox.util.images :as images]
            [uxbox.util.data :refer (dissoc-in)]))

;; FIXME: add spec for thumbnail config

(defn make-thumbnail
  [path {:keys [size format quality] :as cfg}]
  (let [parent (fs/parent path)
        [filename ext] (fs/split-ext path)

        suffix-parts [(nth size 0) (nth size 1) quality format]
        final-name (apply str filename "-" (interpose "." suffix-parts))
        final-path (fs/path parent final-name)

        images-storage media/images-storage
        thumbs-storage media/thumbnails-storage]
    (if @(st/exists? thumbs-storage final-path)
      (str (st/public-url thumbs-storage final-path))
      (if @(st/exists? images-storage path)
        (let [datapath @(st/lookup images-storage path)
              content (images/thumbnail datapath cfg)
              path @(st/save thumbs-storage final-path content)]
          (str (st/public-url thumbs-storage path)))
        nil))))

(defn populate-thumbnail
  [entry {:keys [src dst] :as cfg}]
  (assert (map? entry) "`entry` should be map")

  (let [src (if (vector? src) src [src])
        dst (if (vector? dst) dst [dst])
        src (get-in entry src)]
     (if (empty? src)
       entry
       (assoc-in entry dst (make-thumbnail src cfg)))))

(defn populate-thumbnails
  [entry & settings]
  (reduce populate-thumbnail entry settings))

(defn populate-urls
  [entry storage src dst]
  (assert (map? entry) "`entry` should be map")
  (assert (st/storage? storage) "`storage` should be a valid storage instance.")
  (let [src (if (vector? src) src [src])
        dst (if (vector? dst) dst [dst])
        value (get-in entry src)]
    (if (empty? value)
      entry
      (let [url (str (st/public-url storage value))]
        (-> entry
            (dissoc-in src)
            (assoc-in dst url))))))
