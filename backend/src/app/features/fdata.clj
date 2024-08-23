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
   [app.common.logging :as l]
   [app.db :as db]
   [app.db.sql :as-alias sql]
   [app.storage :as sto]
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
  [file]
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

(defn get-file-data
  "Get file data given a file instance."
  [system file]
  (if (offloaded? file)
    (let [storage (sto/resolve system ::db/reuse-conn true)]
      (->> (sto/get-object storage (:data-ref-id file))
           (sto/get-object-bytes storage)))
    (:data file)))

(defn resolve-file-data
  [system file]
  (let [data (get-file-data system file)]
    (assoc file :data data)))

(defn load-pointer
  "A database loader pointer helper"
  [system file-id id]
  (let [fragment (db/get* system :file-data-fragment
                          {:id id :file-id file-id}
                          {::sql/columns [:data :data-backend :data-ref-id :id]})]

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

    (let [data (get-file-data system fragment)]
      ;; FIXME: conditional thread scheduling for decoding big objects
      (blob/decode data))))

(defn persist-pointers!
  "Persist all currently tracked pointer objects"
  [system file-id]
  (let [conn (db/get-connection system)]
    (doseq [[id item] @pmap/*tracked*]
      (when (pmap/modified? item)
        (l/trc :hint "persist pointer" :file-id (str file-id) :id (str id))
        (let [content (-> item deref blob/encode)]
          (db/insert! conn :file-data-fragment
                      {:id id
                       :file-id file-id
                       :data content}))))))

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
  [file]
  (-> file
      (update :data (fn [fdata]
                      (-> fdata
                          (update :pages-index d/update-vals pmap/wrap)
                          (d/update-when :components pmap/wrap))))
      (update :features conj "fdata/pointer-map")))
