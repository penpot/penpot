;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC Sucursal en España SL

(ns app.media.remote
  "Remote media processing via the media-processor HTTP service."
  (:require
   [app.common.exceptions :as ex]
   [app.common.media :as cm]
   [app.common.time :as ct]
   [app.common.uri :as uri]
   [app.config :as cf]
   [app.http.client :as http]
   [app.media.local :as local]
   [app.media.validation :as validation]
   [app.setup :as-alias setup]
   [app.storage.tmp :as tmp]
   [app.util.json :as json]
   [cuerdas.core :as str]
   [datoteka.fs :as fs]
   [datoteka.io :as io])
  (:import
   java.io.ByteArrayInputStream
   java.io.InputStream
   java.io.SequenceInputStream
   java.net.ConnectException
   java.net.http.HttpTimeoutException
   java.util.Collections))

(defn- service-base-url
  "Returns the base URL of the media-processor service."
  []
  (or (cf/get :media-processing-service-uri)
      (ex/raise :type :internal
                :code :media-processor-not-configured
                :hint "PENPOT_MEDIA_PROCESSING_SERVICE_URI is not configured")))

(defn- service-timeout
  "Returns the HTTP timeout (ms) for media-processor requests."
  []
  (or (cf/get :media-processing-service-timeout)
      120000))

(defn- get-shared-key
  "Returns the shared key for authenticating with the media-processor."
  [system]
  (-> system ::setup/shared-keys :media-processor))

(defn- parse-json-response
  "Parse a JSON response body."
  [body]
  (json/read! body))

(defn- translate-error
  "Translate a media-processor error response into a Penpot exception."
  [status body]
  (let [code (or (:code body) "media-processor-error")
        hint (or (:hint body) "media-processor request failed")]
    (case status
      400 {:type :validation    :code (keyword code) :hint hint}
      403 {:type :authorization :code :forbidden      :hint hint}
      413 {:type :restriction   :code (keyword code) :hint hint}
      504 {:type :internal      :code :media-processor-timeout :hint hint}
      {:type :internal :code (keyword code) :hint hint})))

(defn service-request
  "Make an HTTP request to the media-processor service."
  [system {:keys [method uri body headers timeout]}]
  (let [client  (::http/client system)
        timeout (or timeout (service-timeout))]
    (try
      (let [resp (http/req client
                           {:method method
                            :uri uri
                            :body body
                            :headers headers}
                           {:response-type :input-stream
                            :skip-ssrf-check? true
                            :timeout timeout})
            status (:status resp)]
        (when (not (<= 200 status 299))
          (let [body (try (parse-json-response (:body resp)) (catch Exception _ nil))
                err  (translate-error status body)]
            (ex/raise :type (:type err) :code (:code err) :hint (:hint err))))
        resp)
      (catch ConnectException _cause
        (ex/raise :type :internal
                  :code :media-processor-unavailable
                  :hint "Cannot connect to media-processor service"))
      (catch HttpTimeoutException _cause
        (ex/raise :type :internal
                  :code :media-processor-timeout
                  :hint "media-processor service request timed out")))))

(defn- multipart-boundary
  []
  (str "----PenpotBoundary" (System/currentTimeMillis)))

(defn- build-multipart-stream
  "Build a streaming multipart/form-data body with a single file field.
   Returns an InputStream that lazily reads from the file on demand."
  [^String boundary mtype ^InputStream file-stream]
  (let [header (.getBytes (str "--" boundary "\r\n"
                               "Content-Disposition: form-data; name=\"file\"; filename=\"file\"\r\n"
                               "Content-Type: " mtype "\r\n"
                               "\r\n")
                          "UTF-8")
        footer (.getBytes (str "\r\n--" boundary "--\r\n")
                          "UTF-8")
        parts  (Collections/enumeration
                [(ByteArrayInputStream. header)
                 file-stream
                 (ByteArrayInputStream. footer)])]
    (SequenceInputStream. parts)))

(defn- service-multipart-request
  "Send a multipart request to the media-processor service.
   Accepts a file from disk via :path. The file stream is closed
   after the HTTP request completes (success or failure)."
  [system {:keys [endpoint path mtype query timeout]}]
  (let [shared-key  (get-shared-key system)
        boundary    (multipart-boundary)
        ctype       (or mtype "application/octet-stream")
        base-url    (service-base-url)
        request-uri (cond-> (uri/join base-url endpoint)
                      (seq query)
                      (str "?" (uri/map->query-string query)))]
    (with-open [file-stream (io/input-stream path)]
      (let [body (build-multipart-stream boundary ctype file-stream)]
        (service-request system
                         {:method  :post
                          :uri     request-uri
                          :body    body
                          :headers {"Content-Type" (str "multipart/form-data; boundary=" boundary)
                                    "x-shared-key" shared-key}
                          :timeout timeout})))))

