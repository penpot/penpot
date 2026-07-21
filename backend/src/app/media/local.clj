;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC Sucursal en España SL

(ns app.media.local
  "Local media processing via ImageMagick and FontForge shell commands."
  (:require
   [app.common.data :as d]
   [app.common.data.macros :as dm]
   [app.common.exceptions :as ex]
   [app.common.logging :as l]
   [app.common.media :as cm]
   [app.common.schema :as sm]
   [app.common.time :as ct]
   [app.config :as cf]
   [app.media.validation :as validation]
   [app.storage.tmp :as tmp]
   [app.util.shell :as shell]
   [buddy.core.bytes :as bb]
   [buddy.core.codecs :as bc]
   [clojure.string]
   [clojure.xml :as xml]
   [cuerdas.core :as str]
   [datoteka.fs :as fs]
   [datoteka.io :as io])
  (:import
   clojure.lang.XMLHandler
   java.io.InputStream
   javax.xml.parsers.SAXParserFactory
   javax.xml.XMLConstants
   org.apache.commons.io.IOUtils))

(defmulti process (fn [_system params] (:cmd params)))

(defmethod process :default
  [_system {:keys [cmd] :as params}]
  (ex/raise :type :internal
            :code :not-implemented
            :hint (str/fmt "No impl found for local process cmd: %s" cmd)))

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

(defn parse-svg
  [text]
  (let [text (strip-doctype text)]
    (dm/with-open [istream (IOUtils/toInputStream ^String text "UTF-8")]
      (xml/parse istream secure-parser-factory))))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; IMAGE THUMBNAILS
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(def ^:private schema:thumbnail-params
  [:map {:title "ThumbnailParams"}
   [:input validation/schema:input]
   [:format [:enum :jpeg :webp :png]]
   [:quality [:int {:min 1 :max 100}]]
   [:width :int]
   [:height :int]])

(def ^:private check-thumbnail-params
  (sm/check-fn schema:thumbnail-params))

;; Related info on how thumbnails generation
;;  http://www.imagemagick.org/Usage/thumbnails/

(def ^:private imagemagick-default-env
  "Default environment variables for ImageMagick resource limits.
   These are the soft ceiling — policy.xml is the hard ceiling."
  {"MAGICK_THREAD_LIMIT" "2"
   "MAGICK_MEMORY_LIMIT" "256MiB"
   "MAGICK_MAP_LIMIT" "512MiB"
   "MAGICK_AREA_LIMIT" "128MP"
   "MAGICK_DISK_LIMIT" "1GiB"
   "MAGICK_TIME_LIMIT" "30"})

(defn- get-imagemagick-env
  "Returns environment variables for ImageMagick commands.
   Reads individual PENPOT_IMAGEMAGICK_* config values, falling back to defaults."
  []
  (let [thread (cf/get :imagemagick-thread-limit)
        memory (cf/get :imagemagick-memory-limit)
        map-l  (cf/get :imagemagick-map-limit)
        area   (cf/get :imagemagick-area-limit)
        disk   (cf/get :imagemagick-disk-limit)
        time   (cf/get :imagemagick-time-limit)
        width  (cf/get :imagemagick-width-limit)
        height (cf/get :imagemagick-height-limit)]
    (cond-> imagemagick-default-env
      thread (assoc "MAGICK_THREAD_LIMIT" thread)
      memory (assoc "MAGICK_MEMORY_LIMIT" memory)
      map-l  (assoc "MAGICK_MAP_LIMIT" map-l)
      area   (assoc "MAGICK_AREA_LIMIT" area)
      disk   (assoc "MAGICK_DISK_LIMIT" disk)
      time   (assoc "MAGICK_TIME_LIMIT" time)
      width  (assoc "MAGICK_WIDTH_LIMIT" width)
      height (assoc "MAGICK_HEIGHT_LIMIT" height))))

(defn- exec-magick!
  "Execute an ImageMagick command with resource limits.
   `args` is a vector of string arguments to pass to `magick`."
  [system args]
  (let [cmd    (into ["magick"] args)
        result (shell/exec! system
                            :cmd cmd
                            :env (get-imagemagick-env)
                            :timeout 60)]
    (when (not= 0 (:exit result))
      (ex/raise :type :validation
                :code :invalid-image
                :hint (str "ImageMagick command failed: " (:err result))
                :cmd cmd
                :exit (:exit result)))
    result))

