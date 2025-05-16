;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC

(ns app.worker.export
  (:require
   [app.common.exceptions :as ex]
   [app.main.repo :as rp]
   [app.util.webapi :as wapi]
   [app.worker.impl :as impl]
   [beicon.v2.core :as rx]))

(defmethod impl/handler :export-files
  [{:keys [files type format] :as message}]
  (assert (or (= format :binfile-v1)
              (= format :binfile-v3))
          "expected valid format")

  (->> (rx/from files)
       (rx/mapcat
        (fn [file]
          (->> (rp/cmd! :export-binfile {:file-id (:id file)
                                         :version (if (= format :binfile-v3) 3 1)
                                         :include-libraries (= type :all)
                                         :embed-assets (= type :merge)})
               (rx/map wapi/create-blob)
               (rx/map wapi/create-uri)
               (rx/map (fn [uri]
                         {:type :finish
                          :file-id (:id file)
                          :filename (:name file)
                          :mtype (if (= format :binfile-v3)
                                   "application/zip"
                                   "application/penpot")
                          :uri uri}))
               (rx/catch
                (fn [cause]
                  (rx/of (ex/raise :type :internal
                                   :code :export-error
                                   :hint "unexpected error on exporting file"
                                   :file-id (:id file)
                                   :cause cause)))))))))
