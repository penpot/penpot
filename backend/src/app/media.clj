;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) UXBOX Labs SL

(ns app.media
  "Media & Font postprocessing."
  (:require
   [app.common.data :as d]
   [app.common.exceptions :as ex]
   [app.common.media :as cm]
   [app.common.spec :as us]
   [app.config :as cf]
   [app.util.svg :as svg]
   [buddy.core.bytes :as bb]
   [buddy.core.codecs :as bc]
   [clojure.java.io :as io]
   [clojure.java.shell :as sh]
   [clojure.spec.alpha :as s]
   [clojure.string :as st]
   [cuerdas.core :as str]
   [datoteka.core :as fs])
  (:import
   java.io.ByteArrayInputStream
   java.io.OutputStream
   org.apache.commons.io.IOUtils
   org.im4java.core.ConvertCmd
   org.im4java.core.IMOperation
   org.im4java.core.Info))

(s/def ::path fs/path?)
(s/def ::filename string?)
(s/def ::size integer?)
(s/def ::headers (s/map-of string? string?))
(s/def ::mtype string?)

(s/def ::upload
  (s/keys :req-un [::filename ::size ::path]
          :opt-un [::mtype ::headers]))

;; A subset of fields from the ::upload spec
(s/def ::input
  (s/keys :req-un [::path]
          :opt-un [::mtype]))

(defn validate-media-type!
  ([upload] (validate-media-type! upload cm/valid-image-types))
  ([upload allowed]
   (when-not (contains? allowed (:mtype upload))
     (ex/raise :type :validation
               :code :media-type-not-allowed
               :hint "Seems like you are uploading an invalid media object"))
   upload))

(defmulti process :cmd)
(defmulti process-error class)

(defmethod process :default
  [{:keys [cmd] :as params}]
  (ex/raise :type :internal
            :code :not-implemented
            :hint (str/fmt "No impl found for process cmd: %s" cmd)))

(defmethod process-error :default
  [error]
  (throw error))

