;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS SUBSIDIARY SL

(ns app.handlers.resources
  "Temporal resources management."
  (:require
   ["archiver" :as arc]
   ["node:fs" :as fs]
   ["node:fs/promises" :as fsp]
   ["node:path" :as path]
   ["undici" :as http]
   [app.common.exceptions :as ex]
   [app.common.transit :as t]
   [app.common.uri :as u]
   [app.common.uuid :as uuid]
   [app.config :as cf]
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
    (.on out "error" on-error)
    (.pipe zip out)
    [zip out]))

(defn add-to-zip
  [zip path name]
  (.file ^js zip path #js {:name name}))

(defn close-zip
  [zip out]
  (p/create (fn [resolve reject]
              (let [zipped  (atom false)
                    flushed (atom false)
                    done    (atom false)
                    settle  (fn [err]
                              (when-not @done
                                (reset! done true)
                                (if err
                                  (reject err)
                                  (resolve nil))))
                    check   #(when (and @zipped @flushed) (settle nil))
                    note-z  #(do (reset! zipped true) (check))
                    note-o  #(do (reset! flushed true) (check))]
                ;; Wait for the archiver to be done AND for the destination
                ;; file stream to be flushed. The archiver's `close` event
                ;; does not guarantee the last chunks received by the pipe
                ;; have been written to disk, so resolving on it alone used
                ;; to race with `readFile` in `upload-resource`, yielding
                ;; truncated (unusable) zip archives.
                (.on ^js zip "end" note-z)
                (.on ^js zip "close" note-z)
                (if (some? out)
                  (doto ^js out
                    (.on "finish" note-o)
                    (.on "close" note-o)
                    (.on "error" settle))
                  (reset! flushed true))
                (.then (.finalize ^js zip) note-z settle)))))

(defn upload-resource
  [auth-token resource]
  (->> (fsp/readFile (:path resource))
       (p/fmap (fn [buffer]
                 (new js/Blob #js [buffer] #js {:type (:mtype resource)})))
       (p/mcat (fn [blob]
                 (let [fdata  (new http/FormData)
                       agent  (new http/Agent #js {:connect #js {:rejectUnauthorized false}})
                       headers #js {"X-Shared-Key" (str "exporter " cf/management-key)
                                    "Authorization" (str "Bearer " auth-token)}

                       request #js {:headers headers
                                    :method "POST"
                                    :body fdata
                                    :dispatcher agent}
                       uri     (-> (cf/get-internal-uri)
                                   (u/ensure-path-slash)
                                   (u/join "api/management/methods/upload-tempfile")
                                   (str))]

                   (.append fdata "content" blob (:filename resource))
                   (http/fetch uri request))))

       (p/mcat (fn [response]
                 (if (not= (.-status response) 200)
                   (ex/raise :type :internal
                             :code :unable-to-upload-resource
                             :response-status (.-status response))
                   (.text response))))
       (p/fmap t/decode-str)
       (p/fmap (fn [result]
                 (merge resource (dissoc result :id))))))
