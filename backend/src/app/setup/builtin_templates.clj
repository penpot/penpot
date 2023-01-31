;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC

(ns app.setup.builtin-templates
  "A service/module that is responsible for download, load & internally
  expose a set of builtin penpot file templates."
  (:require
   [app.common.logging :as l]
   [app.common.spec :as us]
   [app.http.client :as http]
   [clojure.edn :as edn]
   [clojure.java.io :as io]
   [clojure.spec.alpha :as s]
   [datoteka.fs :as fs]
   [integrant.core :as ig]))

(declare download-all!)

(s/def ::id ::us/not-empty-string)
(s/def ::name ::us/not-empty-string)
(s/def ::thumbnail-uri ::us/not-empty-string)
(s/def ::file-uri ::us/not-empty-string)
(s/def ::path fs/path?)

(s/def ::template
  (s/keys :req-un [::id ::name ::thumbnail-uri ::file-uri]
          :opt-un [::path]))

(defmethod ig/pre-init-spec :app.setup/builtin-templates [_]
  (s/keys :req [::http/client]))

(defmethod ig/init-key :app.setup/builtin-templates
  [_ cfg]
  (let [presets (-> "app/onboarding.edn" io/resource slurp edn/read-string)]
    (l/info :hint "loading template files" :total (count presets))
    (let [result (download-all! cfg presets)]
      (us/conform (s/coll-of ::template) result))))

(defn- download-preset!
  [cfg {:keys [path file-uri] :as preset}]
  (let [response (http/req! cfg
                            {:method :get
                             :uri file-uri}
                            {:response-type :input-stream
                             :sync? true})]
    (us/verify! (= 200 (:status response)) "unexpected response found on fetching preset")
    (with-open [output (io/output-stream path)]
      (with-open [input (io/input-stream (:body response))]
        (io/copy input output)))))

(defn- download-all!
  "Download presets to the default directory, if preset is already
  downloaded, no action will be performed."
  [cfg presets]
  (let [dest (fs/join fs/*cwd* "builtin-templates")]
    (when-not (fs/exists? dest)
      (fs/create-dir dest))

    (doall
     (map (fn [item]
            (let [path (fs/join dest (:id item))
                  item (assoc item :path path)]
              (if (fs/exists? path)
                (l/trace :hint "template file already present" :id (:id item))
                (do
                  (l/trace :hint "downloading template file" :id (:id item) :dest (str path))
                  (download-preset! cfg item)))
              item))
          presets))))
