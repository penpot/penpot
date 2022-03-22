;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) UXBOX Labs SL

(ns app.handlers.resources
  "Temporal resouces management."
  (:require
   ["archiver" :as arc]
   ["fs" :as fs]
   ["os" :as os]
   ["path" :as path]
   [app.common.data :as d]
   [app.common.data.macros :as dm]
   [app.common.exceptions :as ex]
   [app.common.uuid :as uuid]
   [app.util.shell :as sh]
   [cljs.core :as c]
   [cuerdas.core :as str]
   [promesa.core :as p]))

(defn- get-path
  [type id]
  (path/join (os/tmpdir) (dm/str  "exporter." (d/name type) "." id)))

(defn- get-mtype
  [type]

  (case (d/name type)
    "zip"  "application/zip"
    "pdf"  "application/pdf"
    "svg"  "image/svg+xml"
    "jpeg" "image/jpeg"
    "png"  "image/png"))

(defn create
  "Generates ephimeral resource object."
  [type name]
  (let [task-id (uuid/next)]
    {:path (get-path type task-id)
     :mtype (get-mtype type)
     :name name
     :id (dm/str (c/name type) "." task-id)}))

(defn- write-as-zip!
  [{:keys [id path]} items on-progress]
  (let [^js zip  (arc/create "zip")
        ^js out  (fs/createWriteStream path)
        append!  (fn [{:keys [data name] :as result}]
                   (.append zip data #js {:name name}))
        progress (atom 0)]
    (p/create
     (fn [resolve reject]
       (.on zip "error" #(reject %))
       (.on zip "end" resolve)
       (.on zip "entry" (fn [data]
                          (let [name (unchecked-get data "name")
                                num  (swap! progress inc)]
                            ;; Sample code used for testing failing exports
                            #_(when (= 2 num)
                                (.abort ^js zip)
                                (reject (js/Error. "unable to create zip file")))
                            (on-progress
                             {:total (count items)
                              :done num}))))
       (.pipe zip out)
       (-> (reduce (fn [res export-fn]
                     (p/then res (fn [_] (-> (export-fn) (p/then append!)))))
                   (p/resolved 1)
                   items)
           (p/then #(.finalize zip))
           (p/catch reject))))))

(defn create-simple
  [& {:keys [task resource on-progress on-complete on-error]
      :or {on-progress identity
           on-complete identity
           on-error identity}
      :as params}]
  (let [path (:path resource)]
    (-> (task)
        (p/then (fn [{:keys [data name]}]
                  (on-progress {:total 1 :done 1 :name name})
                  (.writeFile fs/promises path data)))
        (p/then #(sh/stat path))
        (p/then #(merge resource %))
        (p/finally (fn [result cause]
                     (if cause
                       (on-error cause)
                       (on-complete result)))))))

(defn create-zip
  "Creates a resource with multiple files merget into a single zip file."
  [& {:keys [resource tasks on-error on-progress on-complete]
      :or {on-error identity
           on-progress identity
           on-complete identity}}]
  (let [{:keys [path id] :as resource} resource]
    (-> (write-as-zip! resource tasks on-progress)
        (p/then #(sh/stat path))
        (p/then #(merge resource %))
        (p/finally (fn [result cause]
                     (if cause
                       (on-error cause)
                       (on-complete result)))))))

(defn- lookup
  [id]
  (p/let [[type task-id] (str/split id "." 2)
          path  (get-path type task-id)
          mtype (get-mtype type)
          stat  (sh/stat path)]

    (when-not stat
      (ex/raise :type :not-found))

    {:stream (fs/createReadStream path)
     :headers {"content-type" mtype
               "content-length" (:size stat)}}))

(defn handler
  [{:keys [:request/params response] :as exchange}]
  (when-not (contains? params :id)
    (ex/raise :type :validation
              :code :missing-id))

  (-> (lookup (get params :id))
      (p/then (fn [{:keys [stream headers] :as resource}]
                (-> exchange
                    (assoc :response/status 200)
                    (assoc :response/body stream)
                    (assoc :response/headers headers))))))