(defn- generic-process
  [system {:keys [input format convert-args] :as params}]
  (let [{:keys [path mtype]} input
        format (or format (cm/mtype->format mtype))
        ext    (cm/format->extension format)
        tmp    (tmp/tempfile :prefix "penpot.media." :suffix ext)
        args   (into [(str path)] (conj (vec convert-args) (str tmp)))]
    (exec-magick! system args)
    (assoc params
           :format format
           :mtype  (cm/format->mtype format)
           :size   (fs/size tmp)
           :data   tmp)))

(defmethod process :generic-thumbnail
  [system params]
  (let [{:keys [quality width height] :as params}
        (check-thumbnail-params params)]
    (generic-process system
                     (assoc params
                            :convert-args ["-auto-orient" "-strip"
                                           "-thumbnail" (str width "x" height ">")
                                           "-quality" (str quality)]))))

(defmethod process :profile-thumbnail
  [system params]
  (let [{:keys [quality width height] :as params}
        (check-thumbnail-params params)]
    (generic-process system
                     (assoc params
                            :convert-args ["-auto-orient" "-strip"
                                           "-thumbnail" (str width "x" height "^")
                                           "-gravity" "center"
                                           "-extent" (str width "x" height)
                                           "-quality" (str quality)]))))

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

(defn- get-dimensions-with-orientation [system ^String path]
  ;; Image magick doesn't give info about exif rotation so we use the identify command
  ;; If we are processing an animated gif we use the first frame with -scene 0
  (let [dim-result    (exec-magick! system ["identify" "-format" "%w %h\n" path])
        orient-result (exec-magick! system ["identify" "-format" "%[EXIF:Orientation]\n" path])]
    (when (= 0 (:exit dim-result))
      (let [[w h] (-> (:out dim-result)
                      str/trim
                      (clojure.string/split #"\s+")
                      (->> (mapv #(Integer/parseInt %))))
            orientation-exit (:exit orient-result)
            orientation      (-> orient-result :out str/trim)]
        (if (= 0 orientation-exit)
          (case orientation
            ("6" "8") {:width h :height w} ; Rotated 90 or 270 degrees
            {:width w :height h})          ; Normal or unknown orientation
          {:width w :height h})))))        ; If orientation can't be read, use dimensions as-is

(defmethod process :info
  [system {:keys [input] :as params}]
  (let [{:keys [path mtype] :as input} (validation/check-input input)]
    (if (= mtype "image/svg+xml")
      (let [info (some-> path slurp parse-svg get-basic-info-from-svg)]
        (when-not info
          (ex/raise :type :validation
                    :code :invalid-svg-file
                    :hint "uploaded svg does not provides dimensions"))
        (merge input info {:ts (ct/now) :size (fs/size path)}))

      (let [path-str      (str path)
            identify-res  (exec-magick! system ["identify" "-format" "image/%[magick]\n" path-str])
            ;; identify prints one line per frame (animated GIFs, etc.); we take the first one
            mtype'        (if (zero? (:exit identify-res))
                            (-> identify-res
                                :out
                                str/trim
                                (str/split #"\s+" 2)
                                first
                                str/lower)
                            (ex/raise :type :validation
                                      :code :invalid-image
                                      :hint "invalid image"))
            {:keys [width height]}
            (or (get-dimensions-with-orientation system path-str)
                (do
                  (l/warn "Failed to read image dimensions with orientation" {:path path})
                  (ex/raise :type :validation
                            :code :invalid-image
                            :hint "invalid image")))]
        (when (and (string? mtype)
                   (not= (str/lower mtype) mtype'))
          (ex/raise :type :validation
                    :code :media-type-mismatch
                    :hint (str "Seems like you are uploading a file whose content does not match the extension."
                               "Expected: " mtype ". Got: " mtype')))
        (assoc input
               :width  width
               :height height
               :size (fs/size path)
               :ts (ct/now))))))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; FONTS
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(defn- get-font-prlimit
  "Returns resource limits for font processing tools, read from config."
  []
  {:mem (cf/get :font-process-mem)
   :cpu (cf/get :font-process-cpu)})

(defn- get-font-timeout
  "Returns the wall-clock timeout for font processing, read from config."
  []
  (cf/get :font-process-timeout))

(defn- exec-font!
  "Execute a font processing command with resource limits.
   `args` is a vector of string arguments."
  [system args]
  (shell/exec! system
               :cmd args
               :prlimit (get-font-prlimit)
               :timeout (get-font-timeout)))

(defmethod process :generate-fonts
  [system {:keys [input] :as params}]
  (letfn [(ttf->otf [data]
            (let [finput  (tmp/tempfile :prefix "penpot.font." :suffix "")
                  foutput (fs/path (str finput ".otf"))]
              (try
                (io/write* finput data)
                (let [res (exec-font! system ["fontforge" "-lang=ff" "-c"
                                              (str/fmt "Open('%s'); Generate('%s')"
                                                       (str finput)
                                                       (str foutput))])]
                  (when (zero? (:exit res))
                    foutput))
                (finally
                  (fs/delete finput)))))

          (otf->ttf [data]
            (let [finput  (tmp/tempfile :prefix "penpot.font." :suffix "")
                  foutput (fs/path (str finput ".ttf"))]
              (try
                (io/write* finput data)
                (let [res (exec-font! system ["fontforge" "-lang=ff" "-c"
                                              (str/fmt "Open('%s'); Generate('%s')"
                                                       (str finput)
                                                       (str foutput))])]
                  (when (zero? (:exit res))
                    foutput))
                (finally
                  (fs/delete finput)))))

          (ttf-or-otf->woff [data]
            (let [finput  (tmp/tempfile :prefix "penpot.font." :suffix "")
                  foutput (fs/path (str finput ".woff"))]
              (try
                (io/write* finput data)
                (let [res (exec-font! system ["sfnt2woff" (str finput)])]
                  (when (zero? (:exit res))
                    foutput))
                (finally
                  (fs/delete finput)))))

          (woff->sfnt [data]
            (let [finput (tmp/tempfile :prefix "penpot" :suffix "")]
              (try
                (io/write* finput data)
                (let [res (shell/exec! system
                                       :cmd ["woff2sfnt" (str finput)]
                                       :out-enc :bytes
                                       :prlimit (get-font-prlimit)
                                       :timeout (get-font-timeout))]
                  (when (zero? (:exit res))
                    (:out res)))
                (finally
                  (fs/delete finput)))))

          (woff2->sfnt [data]
            ;; woff2_decompress outputs to same directory with .ttf extension
            (let [finput  (tmp/tempfile :prefix "penpot.font." :suffix ".woff2")
                  foutput (fs/path (str/replace (str finput) #"\.woff2$" ".ttf"))]
              (try
                (io/write* finput data)
                (let [res (exec-font! system ["woff2_decompress" (str finput)])]
                  (if (zero? (:exit res))
                    foutput
                    (do
                      (when (fs/exists? foutput)
                        (fs/delete foutput))
                      nil)))
                (finally
                  (fs/delete finput)))))

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
                  (assoc "font/ttf" sfnt)))))

        (contains? current "font/woff2")
        (let [data    (get input "font/woff2")
              foutput (woff2->sfnt data)]
          (when-not foutput
            (ex/raise :type :validation
                      :code :invalid-woff2-file
                      :hint "invalid woff2 file"))
          (try
            (let [sfnt  (io/read* foutput)
                  type (get-sfnt-type sfnt)]
              (cond-> input
                (= type :otf)
                (-> (assoc "font/otf" sfnt)
                    (assoc "font/ttf" (otf->ttf sfnt))
                    (update "font/woff" gen-if-nil #(ttf-or-otf->woff sfnt)))

                (= type :ttf)
                (-> (assoc "font/ttf" sfnt)
                    (assoc "font/otf" (ttf->otf sfnt))
                    (update "font/woff" gen-if-nil #(ttf-or-otf->woff sfnt)))))
            (finally
              (fs/delete foutput))))))))
