;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC

(ns app.common.test-helpers.files
  (:require
   [app.common.data :as d]
   [app.common.features :as ffeat]
   [app.common.files.changes :as cfc]
   [app.common.files.validate :as cfv]
   [app.common.pprint :refer [pprint]]
   [app.common.test-helpers.ids-map :as thi]
   [app.common.types.component :as ctk]
   [app.common.types.file :as ctf]
   [app.common.types.page :as ctp]
   [app.common.types.pages-list :as ctpl]
   [app.common.uuid :as uuid]
   [cuerdas.core :as str]))

;; ----- Files

(defn sample-file
  [label & {:keys [page-label name view-only?] :as params}]
  (let [params
        (cond-> params
          label
          (assoc :id (thi/new-id! label))

          (nil? name)
          (assoc :name "Test file")

          :always
          (assoc :features ffeat/default-features))

        opts
        (cond-> {}
          page-label
          (assoc :page-id (thi/new-id! page-label)))

        file (-> (ctf/make-file params opts)
                 (assoc :permissions {:can-edit (not (true? view-only?))}))

        page (-> file
                 :data
                 (ctpl/pages-seq)
                 (first))]

    (with-meta file
      {:current-page-id (:id page)})))

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

(defn apply-undo-changes
  [file changes]
  (let [file' (ctf/update-file-data file #(cfc/process-changes % (:undo-changes changes) true))]
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

(defn dump-tree
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
  (let [kv (-> (select-keys m keys)
               (assoc :swap-slot (when ((set keys) :swap-slot)
                                   (ctk/get-swap-slot m)))
               (assoc :swap-slot-label (when ((set keys) :swap-slot-label)
                                         (when-let [slot (ctk/get-swap-slot m)]
                                           (thi/label slot))))
               (d/without-nils))

        pretty-uuid (fn [id]
                      (let [id (str id)]
                        (str "#" (subs id (- (count id) 6)))))

        format-kv (fn [[k v]]
                    (cond
                      (uuid? v)
                      (str k " " (pretty-uuid v))

                      :else
                      (str k " " v)))]

    (when (seq kv)
      (str " [" (apply str (interpose ", " (map format-kv kv))) "]"))))

(defn- dump-page-shape
  [shape keys padding show-refs?]
  (println (str/pad (str padding
                         (when (and (:main-instance shape) show-refs?) "{")
                         (thi/label (:id shape))
                         (when (and (:main-instance shape) show-refs?) "}")
                         (when (seq keys)
                           (stringify-keys shape keys)))
                    {:length 50 :type :right})
           (if (nil? (:shape-ref shape))
             (if (and (:component-root shape) show-refs?)
               (str "# [Component " (thi/label (:component-id shape)) "]")
               "")
             (if show-refs?
               (str/format "%s--> %s%s"
                           (cond (:component-root shape) "#"
                                 (:component-id shape) "@"
                                 :else "-")
                           (if (:component-root shape)
                             (str "[Component " (thi/label (:component-id shape)) "] ")
                             "")
                           (thi/label (:shape-ref shape)))
               ""))))

(defn dump-page
  "Dump the layer tree of the page, showing labels of the shapes.
    - keys: a list of attributes of the shapes you want to show. In addition, you
            can add :swap-slot to show the slot id (if any) or :swap-slot-label
            to show the corresponding label.
    - show-refs?: if true, the component references will be shown."
  [page & {:keys [keys root-id padding show-refs?]
           :or {keys [:name :swap-slot-label] root-id uuid/zero padding "" show-refs? true}}]
  (let [lookupf (d/getf (:objects page))
        root-shape (lookupf root-id)
        shapes (map lookupf (:shapes root-shape))]
    (doseq [shape shapes]
      (dump-page-shape shape keys padding show-refs?)
      (dump-page page
                 :keys keys
                 :root-id (:id shape)
                 :padding (str padding "    ")
                 :show-refs? show-refs?))))

(defn dump-file
  "Dump the current page of the file, using dump-page above.
   Example: (thf/dump-file file :keys [:name :swap-slot-label] :show-refs? false)"
  [file & {:keys [] :as params}]
  (dump-page (current-page file) params))
