(ns app.srepl.main
  "A  main namespace for server repl."
  #_:clj-kondo/ignore
  (:require
   [app.common.data :as d]
   [app.common.exceptions :as ex]
   [app.common.logging :as l]
   [app.common.pages :as cp]
   [app.common.pages.migrations :as pmg]
   [app.common.uuid :as uuid]
   [app.config :as cfg]
   [app.db :as db]
   [app.db.sql :as sql]
   [app.main :refer [system]]
   [app.rpc.queries.profile :as prof]
   [app.srepl.dev :as dev]
   [app.util.blob :as blob]
   [app.util.time :as dt]
   [clojure.spec.alpha :as s]
   [clojure.walk :as walk]
   [cuerdas.core :as str]
   [expound.alpha :as expound]
   [fipp.edn :refer [pprint]]))

;; ==== Utility functions

(defn reset-file-data
  "Hardcode replace of the data of one file."
  [system id data]
  (db/with-atomic [conn (:app.db/pool system)]
    (db/update! conn :file
                {:data data}
                {:id id})))

(defn get-file
  "Get the migrated data of one file."
  [system id]
  (-> (:app.db/pool system)
      (db/get-by-id :file id)
      (update :data app.util.blob/decode)
      (update :data pmg/migrate-data)))

(defn duplicate-file
  "This is a raw version of duplication of file just only for forensic analysis."
  [system file-id email]
  (db/with-atomic [conn (:app.db/pool system)]
    (when-let [profile (some->> (prof/retrieve-profile-data-by-email conn (str/lower email))
                                (prof/populate-additional-data conn))]
      (when-let [file (db/exec-one! conn (sql/select :file {:id file-id}))]
        (let [params (assoc file
                            :id (uuid/next)
                            :project-id (:default-project-id profile))]
          (db/insert! conn :file params)
          (:id file))))))

(defn update-file
  "Apply a function to the data of one file. Optionally save the changes or not.
  
  The function receives the decoded and migrated file data."
  ([system id f] (update-file system id f false))
  ([system id f save?]
   (db/with-atomic [conn (:app.db/pool system)]
     (let [file (db/get-by-id conn :file id {:for-update true})
           file (-> file
                    (update :data app.util.blob/decode)
                    (update :data pmg/migrate-data)
                    (update :data f)
                    (update :data blob/encode)
                    (update :revn inc))]
       (when save?
         (db/update! conn :file
                     {:data (:data file)}
                     {:id (:id file)}))
       (update file :data blob/decode)))))

(defn analyze-files
  "Apply a function to all files in the database, reading them in batches. Do not change data.
  
  The function receives an object with some properties of the file and the decoded data, and
  an empty atom where it may accumulate statistics, if desired."
  [system {:keys [sleep chunk-size max-chunks on-file]
           :or {sleep 1000 chunk-size 10 max-chunks ##Inf}}]
  (let [stats (atom {})]
    (letfn [(retrieve-chunk [conn cursor]
              (let [sql (str "select id, name, modified_at, data from file "
                             " where modified_at < ? and deleted_at is null "
                             " order by modified_at desc limit ?")]
                (->> (db/exec! conn [sql cursor chunk-size])
                     (map #(update % :data blob/decode)))))

            (process-chunk [chunk]
              (loop [files chunk]
                (when-let [file (first files)]
                  (on-file file stats)
                  (recur (rest files)))))]

      (db/with-atomic [conn (:app.db/pool system)]
        (loop [cursor (dt/now)
               chunks 0]
          (when (< chunks max-chunks)
            (let [chunk (retrieve-chunk conn cursor)]
              (when-not (empty? chunk)
                (let [cursor (-> chunk last :modified-at)]
                  (process-chunk chunk)
                  (Thread/sleep (inst-ms (dt/duration sleep)))
                  (recur cursor (inc chunks)))))))
        @stats))))

(defn update-pages
  "Apply a function to all pages of one file. The function receives a page and returns an updated page."
  [data f]
  (update data :pages-index d/update-vals f))

(defn update-shapes
  "Apply a function to all shapes of one page The function receives a shape and returns an updated shape"
  [page f]
  (update page :objects d/update-vals f))


;; ==== Specific fixes

(defn repair-orphaned-shapes
  "There are some shapes whose parent has been deleted. This
  function detects them and puts them as children of the root node."
  ([file _] ; to be called from analyze-files to search for files with the problem
   (repair-orphaned-shapes (:data file)))

  ([data]
   (let [is-orphan? (fn [shape objects]
                      (and (some? (:parent-id shape))
                           (nil? (get objects (:parent-id shape)))))

         update-page (fn [page]
                       (let [objects (:objects page)
                             orphans (set (filter #(is-orphan? % objects) (vals objects)))]
                         (if (seq orphans)
                           (do
                             (prn (:id data) "file has" (count orphans) "broken shapes")
                             (-> page
                                 (update-shapes (fn [shape]
                                                  (if (orphans shape)
                                                    (assoc shape :parent-id uuid/zero)
                                                    shape)))
                                 (update-in [:objects uuid/zero :shapes]
                                            (fn [shapes] (into shapes (map :id orphans))))))
                           page)))]

     (update-pages data update-page))))


;; DO NOT DELETE already used scripts, could be taken as templates for easyly writing new ones
;; -------------------------------------------------------------------------------------------

;; (defn repair-orphaned-components
;;   "We have detected some cases of component instances that are not nested, but
;;   however they have not the :component-root? attribute (so the system considers
;;   them nested). This script fixes this adding them the attribute.
;;
;;   Use it with the update-file function above."
;;   [data]
;;   (let [update-page
;;         (fn [page]
;;           (prn "================= Page:" (:name page))
;;           (letfn [(is-nested? [object]
;;                     (and (some? (:component-id object))
;;                          (nil? (:component-root? object))))
;;
;;                   (is-instance? [object]
;;                     (some? (:shape-ref object)))
;;
;;                   (get-parent [object]
;;                     (get (:objects page) (:parent-id object)))
;;
;;                   (update-object [object]
;;                     (if (and (is-nested? object)
;;                              (not (is-instance? (get-parent object))))
;;                       (do
;;                         (prn "Orphan:" (:name object))
;;                         (assoc object :component-root? true))
;;                       object))]
;;
;;             (update page :objects d/update-vals update-object)))]
;;
;;     (update data :pages-index d/update-vals update-page)))

;; (defn check-image-shapes
;;   [{:keys [data] :as file} stats]
;;   (println "=> analizing file:" (:name file) (:id file))
;;   (swap! stats update :total-files (fnil inc 0))
;;   (let [affected? (atom false)]
;;     (walk/prewalk (fn [obj]
;;                     (when (and (map? obj) (= :image (:type obj)))
;;                       (when-let [fcolor (some-> obj :fill-color str/upper)]
;;                         (when (or (= fcolor "#B1B2B5")
;;                                   (= fcolor "#7B7D85"))
;;                           (reset! affected? true)
;;                           (swap! stats update :affected-shapes (fnil inc 0))
;;                           (println "--> image shape:" ((juxt :id :name :fill-color :fill-opacity) obj)))))
;;                     obj)
;;                   data)
;;     (when @affected?
;;       (swap! stats update :affected-files (fnil inc 0)))))

