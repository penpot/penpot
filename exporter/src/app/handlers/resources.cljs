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

(def ^:private close-zip-timeout-ms 60000)

(defn create-zip
  "Creates a zip archiver piped to the resource destination stream.
   Returns `[zip out]`, where `zip` is the archiver and `out` is the
   destination write stream (both needed so completion can wait for the
   stream to finish/close, not only for the archiver to end)."
  [& {:keys [resource on-complete on-progress on-error]}]
  (let [^js zip     (new arc/ZipArchive)
        ^js out     (fs/createWriteStream (:path resource))
        on-complete (or on-complete (constantly nil))
        progress    (atom 0)]
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
  "Finalizes `zip` and resolves only after the archiver has completed and
   the destination stream `out` has finished/closed. This avoids reading a
   partially written archive; `finish`/`close` means the stream handed data
   to the OS and closed the fd (not a durable fsync). Rejects on archiver
   or destination errors and on timeout."
  [zip out]
  (p/create
   (fn [resolve reject]
     (let [zipped?  (volatile! false)
           flushed? (volatile! false)
           settled? (volatile! false)
           timer    (volatile! nil)
           settle!  (fn [err]
                      (when-not @settled?
                        (vreset! settled? true)
                        (when-some [t @timer]
                          (js/clearTimeout t))
                        (if err
                          (reject err)
                          (resolve nil))))
           maybe-resolve! #(when (and @zipped? @flushed?)
                             (settle! nil))
           mark-zipped!   #(do (vreset! zipped? true) (maybe-resolve!))
           mark-flushed!  #(do (vreset! flushed? true) (maybe-resolve!))]
       (vreset! timer
                (js/setTimeout
                 #(settle! (ex-info "close-zip timeout waiting for archive completion"
                                    {:type :internal
                                     :code :close-zip-timeout}))
                 close-zip-timeout-ms))

       ;; The archiver `end`/`close` alone does not guarantee the piped
       ;; chunks were written out by the destination stream, so wait for
       ;; both archiver completion and destination `finish`/`close`.
       (.on ^js zip "end" mark-zipped!)
       (.on ^js zip "close" mark-zipped!)
       (.on ^js zip "error" settle!)

       (doto ^js out
         (.on "finish" mark-flushed!)
         (.on "close" mark-flushed!)
         (.on "error" settle!))

       (-> (.finalize ^js zip)
           (p/then (fn [_] (mark-zipped!)))
           (p/catch settle!))))))

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
