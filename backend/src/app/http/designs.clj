;; backend/src/app/http/designs.clj
(ns app.http.designs
  (:require
   [app.common.exceptions :as ex]
   [app.common.schema :as sm]
   [app.db :as db]
   [app.http.middleware :as mw]
   [app.http.session :as session]
   [app.http.access-token :as actoken]
   [app.rpc.commands.files :as cmd.files]
   [app.rpc.commands.files-update :as cmd.files-update]
   [app.common.files.changes :as cpc]
   [app.common.files.helpers :as cfh] 
   [app.common.types.file :as ctf] 
   [app.common.uuid :as uuid]
   [app.util.time :as dt]
   [app.common.logging :as l]
   [app.features.fdata :as feat.fdata]
   [app.features.file-migrations :as feat.fmigr]
   [app.util.blob :as blob]
   [app.common.features :as cfeat] ; Required for feature flag bindings
   [integrant.core :as ig]
   [clojure.string :as str]))

;; --- Schemas ---
(def schema:object-properties [:map-of :keyword :any])

(def schema:update-operation
  [:map
   [:action [:= "updateObject"]]
   [:elementId ::sm/uuid]
   [:pageId ::sm/uuid]
   [:componentId {:optional true} ::sm/uuid]
   [:properties schema:object-properties]])

(def schema:create-operation
  [:map
   [:action [:= "createObject"]]
   [:newObjectId ::sm/uuid]
   [:elementType :string] 
   [:pageId ::sm/uuid]
   [:frameId ::sm/uuid]
   [:parentId ::sm/uuid]
   [:index {:optional true} :int]
   [:properties schema:object-properties]])

(def schema:delete-operation
  [:map
   [:action [:= "deleteObject"]]
   [:elementId ::sm/uuid]
   [:pageId ::sm/uuid]
   [:componentId {:optional true} ::sm/uuid]])

(def schema:placeholder-target
  [:map
   [:elementId {:optional true} ::sm/uuid]
   [:elementName {:optional true} :string]
   [:placeholderText {:optional true} :string]])

(def schema:replace-placeholder-operation
  [:map
   [:action [:= "replacePlaceholder"]]
   [:pageId ::sm/uuid]
   [:componentId {:optional true} ::sm/uuid]
   [:target schema:placeholder-target]
   [:valueType [:enum "text" "imageUrl"]]
   [:value :string]])

(def schema:individual-operation
  [:multi {:dispatch :action}
   ["updateObject" schema:update-operation]
   ["createObject" schema:create-operation]
   ["deleteObject" schema:delete-operation]
   ["replacePlaceholder" schema:replace-placeholder-operation]])

(def schema:design-modification-payload
  [:map
   [:operations [:sequential {:min 1} schema:individual-operation]]])

(def schema:design-modification-response
  [:map
   [:fileId ::sm/uuid]
   [:newRevision :int]
   [:message :string]])

;; --- Handler Logic ---

;; Function to translate API payload operation to Penpot internal change object(s)
(defn- api-op-to-penpot-changes [api-op file-data-map] ; file-data-map is the decoded :data of the file
  (let [action (:action api-op)]
    (cond
      (= action "updateObject")
      (let [props-to-set (->> (:properties api-op)
                              (mapv (fn [[k v]] {:type :set, :attr k, :val v})))]
        [{:type :mod-obj
          :id (:elementId api-op)
          :page-id (:pageId api-op)
          :component-id (:componentId api-op)
          :operations props-to-set}])

      (= action "createObject")
      (let [base-obj (merge {:id (:newObjectId api-op)
                             :type (keyword (:elementType api-op))}
                            (:properties api-op))
            ;; TODO: Potentially call a function here to ensure all necessary default 
            ;;       attributes for the given elementType are present, similar to how 
            ;;       Penpot's internal object creation might work.
            final-obj base-obj] 
        [{:type :add-obj
          :id (:newObjectId api-op)
          :obj final-obj
          :page-id (:pageId api-op)
          :frame-id (:frameId api-op)
          :parent-id (:parentId api-op)
          :index (:index api-op)}])

      (= action "deleteObject")
      [{:type :del-obj
        :id (:elementId api-op)
        :page-id (:pageId api-op)
        :component-id (:componentId api-op)}]
      
      (= action "replacePlaceholder")
      (let [{:keys [pageId componentId target valueType value]} api-op
            object-id (:elementId target)] 

        (when-not object-id
          (throw (ex/validation-error "Target elementId is required for replacePlaceholder." {:target target})))

        ;; Determine the correct objects map based on whether componentId is present
        (let [objects-map (if componentId
                            (get-in file-data-map [:components componentId :objects])
                            (get-in file-data-map [:pages-index pageId :objects]))
              target-object (get objects-map object-id)]

          (when-not target-object
            (throw (ex/not-found (str "Target element " object-id " not found on page " pageId (if componentId (str " in component " componentId) "")) 
                                 {:target target :pageId pageId :componentId componentId})))

          (cond
            (= valueType "text")
            (if (= (:type target-object) :text) 
              (let [current-text (or (:text target-object) "") ; Assuming :text, verify actual field
                    placeholder-pattern (when-let [pt (:placeholderText target)] 
                                          (try (re-pattern (java.util.regex.Pattern/quote pt))
                                               (catch Exception e 
                                                 (throw (ex/validation-error (str "Invalid regex pattern for placeholderText: " pt) {:placeholderText pt :error (.getMessage e)})))))
                    new-text (if (and placeholder-pattern (:placeholderText target)) ; Check if placeholderText was actually provided
                               (str/replace current-text placeholder-pattern value)
                               value)] 
                [{:type :mod-obj
                  :id object-id
                  :page-id (when (not componentId) pageId) ; page-id only if not in component context for mod-obj
                  :component-id componentId
                  :operations [{:type :set, :attr :text, :val new-text}]}]) ; Verify :text is correct attr
              (throw (ex/validation-error (str "Target element " object-id " is not a text object.") 
                                          {:elementId object-id :actualType (:type target-object)})))

            (= valueType "imageUrl")
            (let [media-asset-id (try (uuid/parse value) (catch Exception _ nil))]
              (when-not media-asset-id
                (throw (ex/validation-error (str "Invalid imageUrl value: expected a UUID string representing a Penpot media asset ID.") {:value value})))
              
              (let [new-image-fill {:type "image" 
                                    :visible? true 
                                    :opacity 1 
                                    :scale-mode "fill" 
                                    :image-asset (str media-asset-id) 
                                    ;; :image-transform may need default or preservation
                                    }]
                ;; This assumes the target is a shape where :fills is the correct property.
                ;; If target-object is an 'image' type, the attribute might be different (e.g., :image-src-id or similar).
                [{:type :mod-obj
                  :id object-id
                  :page-id (when (not componentId) pageId)
                  :component-id componentId
                  :operations [{:type :set, :attr :fills, :val [new-image-fill]}]}])) 

            :else
            (throw (ex/validation-error (str "Unknown valueType for replacePlaceholder: " valueType) {:valueType valueType})))))

      :else
      (throw (ex/validation-error (str "Unknown action type: " action) {:action action})))))

