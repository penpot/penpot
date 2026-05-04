;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the Mozilla Public License was not distributed with this
;; file, You can obtain one at https://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC

(ns app.plugins.orchestration
  "Programmatic workflows for MCP and other plugins (import .penpot, open another file)."
  (:require
   [app.common.data.macros :as dm]
   [app.common.uuid :as uuid]
   [app.main.data.common :as dcm]
   [app.main.store :as st]
   [app.main.worker :as mw]
   [app.plugins.format :as format]
   [app.plugins.register :as r]
   [app.plugins.utils :as u]
   [app.util.webapi :as wapi]
   [beicon.v2.core :as rx]))

(defn- uint8array?
  [x]
  (instance? js/Uint8Array x))

(defn- js-data->uint8-array
  "Coerces plugin sandbox input into a Uint8Array."
  [data]
  (cond
    (uint8array? data) data
    (instance? js/ArrayBuffer data) (js/Uint8Array. data)

    (and ^boolean (array? data) (every? number? data))
    (js/Uint8Array. (into-array data))

    :else
    nil))

(defn navigate-to-file!
  "Opens another team file (and optionally a page) in the workspace router."
  [plugin-id file-id-str page-id-str]
  (if (not (string? file-id-str))
    (u/not-valid plugin-id :openFile "file-id must be a string UUID")

    (let [fid (uuid/parse* file-id-str)
          pid (when page-id-str
                (uuid/parse* page-id-str))]
      (cond
        (nil? fid)
        (u/not-valid plugin-id :openFile "Invalid file id (expected UUID string)")

        (and ^boolean (some? page-id-str) (nil? pid))
        (u/not-valid plugin-id :openFile "Invalid page id (expected UUID string)")

        :else
        (let [evt (if (uuid? pid)
                    (dcm/go-to-workspace :file-id fid :page-id pid)
                    (dcm/go-to-workspace :file-id fid))]
          (st/emit! evt))))))

(defn import-penpot-file!
  "Imports a `.penpot` blob into the current project (same mechanism as Dashboard import)."
  [plugin-id filename data]
  (js/Promise.
   (fn [resolve reject]
     (let [reject-msg #(reject (js/Error %))
           uint8-data (js-data->uint8-array data)]
       (cond
         (not (r/check-permission plugin-id "content:write"))
         (reject-msg "Plugin needs content:write permission")

         (or (not (string? filename)) (empty? (dm/str filename)))
         (reject-msg "Invalid file name")

         (nil? uint8-data)
         (reject-msg "Data must be a Uint8Array (or compatible buffer)")

         :else
         (try
           (let [project-id (:current-project-id @st/state)
                 features (:features @st/state)
                 blob (js/Blob. #js [uint8-data] #js {:type "application/octet-stream"})
                 uri (wapi/create-uri blob)]
             (if (nil? project-id)
               (do
                 (wapi/revoke-uri uri)
                 (reject-msg "No active project — open any file from a project first"))

               (->> (mw/ask-many!
                     {:cmd :analyze-import
                      :features features
                      :files [{:name filename :uri uri}]})
                    (rx/reduce conj [])
                    (rx/subs!
                     (fn [responses]
                       (try
                         (let [errs (filter #(= :error (:status %)) responses)
                               oks (filter #(= :success (:status %)) responses)]
                           (cond
                             (seq errs)
                             (do
                               (wapi/revoke-uri uri)
                               (reject-msg (dm/str (or (-> errs first :error) "analyze error"))))

                             (empty? oks)
                             (do
                               (wapi/revoke-uri uri)
                               (reject-msg "Could not analyze .penpot data"))

                             :else
                             (->> (mw/ask-many!
                                   {:cmd :import-files
                                    :project-id project-id
                                    :files oks})
                                  (rx/reduce conj [])
                                  (rx/subs!
                                   (fn [rows]
                                     (try
                                       (wapi/revoke-uri uri)
                                       (catch :default _))
                                     (let [ierrs (filter #(= :error (:status %)) rows)
                                           ok (filter #(= :finish (:status %)) rows)]
                                       (cond
                                         (seq ierrs)
                                         (reject-msg (dm/str (or (-> ierrs first :error) "import error")))

                                         (empty? ok)
                                         (reject-msg "Import produced no finished file")

                                         :else
                                         (resolve
                                          #js {:fileIds (->> ok
                                                               (mapv #(format/format-id (:file-id %)))
                                                               (into-array))}))))
                                   (fn [err]
                                     (try
                                       (wapi/revoke-uri uri)
                                       (catch :default _))
                                     (reject err))))))
                         (catch :default e
                           (try
                             (wapi/revoke-uri uri)
                             (catch :default _))
                           (reject (or e (js/Error "import analyze failed"))))))
                     (fn [err]
                       (try
                         (wapi/revoke-uri uri)
                         (catch :default _))
                       (reject err))))))
           (catch :default cause
             (reject (or cause (js/Error "import failed"))))))))))
