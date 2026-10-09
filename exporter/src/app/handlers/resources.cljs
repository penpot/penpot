;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS SUBSIDIARY SL

(ns app.handlers.resources
  "The run's local resources: the artifact files the runner parks in
  the temp area until the settle moves them into the job's completion,
  zipped once a run packs more than one."
  (:require
   ["archiver" :as arc]
   ["node:fs" :as fs]
   ["node:path" :as path]
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
  (let [task-id (uuid/next)
        path    (-> (get-path type task-id)
                    (sh/schedule-deletion))]
    {:path     path
     :mtype    (mime/get type)
     :name     name
     :filename (str/concat (str/replace name #"[\\/:*?\"<>|]" "_") (mime/get-extension type))
     :id       task-id}))

(defn create-zip
  [& {:keys [resource on-complete on-progress on-error]}]
  (let [^js zip  (new arc/ZipArchive)
        ^js out  (fs/createWriteStream (:path resource))
        on-complete (or on-complete (constantly nil))
        progress (atom 0)]
    (.on zip "error" on-error)
    (.on zip "end" on-complete)
    (.on zip "entry" (fn [data]
                       (let [name (unchecked-get data "name")
                             num  (swap! progress inc)]
                         (on-progress {:done num :filename name}))))
    (.pipe zip out)
    zip))

(defn add-to-zip
  [zip path name]
  (.file ^js zip path #js {:name name}))

(defn close-zip
  [zip]
  (p/create (fn [resolve]
              (.on ^js zip "close" resolve)
              (.finalize ^js zip))))