(defn run
  [params]
  ;; TODO: reenable this
  (process params)
  #_(try
    (process params)
    (catch Throwable e
      (process-error e))))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; IMAGE THUMBNAILS
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(s/def ::width integer?)
(s/def ::height integer?)
(s/def ::format #{:jpeg :webp :png})
(s/def ::quality #(< 0 % 101))

(s/def ::thumbnail-params
  (s/keys :req-un [::input ::format ::width ::height]))

;; Related info on how thumbnails generation
;;  http://www.imagemagick.org/Usage/thumbnails/

(defn- generic-process
  [{:keys [input format operation] :as params}]
  (let [{:keys [path mtype]} input
        format (or (cm/mtype->format mtype) format)
        ext    (cm/format->extension format)
        tmp    (fs/create-tempfile :suffix ext)]

    (doto (ConvertCmd.)
      (.run operation (into-array (map str [path tmp]))))

    (let [thumbnail-data (fs/slurp-bytes tmp)]
      (fs/delete tmp)
      (assoc params
             :format format
             :mtype  (cm/format->mtype format)
             :size   (alength ^bytes thumbnail-data)
             :data   (ByteArrayInputStream. thumbnail-data)))))

(defmethod process :generic-thumbnail
  [{:keys [quality width height] :as params}]
  (us/assert ::thumbnail-params params)
  (let [op (doto (IMOperation.)
             (.addImage)
             (.autoOrient)
             (.strip)
             (.thumbnail ^Integer (int width) ^Integer (int height) ">")
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
             (.thumbnail ^Integer (int width) ^Integer (int height) "^")
             (.gravity "center")
             (.extent (int width) (int height))
             (.quality (double quality))
             (.addImage))]
    (generic-process (assoc params :operation op))))

(defn get-basic-info-from-svg
  [{:keys [tag attrs] :as data}]
  (when (not= tag :svg)
    (ex/raise :type :validation
              :code :unable-to-parse-svg
              :hint "uploaded svg has invalid content"))
  (reduce (fn [default f]
            (if-let [res (f attrs)]
              (reduced res)
              default))
          {:width 100 :height 100}
          [(fn parse-width-and-height
             [{:keys [width height]}]
             (when (and (string? width)
                        (string? height))
               (let [width  (d/parse-double width)
                     height (d/parse-double height)]
                 (when (and width height)
                   {:width (int width)
                    :height (int height)}))))
           (fn parse-viewbox
             [{:keys [viewBox]}]
             (let [[x y width height] (->> (str/split viewBox #"\s+" 4)
                                           (map d/parse-double))]
               (when (and x y width height)
                 {:width (int width)
                  :height (int height)})))]))

(defmethod process :info
  [{:keys [input] :as params}]
  (us/assert ::input input)
  (let [{:keys [path mtype]} input]

    (if (= mtype "image/svg+xml")
      (let [info (some-> path slurp svg/pre-process svg/parse get-basic-info-from-svg)]
        (when-not info
          (ex/raise :type :validation
                    :code :invalid-svg-file
                    :hint "uploaded svg does not provides dimensions"))
        (merge input info))

      (let [instance (Info. (str path) true)
            mtype' (str "image/" (st/lower-case (.getImageFormat instance)))]
        (when (and (string? mtype)
                   (not= mtype mtype'))
          (ex/raise :type :validation
                    :code :media-type-mismatch
                    :hint (str "Seems like you are uploading a file whose content does not match the extension."
                               "Expected: " mtype ". Got: " mtype')))
        ;; For an animated GIF, getImageWidth/Height returns the delta size of one frame (if no frame given
        ;; it returns size of the last one), whereas getPageWidth/Height always return the full size of
        ;; any frame.
        (assoc input
               :width  (.getPageWidth instance)
               :height (.getPageHeight instance))))))

(defmethod process-error org.im4java.core.InfoException
  [error]
  (ex/raise :type :validation
            :code :invalid-image
            :hint "invalid image"
            :cause error))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; FONTS
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(defmethod process :generate-fonts
  [{:keys [input] :as params}]
  (letfn [(ttf->otf [data]
            (let [input-file  (fs/create-tempfile :prefix "penpot")
                  output-file (fs/path (str input-file ".otf"))
                  _           (with-open [out (io/output-stream input-file)]
                                (IOUtils/writeChunked ^bytes data ^OutputStream out)
                                (.flush ^OutputStream out))
                  res         (sh/sh "fontforge" "-lang=ff" "-c"
                                     (str/fmt "Open('%s'); Generate('%s')"
                                              (str input-file)
                                              (str output-file)))]
              (when (zero? (:exit res))
                (fs/slurp-bytes output-file))))


          (otf->ttf [data]
            (let [input-file  (fs/create-tempfile :prefix "penpot")
                  output-file (fs/path (str input-file ".ttf"))
                  _           (with-open [out (io/output-stream input-file)]
                                (IOUtils/writeChunked ^bytes data ^OutputStream out)
                                (.flush ^OutputStream out))
                  res         (sh/sh "fontforge" "-lang=ff" "-c"
                                     (str/fmt "Open('%s'); Generate('%s')"
                                              (str input-file)
                                              (str output-file)))]
              (when (zero? (:exit res))
                (fs/slurp-bytes output-file))))

          (ttf-or-otf->woff [data]
            (let [input-file  (fs/create-tempfile :prefix "penpot" :suffix "")
                  output-file (fs/path (str input-file ".woff"))
                  _           (with-open [out (io/output-stream input-file)]
                                (IOUtils/writeChunked ^bytes data ^OutputStream out)
                                (.flush ^OutputStream out))
                  res         (sh/sh "sfnt2woff" (str input-file))]
              (when (zero? (:exit res))
                (fs/slurp-bytes output-file))))

          (ttf-or-otf->woff2 [data]
            (let [input-file  (fs/create-tempfile :prefix "penpot" :suffix "")
                  output-file (fs/path (str input-file ".woff2"))
                  _           (with-open [out (io/output-stream input-file)]
                                (IOUtils/writeChunked ^bytes data ^OutputStream out)
                                (.flush ^OutputStream out))
                  res         (sh/sh "woff2_compress" (str input-file))]
              (when (zero? (:exit res))
                (fs/slurp-bytes output-file))))

          (woff->sfnt [data]
            (let [input-file  (fs/create-tempfile :prefix "penpot" :suffix "")
                  _           (with-open [out (io/output-stream input-file)]
                                (IOUtils/writeChunked ^bytes data ^OutputStream out)
                                (.flush ^OutputStream out))
                  res         (sh/sh "woff2sfnt" (str input-file)
                                     :out-enc :bytes)]
              (when (zero? (:exit res))
                (:out res))))

          ;; Documented here:
          ;; https://docs.microsoft.com/en-us/typography/opentype/spec/otff#table-directory
          (get-sfnt-type [data]
            (let [buff (bb/slice data 0 4)
                  type (bc/bytes->hex buff)]
              (case type
                "4f54544f" :otf
                "00010000" :ttf
                (ex/raise :type :internal
                          :code :unexpected-data
                          :hint "unexpected font data"))))

          (gen-if-nil [val factory]
            (if (nil? val)
              (factory)
              val))]

    (let [current (into #{} (keys input))]
      (cond
        (contains? current "font/ttf")
        (let [data (get input "font/ttf")]
          (-> input
              (update "font/otf" gen-if-nil #(ttf->otf data))
              (update "font/woff" gen-if-nil #(ttf-or-otf->woff data))
              (assoc "font/woff2" (ttf-or-otf->woff2 data))))

        (contains? current "font/otf")
        (let [data (get input "font/otf")]
          (-> input
              (update "font/woff" gen-if-nil #(ttf-or-otf->woff data))
              (assoc "font/ttf" (otf->ttf data))
              (assoc "font/woff2" (ttf-or-otf->woff2 data))))

        (contains? current "font/woff")
        (let [data (get input "font/woff")
              sfnt (woff->sfnt data)]
          (when-not sfnt
            (ex/raise :type :validation
                      :code :invalid-woff-file
                      :hint "invalid woff file"))
          (let [stype (get-sfnt-type sfnt)]
            (cond-> input
              true
              (-> (assoc "font/woff" data)
                  (assoc "font/woff2" (ttf-or-otf->woff2 sfnt)))

              (= stype :otf)
              (-> (assoc "font/otf" sfnt)
                  (assoc "font/ttf" (otf->ttf sfnt)))

              (= stype :ttf)
              (-> (assoc "font/otf" (ttf->otf sfnt))
                  (assoc "font/ttf" sfnt)))))))))


;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Utility functions
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(defn configure-assets-storage
  "Given storage map, returns a storage configured with the appropriate
  backend for assets and optional connection attached."
  ([storage]
   (assoc storage :backend (cf/get :assets-storage-backend :assets-fs)))
  ([storage conn]
   (-> storage
       (assoc :conn conn)
       (assoc :backend (cf/get :assets-storage-backend :assets-fs)))))
