;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS SUBSIDIARY SL

(ns exporter.consumer.plan
  "The render plan of an assets run.

  The items the backend froze in the job become the renders this
  process runs: file names built once and kept free of duplicates, then
  grouped by scale and type, one render per group on the wasm engine
  and one DOM page per partition of fifty on the browser engine.
  `exporter.consumer` runs whatever this namespace plans, and this
  namespace only plans."
  (:require
   [app.common.data :as d]
   [cuerdas.core :as str]
   [exporter.renderer :as renderer]
   [exporter.util.mime :as mime]))

;; Regex to clean namefiles
(def sanitize-file-regex #"[\\/:*?\"<>|]")

(defn count-objects
  [exports]
  (reduce + 0 (map (comp count :objects) exports)))

(defn- assoc-file-name
  "A transducer that assocs a candidate filename and avoid duplicates"
  []
  (letfn [(find-candidate [params used]
            (loop [index 0]
              (let [candidate (str (:name params)
                                   (:suffix params "")
                                   (when (pos? index)
                                     (str/concat "-" (inc index)))
                                   (mime/get-extension (:type params)))]
                (if (contains? used candidate)
                  (recur (inc index))
                  candidate))))]
    (fn [rf]
      (let [used (volatile! #{})]
        (fn
          ([] (rf))
          ([result] (rf result))
          ([result params]
           (let [candidate (find-candidate params @used)
                 params    (assoc params :filename candidate)]
             (vswap! used conj candidate)
             (rf result params))))))))

(def ^:const ^:private
  default-partition-size 50)

(defn prepare-exports
  [exports token is-wasm]
  (letfn [(process-group [[part1 :as group]]
            ;; The browser path renders one DOM page per partition; wasm
            ;; takes the whole group in one render.
            (if (renderer/wasm? {:is-wasm is-wasm :type (:type part1)})
              [(build-render group)]
              (sequence (comp (partition-all default-partition-size)
                              (map build-render))
                        group)))

          (build-render [[part1 :as part]]
            (cond-> {:file-id (:file-id part1)
                     :page-id (:page-id part1)
                     :name    (:name part1)
                     :token   token
                     :type    (:type part1)
                     :scale   (:scale part1)
                     :objects (mapv part-entry->object part)}
              ;; a nil share-id is not an absent one for the render
              ;; spec: only name it when the item came through one
              (some? (:share-id part1)) (assoc :share-id (:share-id part1))))

          (part-entry->object [entry]
            {:id       (:object-id entry)
             :filename (:filename entry)
             :name     (:name entry)
             :suffix   (:suffix entry)})]

    (let [xform (comp
                 (map #(assoc % :token token))
                 (assoc-file-name))]
      (->> (sequence xform exports)
           (d/group-by (juxt :scale :type))
           (map second)
           (into [] (mapcat process-group))))))