(defn- handle-modify-design
  [cfg request]
  (db/tx-run! cfg 
    (fn [{:keys [::db/conn] :as current-cfg}] 
      (let [profile-id (or (-> request ::session/data :profile-id)
                           (-> request ::actoken/data :id))
            file-id (get-in request [:path-params :id])
            api-operations (get-in request [:body-params :operations])

            _ (l/info :msg "Attempting design modification" :profile-id profile-id :file-id file-id :num-operations (count api-operations))
            _ (l/debug :msg "Received API operations payload" :file-id file-id :profile-id profile-id :payload api-operations)

            _ (cmd.files/check-edition-permissions! conn profile-id file-id)

            current-file-raw (cmd.files-update/get-file conn file-id)

            _ (when-not current-file-raw
                (throw (ex/not-found (str "File " file-id " not found.") {:file-id file-id})))

            current-file-with-resolved-data (-> current-file-raw
                                                (feat.fmigr/resolve-applied-migrations current-cfg)
                                                (feat.fdata/resolve-file-data current-cfg)
                                                (cmd.files/decode-row))
            
            current-data-map (:data current-file-with-resolved-data)

            penpot-changes (try
                             (vec (mapcat #(api-op-to-penpot-changes % current-data-map) api-operations))
                             (catch Exception e
                               (l/error :msg "Failed to translate API operations to Penpot changes"
                                        :file-id file-id
                                        :profile-id profile-id
                                        :error (.getMessage e)
                                        :first-api-operation (first api-operations))
                               (throw e)))
            
            _ (l/debug :msg "Translated Penpot changes" :count (count penpot-changes) :changes penpot-changes)]

        (let [updated-data-map (binding [cpc/*state* (atom {}) 
                                         cfeat/*current* (:features current-file-with-resolved-data) 
                                         cfeat/*previous* (:features current-file-with-resolved-data)]
                                 (cpc/process-changes current-data-map penpot-changes))

              new-revn (inc (:revn current-file-raw))
              
              file-to-persist (-> current-file-raw
                                  (assoc :data (blob/encode updated-data-map) 
                                         :revn new-revn
                                         :features (:features current-file-with-resolved-data) 
                                         :modified-at (dt/now)))
              
              _ (l/debug :msg "File data updated in memory, attempting persistence" :file-id file-id :new-revn new-revn)]

          (cmd.files-update/persist-file! current-cfg file-to-persist)
          _ (l/debug :msg "File persisted successfully" :file-id file-id :new-revn new-revn)

          (let [timestamp (dt/now)] 
             (db/insert! conn :file_change
                  {:id (uuid/next)
                   :session-id (or (:session-id (:params request)) 
                                   (uuid/nil-uuid)) 
                   :profile-id profile-id
                   :created-at timestamp
                   :updated-at timestamp
                   :deleted-at (dt/plus timestamp (dt/duration {:hours 1})) 
                   :file-id file-id
                   :revn new-revn
                   :version (:version file-to-persist) 
                   :features (:features file-to-persist)
                   :changes (blob/encode penpot-changes)}
                  {::db/return-keys false}))
          _ (l/debug :msg "Change record inserted into file_change table" :file-id file-id :revn new-revn)

          ;; TODO: Send notifications via mbus

          (l/info :msg "Design modification successful" :file-id file-id :new-revn new-revn)
          
          {:status 200
           :body {:fileId file-id
                  :newRevision new-revn
                  :message "Design updated successfully."}})))))

(defmethod ig/init-key ::routes
  [_ cfg]
  ["/designs" {:middleware [[mw/cors]
                              [session/authz cfg] 
                              [actoken/authz cfg]
                              [mw/body-params]]} 
   ["/:id/modify" {:post {:handler (partial handle-modify-design cfg)
                           :parameters {:path {:id ::sm/uuid}
                                        :body schema:design-modification-payload}
                           :responses {200 {:body schema:design-modification-response}
                                        ;; TODO: Define other error responses (400, 403, 404, 500)
                                       }}}]]))
