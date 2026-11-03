;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC Sucursal en España SL

(ns app.media.validation
  "Schemas and validation functions for media uploads.
   Leaf namespace — depends on app.common.* and app.config only."
  (:require
   [app.common.exceptions :as ex]
   [app.common.media :as cm]
   [app.common.schema :as sm]
   [app.config :as cf]
   [cuerdas.core :as str]
   [datoteka.fs :as fs]))

(def schema:upload
  [:map {:title "Upload"}
   [:filename :string]
   [:size ::sm/int]
   [:path ::fs/path]
   [:mtype {:optional true} :string]
   [:headers {:optional true}
    [:map-of :string :string]]])

(def schema:input
  [:map {:title "Input"}
   [:path ::fs/path]
   [:mtype {:optional true} ::sm/text]])

(def check-input
  (sm/check-fn schema:input))

(def allowed-upload-prefixes
  ;; Upload temp files are created server-side by the multipart parser
  ;; (yetti `default-temp-dir`, `/tmp/undertow/`) plus the tmp dir used
  ;; by tests and storage tmp files (`/tmp/penpot/`). Anything else
  ;; (e.g. `/etc/passwd` smuggled via Transit `~#path`) is rejected.
  ["/tmp/undertow/" "/tmp/penpot/"])

(defn check-upload-path
  [upload]
  (let [real (try (str (fs/real (:path upload)))
                  (catch Exception _ nil))]
    (when (or (nil? real)
              (not (some #(str/starts-with? real %) allowed-upload-prefixes)))
      (ex/raise :type :validation
                :code :params-validation
                :hint "invalid upload path"))
    upload))

(defn check-media-type
  ([upload] (check-media-type upload cm/image-types))
  ([upload allowed]
   (when-not (contains? allowed (:mtype upload))
     (ex/raise :type :validation
               :code :media-type-not-allowed
               :hint "Seems like you are uploading an invalid media object"))

   upload))

(defn check-media-size
  [upload]
  (let [max-size (cf/get :media-max-file-size)]
    (when (> (:size upload) max-size)
      (ex/raise :type :restriction
                :code :media-max-file-size-reached
                :hint (str/ffmt "the uploaded file size % is greater than the maximum %"
                                (:size upload)
                                max-size)))
    upload))

(defn check-font-size
  "Validates that the font file `upload` does not exceed the configured
  `:font-max-file-size` limit.  Accepts the same map shape as
  `check-media-size` — requires a `:size` key in bytes."
  [upload]
  (let [max-size (cf/get :font-max-file-size)]
    (when (> (:size upload) max-size)
      (ex/raise :type :restriction
                :code :font-max-file-size-reached
                :hint (str/ffmt "the uploaded font size % is greater than the maximum %"
                                (:size upload)
                                max-size)))
    upload))
