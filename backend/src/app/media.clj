;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC

(ns app.media
  "Media & Font postprocessing."
  (:require
   [app.common.data :as d]
   [app.common.data.macros :as dm]
   [app.common.exceptions :as ex]
   [app.common.logging :as l]
   [app.common.media :as cm]
   [app.common.schema :as sm]
   [app.common.schema.openapi :as-alias oapi]
   [app.common.time :as ct]
   [app.config :as cf]
   [app.db :as-alias db]
   [app.http.client :as http]
   [app.storage :as-alias sto]
   [app.storage.tmp :as tmp]
   [buddy.core.bytes :as bb]
   [buddy.core.codecs :as bc]
   [clojure.java.shell :as sh]
   [clojure.string]
   [clojure.xml :as xml]
   [cuerdas.core :as str]
   [datoteka.fs :as fs]
   [datoteka.io :as io])
  (:import
   clojure.lang.XMLHandler
   java.io.InputStream
   javax.xml.XMLConstants
   javax.xml.parsers.SAXParserFactory
   org.apache.commons.io.IOUtils
   org.im4java.core.ConvertCmd
   org.im4java.core.IMOperation
   org.im4java.core.Info))

(def default-max-file-size
  (* 1024 1024 10)) ; 10 MiB

(def schema:upload
  [:map {:title "Upload"}
   [:filename :string]
   [:size ::sm/int]
   [:path ::fs/path]
   [:mtype {:optional true} :string]
   [:headers {:optional true}
    [:map-of :string :string]]])

(def ^:private schema:input
  [:map {:title "Input"}
   [:path ::fs/path]
   [:mtype {:optional true} ::sm/text]])

(def ^:private check-input
  (sm/check-fn schema:input))

(defn validate-media-type!
  ([upload] (validate-media-type! upload cm/image-types))
  ([upload allowed]
   (when-not (contains? allowed (:mtype upload))
     (ex/raise :type :validation
               :code :media-type-not-allowed
               :hint "Seems like you are uploading an invalid media object"))

   upload))

