;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS SUBSIDIARY SL

(ns app.worker.import
  (:refer-clojure :exclude [resolve])
  (:require
   [app.common.exceptions :as ex]
   [app.common.json :as json]
   [app.common.logging :as log]
   [app.common.schema :as sm]
   [app.common.uuid :as uuid]
   [app.util.http :as http]
   [app.util.i18n :as i18n :refer [tr]]
   [app.util.zip :as uz]
   [app.worker.impl :as impl]
   [beicon.v2.core :as rx]
   [cuerdas.core :as str]))

(log/set-level! :warn)

(defn- import-cause-message
  "Prefer the server `:hint` (full text, e.g. SSE error payload), then `:explain`
   when present; avoid the generic `stream exception` wrapper when a payload exists."
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

;; Upload changes batches size
(def ^:const change-batch-size 100)

(def conjv (fnil conj []))

(defn read-zip-manifest
  [zip-reader]
  (->> (rx/from (uz/get-entry zip-reader "manifest.json"))
       (rx/mapcat (fn [entry]
                    (if (nil? entry)
                      (rx/throw (ex/error :type :validation
                                          :code :invalid-penpot-file
                                          :hint "Not a valid Penpot file: manifest.json is missing"))
                      (uz/read-as-text entry))))
       (rx/map json/decode)))

(defn slurp-uri
  ([uri] (slurp-uri uri :text))
  ([uri response-type]
   (->> (http/send!
         {:uri uri
          :response-type response-type
          :method :get})
        (rx/map :body))))

(defn parse-mtype [ba]
  (let [u8 (js/Uint8Array. ba 0 4)
        sg (areduce u8 i ret "" (str ret (if (zero? i) "" " ") (.toString (aget u8 i) 8)))]
    (case sg
      "120 113 3 4" "application/zip"
      "1 13 32 206" "application/octet-stream"
      "other")))

;; NOTE: this is a limited subset schema for the manifest file of
;; binfile-v3 format; is used for partially parse it and read the
;; files referenced inside the exported file

(def ^:private schema:manifest
  [:map {:title "Manifest"}
   [:type :string]
   [:files
    [:vector
     [:map
      [:id ::sm/uuid]
      [:name :string]]]]])

(def ^:private decode-manifest
  (sm/decoder schema:manifest sm/json-transformer))

(defn analyze-file
  [{:keys [uri] :as file}]
  (let [stream (->> (slurp-uri uri :buffer)
                    (rx/merge-map
                     (fn [body]
                       (let [mtype (parse-mtype body)]
                         (cond
                           (= "application/zip" mtype)
                           (let [zip-reader (uz/reader body)]
                             (->> (read-zip-manifest zip-reader)
                                  (rx/map
                                   (fn [manifest]
                                     (if (= (:type manifest) "penpot/export-files")
                                       (let [manifest (decode-manifest manifest)]
                                         (assoc file :type :binfile-v3 :files (:files manifest)))
                                       (assoc file :type :unknown))))
                                  (rx/finalize (partial uz/close zip-reader))))

                           (= "application/octet-stream" mtype)
                           (rx/of (assoc file :type :binfile-v1))

                           :else
                           (rx/of (assoc file :type :unknown))))))

                    (rx/share))]

    (->> (rx/merge
          (->> stream
               (rx/filter (fn [entry] (= :binfile-v1 (:type entry))))
               (rx/map (fn [entry]
                         (let [file-id (uuid/next)]
                           (-> entry
                               (assoc :file-id file-id)
                               (assoc :name (:name file))
                               (assoc :status :success))))))

          (->> stream
               (rx/filter (fn [entry] (= :binfile-v3 (:type entry))))
               (rx/merge-map (fn [{:keys [files] :as entry}]
                               (->> (rx/from files)
                                    (rx/map (fn [file]
                                              (-> entry
                                                  (dissoc :files)
                                                  (assoc :name (:name file))
                                                  (assoc :file-id (:id file))
                                                  (assoc :status :success))))))))

          (->> stream
               (rx/filter (fn [data] (= :unknown (:type data))))
               (rx/map (fn [_]
                         {:uri (:uri file)
                          :status :error
                          :error (tr "dashboard.import.analyze-error")}))))

         (rx/catch (fn [cause]
                     (let [error (import-cause-message cause (tr "dashboard.import.analyze-error"))]
                       (rx/of (assoc file :error error :status :error))))))))

(defmethod impl/handler :analyze-import
  [{:keys [files]}]
  (->> (rx/from files)
       (rx/merge-map analyze-file)))
