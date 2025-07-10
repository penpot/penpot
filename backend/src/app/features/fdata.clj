;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC

(ns app.features.fdata
  "A `fdata/*` related feature migration helpers"
  (:require
   [app.common.data :as d]
   [app.common.exceptions :as ex]
   [app.common.files.helpers :as cfh]
   [app.common.files.migrations :as fmg]
   [app.common.logging :as l]
   [app.common.types.path :as path]
   [app.db :as db]
   [app.db.sql :as-alias sql]
   ;; [app.storage :as sto]
   [app.util.blob :as blob]
   [app.util.objects-map :as omap]
   [app.util.pointer-map :as pmap]))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; OFFLOAD
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(defn offloaded?
  [file]
  (= "objects-storage" (:data-backend file)))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; OBJECTS-MAP
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(defn enable-objects-map
  [file & _opts]
  (let [update-page
        (fn [page]
          (if (and (pmap/pointer-map? page)
                   (not (pmap/loaded? page)))
            page
            (update page :objects omap/wrap)))

        update-data
        (fn [fdata]
          (update fdata :pages-index d/update-vals update-page))]

    (-> file
        (update :data update-data)
        (update :features conj "fdata/objects-map"))))

(defn process-objects
  "Apply a function to all objects-map on the file. Usualy used for convert
  the objects-map instances to plain maps"
  [fdata update-fn]
  (if (contains? fdata :pages-index)
    (update fdata :pages-index d/update-vals
            (fn [page]
              (update page :objects
                      (fn [objects]
                        (if (omap/objects-map? objects)
                          (update-fn objects)
                          objects)))))
    fdata))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; POINTER-MAP
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

;; (defn get-file-data
;;   "Get file data given a file instance."
;;   [system file]
;;   (if (offloaded? file)
;;     (let [storage (sto/resolve system ::db/reuse-conn true)]
;;       (->> (sto/get-object storage (:data-ref-id file))
;;            (sto/get-object-bytes storage)))
;;     (:data file)))

(defn load-pointer
  "A database loader pointer helper"
  [system file-id id]
  (let [fragment (db/get* system :file-data
                          {:id id :file-id file-id :type "fragment"}
                          {::sql/columns [:content :backend :id]})]

    (l/trc :hint "load pointer"
           :file-id (str file-id)
           :id (str id)
           :found (some? fragment))

    (when-not fragment
      (ex/raise :type :internal
                :code :fragment-not-found
                :hint "fragment not found"
                :file-id file-id
                :fragment-id id))

    ;; FIXME: conditional thread scheduling for decoding big objects
    (blob/decode (:data fragment))))

(defn persist-pointers!
  "Persist all currently tracked pointer objects"
  [system file-id]
  (let [conn (db/get-connection system)]
    (doseq [[id item] @pmap/*tracked*]
      (when (pmap/modified? item)
        (l/trc :hint "persist pointer" :file-id (str file-id) :id (str id))
        (let [content (-> item deref blob/encode)]
          (db/insert! conn :file-data
                      {:id id
                       :file-id file-id
                       :type "fragment"
                       :content content}))))))

(defn process-pointers
  "Apply a function to all pointers on the file. Usuly used for
  dereference the pointer to a plain value before some processing."
  [fdata update-fn]
  (let [update-fn' (fn [val]
                     (if (pmap/pointer-map? val)
                       (update-fn val)
                       val))]
    (-> fdata
        (d/update-vals update-fn')
        (update :pages-index d/update-vals update-fn'))))

(defn get-used-pointer-ids
  "Given a file, return all pointer ids used in the data."
  [fdata]
  (->> (concat (vals fdata)
               (vals (:pages-index fdata)))
       (into #{} (comp (filter pmap/pointer-map?)
                       (map pmap/get-id)))))

(defn enable-pointer-map
  "Enable the fdata/pointer-map feature on the file."
  [file & _opts]
  (-> file
      (update :data (fn [fdata]
                      (-> fdata
                          (update :pages-index d/update-vals pmap/wrap)
                          (d/update-when :components pmap/wrap))))
      (update :features conj "fdata/pointer-map")))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; PATH-DATA
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(defn enable-path-data
  "Enable the fdata/path-data feature on the file."
  [file & _opts]
  (letfn [(update-object [object]
            (if (or (cfh/path-shape? object)
                    (cfh/bool-shape? object))
              (update object :content path/content)
              object))

          (update-container [container]
            (d/update-when container :objects d/update-vals update-object))]

    (-> file
        (update :data (fn [data]
                        (-> data
                            (update :pages-index d/update-vals update-container)
                            (d/update-when :components d/update-vals update-container))))
        (update :features conj "fdata/path-data"))))

(defn disable-path-data
  [file & _opts]
  (letfn [(update-object [object]
            (if (or (cfh/path-shape? object)
                    (cfh/bool-shape? object))
              (update object :content vec)
              object))

          (update-container [container]
            (d/update-when container :objects d/update-vals update-object))]

    (when-let [conn db/*conn*]
      (db/delete! conn :file-migration {:file-id (:id file)
                                        :name "0003-convert-path-content"}))
    (-> file
        (update :data (fn [data]
                        (-> data
                            (update :pages-index d/update-vals update-container)
                            (d/update-when :components d/update-vals update-container))))
        (update :features disj "fdata/path-data")
        (update :migrations disj "0003-convert-path-content")
        (vary-meta update ::fmg/migrated disj "0003-convert-path-content"))))


;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; STORAGE
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(defn resolve-row
  [{:keys [legacy-data data] :as file}]
  (if (and (some? legacy-data) (not data))
    (-> file
        (assoc :data legacy-data)
        (dissoc :legacy-data))
    (dissoc file :legacy-data)))

(defn resolve-file-data
  [_system file]
  (resolve-row file))

;; FIXME: rename to resolve-file
(defn decode-file-data
  [_system file]
  (-> file
      (d/update-when :migrations
                     (fn [migrations]
                       (if (db/pgarray? migrations)
                         (db/decode-pgarray migrations [])
                         migrations)))
      (d/update-when :features
                     (fn [features]
                       (if (db/pgarray? features)
                         (db/decode-pgarray features #{})
                         features)))
      ;; FIXME: move to separated thread
      (d/update-when :data blob/decode)))
