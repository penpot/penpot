;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS SUBSIDIARY SL

(ns app.main.data.imports
  "Upload and import-binfile on the main thread from the dropped File."
  (:require
   [app.common.logging :as log]
   [app.main.data.uploads :as uploads]
   [app.main.repo :as rp]
   [app.util.i18n :as i18n :refer [tr]]
   [app.util.sse :as sse]
   [beicon.v2.core :as rx]
   [cuerdas.core :as str]))

(log/set-level! :warn)

(defn- import-cause-message
  [cause default-msg]
  (let [data    (ex-data cause)
        hint    (some-> data :hint str/trim)
        explain (some-> data :explain str/trim)]
    (cond
      (not (str/blank? hint)) hint
      (not (str/blank? explain)) explain
      :else
      (let [msg (some-> (ex-message cause) str/trim)]
        (if (or (str/blank? msg) (= msg "stream exception"))
          default-msg
          msg)))))

(defn- import-blob
  [blob {:keys [name version project-id]}]
  (->> (uploads/upload-blob-chunked blob)
       (rx/mapcat
        (fn [{:keys [session-id]}]
          (rp/cmd! ::sse/import-binfile
                   {:name       name
                    :upload-id  session-id
                    :version    version
                    :project-id project-id})))
       (rx/tap (fn [event]
                 (let [payload (sse/get-payload event)
                       type    (sse/get-type event)]
                   (if (= type "progress")
                     (log/dbg :hint "import-binfile: progress"
                              :section (:section payload)
                              :name (:name payload))
                     (log/dbg :hint "import-binfile: end")))))
       (rx/filter sse/end-of-stream?)))

(defn- entry-blob
  [entry]
  (or (:blob entry)
      (throw (ex-info "import entry is missing the dropped file"
                      {:type :internal
                       :code :missing-import-blob
                       :uri (:uri entry)
                       :name (:name entry)}))))

(defn import-files
  "Upload and import analyzed entries. Emits per-file `:finish`/`:error`,
  then `{:libraries-resolution …}`."
  [{:keys [project-id files]}]
  (let [binfile-v1  (filter #(= :binfile-v1 (:type %)) files)
        binfile-v3  (filter #(= :binfile-v3 (:type %)) files)
        resolutions (volatile! {})]

    (rx/merge
     (->> (rx/from binfile-v1)
          (rx/merge-map
           (fn [data]
             (->> (import-blob (entry-blob data)
                               {:name       (str/replace (:name data) #".penpot$" "")
                                :version    1
                                :project-id project-id})
                  (rx/map (fn [_]
                            {:status :finish
                             :file-id (:file-id data)}))
                  (rx/catch
                   (fn [cause]
                     (log/error :hint "unexpected error on import process"
                                :project-id project-id
                                :cause cause)
                     (rx/of {:status :error
                             :error (import-cause-message cause (tr "labels.error"))
                             :file-id (:file-id data)})))))))

     (rx/concat
      (->> (rx/from binfile-v3)
           (rx/reduce (fn [result file]
                        (update result (:uri file) (fnil conj []) file))
                      {})
           (rx/mapcat identity)
           (rx/merge-map
            (fn [[_uri entries]]
              (->> (import-blob (entry-blob (first entries))
                                {:name       (-> entries first :name)
                                 :version    3
                                 :project-id project-id})
                   (rx/mapcat (fn [message]
                                (let [{:keys [resolution]} (sse/get-payload message)]
                                  (when (seq resolution)
                                    (vswap! resolutions merge resolution))
                                  (->> (rx/from entries)
                                       (rx/map (fn [entry]
                                                 {:status :finish
                                                  :file-id (:file-id entry)}))))))
                   (rx/catch (fn [cause]
                               (log/error :hint "import-binfile: unexpected error on importing"
                                          :project-id project-id
                                          ::log/sync? true
                                          :cause cause)
                               (let [err (import-cause-message cause (tr "labels.error"))]
                                 (->> (rx/from entries)
                                      (rx/map (fn [entry]
                                                {:status :error
                                                 :error err
                                                 :file-id (:file-id entry)}))))))))))
      (->> (rx/defer #(rx/of @resolutions))
           (rx/map (fn [resolutions]
                     {:libraries-resolution resolutions})))))))
