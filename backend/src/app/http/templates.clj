;; backend/src/app/http/templates.clj
(ns app.http.templates
  (:require
   [app.common.exceptions :as ex]
   [app.common.schema :as sm]
   [app.db :as db]
   [app.http.middleware :as mw] ; For auth and other common middleware
   [app.media :as mediaa] ; Renamed to avoid conflict with media namespace
   [app.storage :as sto]
   [app.rpc.permissions :as perms] ; Or similar for checking user permissions
   [app.http.session :as session] ; For getting profile-id
   [app.http.access-token :as actoken] ; For getting profile-id
   [integrant.core :as ig]
   [reitit.ring.middleware.multipart :as multipart]
   [clojure.java.io :as io]
   [app.common.logging :as l]
   [app.common.data :as d]
   [app.common.uuid :as uuid]
   [app.util.time :as dt]
   [clojure.string :as str]))

;; --- Schemas ---
(def schema:template-upload-params
  [:map
   [:file ; This will be the file part from multipart
    [:map ; Reitit's multipart middleware usually provides a map with these keys
     [:filename :string]
     [:size :int]
     [:tempfile ::sm/file] ; Instance of java.io.File
     [:content-type :string]]]])

(def schema:template-upload-response
  [:map
   [:id ::sm/uuid] ; This will be template-db-id
   [:name :string]
   [:storage_id ::sm/uuid]
   [:message :string]])

;; --- Handler Logic ---
(defn- handle-template-upload
  [cfg request]
  (let [profile-id (or (-> request ::session/data :profile-id)
                       (-> request ::actoken/data :id))
        params (:params request)
        file-details (get params "file")
        tempfile-path (some-> file-details :tempfile)]

    (l/info :msg "Attempting template upload" :profile-id profile-id :filename (:filename file-details))

    ;; (if-not profile-id
    ;;   (throw (ex/access-denied "Authentication required." {})))

    ;; 1. TODO: Add permission check if specific permissions are needed

    (if (nil? file-details)
      (throw (ex/validation-error "No file provided in the upload." {})))

    (mediaa/validate-media-size! {:size (:size file-details)})

    (when-not (or (= (:content-type file-details) "application/zip")
                  (= (:content-type file-details) "application/octet-stream")
                  (.endsWith (:filename file-details) ".penpot"))
      (throw (ex/validation-error (str "Invalid file type. Expected a .penpot file (application/zip), got " (:content-type file-details)) {})))

    (try
      (with-open [zip-file (java.util.zip.ZipFile. ^java.io.File tempfile-path)]
        (let [entry (.getEntry zip-file "data.json")]
          (when-not entry
            (throw (ex/validation-error "Invalid .penpot file: data.json not found in archive." {})))))
      (catch java.util.zip.ZipException e
        (throw (ex/validation-error (str "Error reading .penpot file (not a valid zip archive): " (.getMessage e)) {})))
      (catch Exception e
        (throw (ex/service-unavailable (str "Error validating .penpot file: " (.getMessage e)) {}))))
    
    (l/info :msg ".penpot file validated" :filename (:filename file-details))

    (let [storage (::sto/storage cfg)
          template-name (or (get params "name") (:filename file-details))
          tags (get params "tags")
          user-id profile-id
          s-obj-id (uuid/random)

          content-obj (sto/content tempfile-path (:size file-details))
          
          stored-object (sto/put-object! storage {::sto/content content-obj
                                                  ::sto/deduplicate? true
                                                  ::sto/touched-at (dt/now)
                                                  :bucket "penpot-templates"
                                                  :content-type (:content-type file-details)
                                                  :original-filename (:filename file-details)
                                                  :uploader-id user-id
                                                  :upload-name template-name
                                                  :tags tags
                                                  ;; It's generally better to let app.storage/create-database-object handle the ID generation
                                                  ;; unless you absolutely need to predefine it and ensure it's passed correctly.
                                                  ;; If storage_object.id needs to be known for another table *before* calling put-object!,
                                                  ;; then pre-generating is one way, but ensure app.storage handles it.
                                                  ;; For now, I'm removing :id s-obj-id from being explicitly passed here,
                                                  ;; assuming put-object! will generate and return it.
                                                  })]
      
      (l/info :msg "Template stored in storage" :storage-id (:id stored-object) :template-name template-name)

      ;; Create entry in the new 'templates' table
      (let [template-db-id (uuid/random) ; Generate ID for the templates table
            db-pool (::db/pool cfg) ; Get the database pool from the config
            ;; Convert tags to an array of strings for PostgreSQL text[] type.
            ;; If tags is already a collection, use it directly. If it's a comma-separated string, split it.
            tags-array (cond
                         (coll? tags) (into-array String tags)
                         (string? tags) (into-array String (str/split (str/trim tags) #",")) ; Added trim
                         :else nil)]

        (db/insert! db-pool :templates ; Table name is :templates
                    {:id template-db-id
                     :name template-name
                     :uploader_id user-id ;; This is profile-id
                     :storage_object_id (:id stored-object)
                     :tags tags-array
                     ;; :description can be added if captured from form params
                     :created_at (dt/now)
                     :updated_at (dt/now)})
        (l/info :msg "Template metadata registered in DB" :template-db-id template-db-id :storage-id (:id stored-object)))

      ;; Update the response body
      {:status 201
       :body {:id template-db-id ; Return the ID from the new 'templates' table
              :name template-name
              :storage_id (:id stored-object)
              :message "Template uploaded and registered successfully."}})))

;; --- Routes ---
(defmethod ig/init-key ::routes
  [_ cfg]
  ["/templates" {:middleware [[mw/cors]
                              [session/authz cfg]
                              [actoken/authz cfg]]}
   ["/upload" {:post {:handler (partial handle-template-upload cfg)
                      ;; It's good practice to also define :parameters for the body (multipart)
                      ;; if Reitit's coercion/validation is to be used.
                      ;; For multipart, it's often {:multipart schema:template-upload-params}
                      ;; but check Reitit docs for exact usage with coercion.
                      :parameters {:multipart schema:template-upload-params}
                      :responses {201 {:body schema:template-upload-response}}
                      :middleware [[multipart/create-multipart-middleware]]}}]])