(defn validate-media-size!
  [upload]
  (let [max-size (cf/get :media-max-file-size)]
    (when (> (:size upload) max-size)
      (ex/raise :type :restriction
                :code :media-max-file-size-reached
                :hint (str/ffmt "the uploaded file size % is greater than the maximum %"
                                (:size upload)
                                max-size)))
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
  (try
    (process params)
    (catch Throwable e
      (process-error e))))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; SVG PARSING
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(defn- secure-parser-factory
  [^InputStream input ^XMLHandler handler]
  (.. (doto (SAXParserFactory/newInstance)
        (.setFeature XMLConstants/FEATURE_SECURE_PROCESSING true)
        (.setFeature "http://apache.org/xml/features/disallow-doctype-decl" true))
      (newSAXParser)
      (parse input handler)))

(defn- strip-doctype
  [data]
  (cond-> data
    (str/includes? data "<!DOCTYPE")
    (str/replace #"<\!DOCTYPE[^>]*>" "")))

(defn- parse-svg
  [text]
  (let [text (strip-doctype text)]
    (dm/with-open [istream (IOUtils/toInputStream ^String text "UTF-8")]
      (xml/parse istream secure-parser-factory))))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; IMAGE THUMBNAILS
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(def ^:private schema:thumbnail-params
  [:map {:title "ThumbnailParams"}
   [:input schema:input]
   [:format [:enum :jpeg :webp :png]]
   [:quality [:int {:min 1 :max 100}]]
   [:width :int]
   [:height :int]])

(def ^:private check-thumbnail-params
  (sm/check-fn schema:thumbnail-params))

;; Related info on how thumbnails generation
;;  http://www.imagemagick.org/Usage/thumbnails/

(defn- generic-process
  [{:keys [input format operation] :as params}]
  (let [{:keys [path mtype]} input
        format (or (cm/mtype->format mtype) format)
        ext    (cm/format->extension format)
        tmp    (tmp/tempfile :prefix "penpot.media." :suffix ext)]

    (doto (ConvertCmd.)
      (.run operation (into-array (map str [path tmp]))))

    (assoc params
           :format format
           :mtype  (cm/format->mtype format)
           :size   (fs/size tmp)
           :data   tmp)))

(defmethod process :generic-thumbnail
  [params]
  (let [{:keys [quality width height] :as params}
        (check-thumbnail-params params)

        operation
        (doto (IMOperation.)
          (.addImage)
          (.autoOrient)
          (.strip)
          (.thumbnail ^Integer (int width) ^Integer (int height) ">")
          (.quality (double quality))
          (.addImage))]

    (generic-process (assoc params :operation operation))))

(defmethod process :profile-thumbnail
  [params]
  (let [{:keys [quality width height] :as params}
        (check-thumbnail-params params)

        operation
        (doto (IMOperation.)
          (.addImage)
          (.autoOrient)
          (.strip)
          (.thumbnail ^Integer (int width) ^Integer (int height) "^")
          (.gravity "center")
          (.extent (int width) (int height))
          (.quality (double quality))
          (.addImage))]

    (generic-process (assoc params :operation operation))))

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

(defn- get-dimensions-with-orientation [^String path]
  ;; Image magick doesn't give info about exif rotation so we use the identify command
  ;; If we are processing an animated gif we use the first frame with -scene 0
  (let [dim-result (sh/sh "identify" "-format" "%w %h\n" path)
        orient-result (sh/sh "identify" "-format" "%[EXIF:Orientation]\n" path)]
    (if (and (= 0 (:exit dim-result))
             (= 0 (:exit orient-result)))
      (let [[w h] (-> (:out dim-result)
                      str/trim
                      (clojure.string/split #"\s+")
                      (->> (mapv #(Integer/parseInt %))))
            orientation (-> orient-result :out str/trim)]
        (case orientation
          ("6" "8") {:width h :height w} ; Rotated 90 or 270 degrees
          {:width w :height h}))         ; Normal or unknown orientation
      nil)))

(defmethod process :info
  [{:keys [input] :as params}]
  (let [{:keys [path mtype] :as input} (check-input input)]
    (if (= mtype "image/svg+xml")
      (let [info (some-> path slurp parse-svg get-basic-info-from-svg)]
        (when-not info
          (ex/raise :type :validation
                    :code :invalid-svg-file
                    :hint "uploaded svg does not provides dimensions"))
        (merge input info {:ts (ct/now) :size (fs/size path)}))

      (let [instance (Info. (str path))
            mtype'   (.getProperty instance "Mime type")]
        (when (and (string? mtype)
                   (not= mtype mtype'))
          (ex/raise :type :validation
                    :code :media-type-mismatch
                    :hint (str "Seems like you are uploading a file whose content does not match the extension."
                               "Expected: " mtype ". Got: " mtype')))
        (let [{:keys [width height]}
              (or (get-dimensions-with-orientation (str path))
                  (do
                    (l/warn "Failed to read image dimensions with orientation; falling back to im4java"
                            {:path path})
                    {:width  (.getPageWidth instance)
                     :height (.getPageHeight instance)}))]
          (assoc input
                 :width  width
                 :height height
                 :size (fs/size path)
                 :ts (ct/now)))))))

(defmethod process-error org.im4java.core.InfoException
  [error]
  (ex/raise :type :validation
            :code :invalid-image
            :hint "invalid image"
            :cause error))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; IMAGE HELPERS
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(defn download-image
  "Download an image from the provided URI and return the media input object"
  [{:keys [::http/client]} uri]
  (letfn [(parse-and-validate [{:keys [headers] :as response}]
            (let [size     (some-> (get headers "content-length") d/parse-integer)
                  mtype    (get headers "content-type")
                  format   (cm/mtype->format mtype)
                  max-size (cf/get :media-max-file-size default-max-file-size)]

              (when-not size
                (ex/raise :type :validation
                          :code :unknown-size
                          :hint "seems like the url points to resource with unknown size"))

              (when (> size max-size)
                (ex/raise :type :validation
                          :code :file-too-large
                          :hint (str/ffmt "the file size % is greater than the maximum %"
                                          size
                                          default-max-file-size)))

              (when (nil? format)
                (ex/raise :type :validation
                          :code :media-type-not-allowed
                          :hint "seems like the url points to an invalid media object"))

              {:size size :mtype mtype :format format}))]

    (let [{:keys [body] :as response} (http/req! client
                                                 {:method :get :uri uri}
                                                 {:response-type :input-stream})
          {:keys [size mtype]} (parse-and-validate response)
          path    (tmp/tempfile :prefix "penpot.media.download.")
          written (io/write* path body :size size)]

      (when (not= written size)
        (ex/raise :type :internal
                  :code :mismatch-write-size
                  :hint "unexpected state: unable to write to file"))

      {;; :size size
       :path path
       :mtype mtype})))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; FONTS
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(defmethod process :generate-fonts
  [{:keys [input] :as params}]
  (letfn [(ttf->otf [data]
            (let [finput  (tmp/tempfile :prefix "penpot.font." :suffix "")
                  foutput (fs/path (str finput ".otf"))
                  _       (io/write* finput data)
                  res     (sh/sh "fontforge" "-lang=ff" "-c"
                                 (str/fmt "Open('%s'); Generate('%s')"
                                          (str finput)
                                          (str foutput)))]
              (when (zero? (:exit res))
                foutput)))

          (otf->ttf [data]
            (let [finput  (tmp/tempfile :prefix "penpot.font." :suffix "")
                  foutput (fs/path (str finput ".ttf"))
                  _       (io/write* finput data)
                  res     (sh/sh "fontforge" "-lang=ff" "-c"
                                 (str/fmt "Open('%s'); Generate('%s')"
                                          (str finput)
                                          (str foutput)))]
              (when (zero? (:exit res))
                foutput)))

          (ttf-or-otf->woff [data]
            ;; NOTE: foutput is not used directly, it represents the
            ;; default output of the execution of the underlying
            ;; command.
            (let [finput  (tmp/tempfile :prefix "penpot.font." :suffix "")
                  foutput (fs/path (str finput ".woff"))
                  _       (io/write* finput data)
                  res     (sh/sh "sfnt2woff" (str finput))]
              (when (zero? (:exit res))
                foutput)))

          (woff->sfnt [data]
            (let [finput  (tmp/tempfile :prefix "penpot" :suffix "")
                  _       (io/write* finput data)
                  res     (sh/sh "woff2sfnt" (str finput)
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
              (update "font/woff" gen-if-nil #(ttf-or-otf->woff data))))

        (contains? current "font/otf")
        (let [data (get input "font/otf")]
          (-> input
              (update "font/woff" gen-if-nil #(ttf-or-otf->woff data))
              (assoc "font/ttf" (otf->ttf data))))

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
              (-> (assoc "font/woff" data))

              (= stype :otf)
              (-> (assoc "font/otf" sfnt)
                  (assoc "font/ttf" (otf->ttf sfnt)))

              (= stype :ttf)
              (-> (assoc "font/otf" (ttf->otf sfnt))
                  (assoc "font/ttf" sfnt)))))))))
