;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC

(ns common-tests.helpers.files
  (:require
   [app.common.data :as d]
   [app.common.features :as ffeat]
   [app.common.files.changes :as cfc]
   [app.common.files.validate :as cfv]
   [app.common.pprint :refer [pprint]]
   [app.common.types.file :as ctf]
   [app.common.types.page :as ctp]
   [app.common.types.pages-list :as ctpl]
   [app.common.uuid :as uuid]
   [common-tests.helpers.ids-map :as thi]
   [cuerdas.core :as str]))

;; ----- Files

(defn sample-file
  [label & {:keys [page-label name] :as params}]
  (binding [ffeat/*current* #{"components/v2"}]
    (let [params (cond-> params
                   label
                   (assoc :id (thi/new-id! label))

                   page-label
                   (assoc :page-id (thi/new-id! page-label))

                   (nil? name)
                   (assoc :name "Test file"))

          file (-> (ctf/make-file (dissoc params :page-label))
                   (assoc :features #{"components/v2"}))

          page (-> file
                   :data
                   (ctpl/pages-seq)
                   (first))]

      (with-meta file
        {:current-page-id (:id page)}))))

(defn validate-file!
  ([file] (validate-file! file {}))
  ([file libraries]
   (cfv/validate-file-schema! file)
   (cfv/validate-file! file libraries)))

(defn apply-changes
  [file changes]
  (let [file' (ctf/update-file-data file #(cfc/process-changes % (:redo-changes changes) true))]
    (validate-file! file')
    file'))

;; ----- Pages

(defn sample-page
  [label & {:keys [] :as params}]
  (ctp/make-empty-page (assoc params :id (thi/new-id! label))))

(defn add-sample-page
  [file label & {:keys [] :as params}]
  (let [page (sample-page label params)]
    (-> file
        (ctf/update-file-data #(ctpl/add-page % page))
        (vary-meta assoc :current-page-id (:id page)))))

(defn get-page
  [file label]
  (ctpl/get-page (:data file) (thi/id label)))

(defn current-page-id
  [file]
  (:current-page-id (meta file)))

(defn current-page
  [file]
  (ctpl/get-page (:data file) (current-page-id file)))

(defn switch-to-page
  [file label]
  (vary-meta file assoc :current-page-id (thi/id label)))

;; ----- Debug

(defn dump-file-type
  "Dump a file using dump-tree function in common.types.file."
  [file & {:keys [page-label libraries] :as params}]
  (let [params    (-> params
                      (or {:show-ids true :show-touched true})
                      (dissoc page-label libraries))
        page      (if (some? page-label)
                    (:id (get-page file page-label))
                    (current-page-id file))
        libraries (or libraries {})]

    (ctf/dump-tree file page libraries params)))

(defn pprint-file
  "Pretry print a file trying to limit the quantity of info shown."
  [file & {:keys [level length] :or {level 10 length 1000}}]
  (pprint file {:level level :length length}))

(defn dump-shape
  "Dump a shape, with each attribute in a line."
  [shape]
  (println "{")
  (doseq [[k v] (sort shape)]
    (when (some? v)
      (println (str "    " k " : " v))))
  (println "}"))

(defn- stringify-keys [m keys]
  (apply str (interpose ", " (map #(str % ": " (get m %)) keys))))

(defn- dump-page-shape
  [shape keys padding]
  (println (str/pad (str padding
                         (when (:main-instance shape) "{")
                         (or (thi/label (:id shape)) "<no-label>")
                         (when (:main-instance shape) "}")
                         (when keys
                           (str " [" (stringify-keys shape keys) "]")))
                    {:length 40 :type :right})
           (if (nil? (:shape-ref shape))
             (if (:component-root shape)
               (str "# [Component " (or (thi/label (:component-id shape)) "<no-label>") "]")
               "")
             (str/format "%s--> %s%s"
                         (cond (:component-root shape) "#"
                               (:component-id shape) "@"
                               :else "-")
                         (if (:component-root shape)
                           (str "[Component " (or (thi/label (:component-id shape)) "<no-label>") "] ")
                           "")
                         (or (thi/label (:shape-ref shape)) "<no-label>")))))

(defn dump-page
  "Dump the layer tree of the page. Print the label of each shape, and the specified keys."
  ([page keys]
   (dump-page page uuid/zero "" keys))
  ([page root-id padding keys]
   (let [lookupf (d/getf (:objects page))
         root-shape (lookupf root-id)
         shapes (map lookupf (:shapes root-shape))]
     (doseq [shape shapes]
       (dump-page-shape shape keys padding)
       (dump-page page (:id shape) (str padding "    ") keys)))))

(defn dump-file
  "Dump the current page of the file, using dump-page above.
   Example: (thf/dump-file file [:id :touched])"
  ([file] (dump-file file []))
  ([file keys] (dump-page (current-page file) keys)))