(def ^:private known-font-types
  "Priority-ordered list of font mime-types the system knows how to convert.
   Order matters: when a font upload contains multiple variants, the first
   match becomes the conversion source (ttf preferred for best coverage)."
  ["font/ttf" "font/otf" "font/woff" "font/woff2"])

(defn- font-convert
  "Convert a font to the given target mime-type via the media-processor service.
   Accepts source font data as a filesystem Path. Returns a tempfile Path."
  [system source-mtype target-mtype data]
  (let [resp (service-multipart-request system {:endpoint "api/font/convert"
                                                :path     data
                                                :mtype    source-mtype
                                                :query    {:target-type target-mtype}
                                                :timeout  180000})
        ext  (cm/mtype->extension target-mtype)
        tmp  (tmp/tempfile :prefix "penpot.font." :suffix ext)]
    (io/write* tmp (:body resp))
    tmp))

(defn- font-missing-variants
  "Return the set of target mime-types that should be generated for the given
   source mime-type (excluding font/woff2, which is never generated)."
  [source-mtype]
  (case source-mtype
    "font/ttf"   #{"font/otf" "font/woff"}
    "font/otf"   #{"font/ttf" "font/woff"}
    "font/woff"  #{"font/ttf" "font/otf"}
    "font/woff2" #{"font/ttf" "font/otf" "font/woff"}))

(defmulti process (fn [_system params] (:cmd params)))

(defmethod process :info
  [system {:keys [input]}]
  (let [{:keys [path mtype]} (validation/check-input input)]
    (if (= mtype "image/svg+xml")
      ;; SVG: parse locally (Sharp doesn't support SVG)
      (let [info (some-> path slurp local/parse-svg local/get-basic-info-from-svg)]
        (when-not info
          (ex/raise :type :validation
                    :code :invalid-svg-file
                    :hint "uploaded svg does not provide dimensions"))
        (merge input info {:ts (ct/now) :size (fs/size path)}))
      ;; Raster: delegate to media-processor
      (let [resp (service-multipart-request system {:endpoint "api/image/info"
                                                    :path     path
                                                    :mtype    mtype})
            info (parse-json-response (:body resp))
            detected-mtype (:mtype info)]
        (when (and (string? mtype)
                   (string? detected-mtype)
                   (not= (str/lower mtype) (str/lower detected-mtype)))
          (ex/raise :type :validation
                    :code :media-type-mismatch
                    :hint (str "File content does not match the declared type. "
                               "Expected: " mtype ". Got: " detected-mtype)))
        (assoc input
               :width  (:width info)
               :height (:height info)
               :size   (fs/size path)
               :ts     (ct/now))))))

(defn- thumbnail-request
  "Shared implementation for generic-thumbnail and profile-thumbnail."
  [system params mode]
  (let [{:keys [input format quality width height]} params
        {:keys [path mtype]} (validation/check-input input)
        fmt        (name (or format (cm/mtype->format mtype) :jpeg))
        resp       (service-multipart-request system {:endpoint "api/image/thumbnail"
                                                      :path     path
                                                      :mtype    mtype
                                                      :query    {:width   width
                                                                 :height  height
                                                                 :quality quality
                                                                 :format  fmt
                                                                 :mode    mode}})
        out-format (or format (cm/mtype->format mtype) :jpeg)
        ext        (cm/format->extension out-format)
        tmp        (tmp/tempfile :prefix "penpot.media." :suffix ext)
        _          (io/write* tmp (:body resp))]
    (assoc params
           :format out-format
           :mtype  (cm/format->mtype out-format)
           :size   (fs/size tmp)
           :data   tmp)))

(defmethod process :generic-thumbnail
  [system params]
  (thumbnail-request system params "fit"))

(defmethod process :profile-thumbnail
  [system params]
  (thumbnail-request system params "crop"))

(defmethod process :generate-fonts
  [system {:keys [input]}]
  (let [source-mtype (or (some #(when (contains? input %) %) known-font-types)
                         (ex/raise :type :validation
                                   :code :invalid-font
                                   :hint "No recognized font variant in input"))
        data         (get input source-mtype)
        present      (set (keys input))
        targets      (remove present (font-missing-variants source-mtype))]
    (reduce (fn [acc target-mtype]
              (assoc acc target-mtype
                     (font-convert system source-mtype target-mtype data)))
            input
            targets)))

