;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC

(ns app.handlers.resources
  "Temporal resources management."
  (:require
   ["archiver$default" :as arc]
   ["node:fs" :as fs]
   ["node:path" :as path]
   [app.common.exceptions :as ex]
   [app.common.uuid :as uuid]
   [app.util.mime :as mime]
   [app.util.shell :as sh]
   [cljs.core :as c]
   [cuerdas.core :as str]
   [promesa.core :as p]))

(defn- get-path
  [type id]
  (path/join sh/tmpdir (str/concat  "penpot.resource." (c/name type) "." id)))

(defn create
  "Generates ephimeral resource object."
  [type name]
  (let [task-id (uuid/next)]
    {:path     (get-path type task-id)
     :mtype    (mime/get type)
     :name     name
     :filename (str/concat name (mime/get-extension type))
     :id       (str/concat (c/name type) "." task-id)}))

(defn- lookup
  [id]
  (p/let [[type task-id] (str/split id "." 2)
          path  (get-path type task-id)
          mtype (mime/get (keyword type))
          stat  (sh/stat path)]

    (when-not stat
      (ex/raise :type :not-found))

    {:stream (fs/createReadStream path)
     :headers {"content-type" mtype
               "content-length" (:size stat)}}))

(defn handler
  [{:keys [:request/params] :as exchange}]
  (when-not (contains? params :id)
    (ex/raise :type :validation
              :code :missing-id))

  (-> (lookup (get params :id))
      (p/then (fn [{:keys [stream headers] :as resource}]
                (-> exchange
                    (assoc :response/status 200)
                    (assoc :response/body stream)
                    (assoc :response/headers headers))))))

(defn create-zip
  [& {:keys [resource on-complete on-progress on-error]}]
  (let [^js zip  (arc/create "zip")
        ^js out  (fs/createWriteStream (:path resource))
        progress (atom 0)]
    (.on zip "error" on-error)
    (.on zip "end" on-complete)
    (.on zip "entry" (fn [data]
                       (let [name (unchecked-get data "name")
                             num  (swap! progress inc)]
                         (on-progress {:done num :filename name}))))
    (.pipe zip out)
    zip))

(defn add-to-zip!
  [zip path name]
  (.file ^js zip path #js {:name name}))

(defn close-zip!
  [zip]
  (.finalize ^js zip))
