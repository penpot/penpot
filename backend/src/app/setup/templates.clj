;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC

(ns app.setup.templates
  "A service/module that is responsible for download, load & internally
  expose a set of builtin penpot file templates."
  (:require
   [app.common.data :as d]
   [app.common.data.macros :as dm]
   [app.common.logging :as l]
   [app.common.schema :as sm]
   [app.http.client :as http]
   [app.setup :as-alias setup]
   [clojure.edn :as edn]
   [clojure.java.io :as io]
   [datoteka.fs :as fs]
   [integrant.core :as ig]))

(def ^:private
  schema:template
  (sm/define
    [:map {:title "Template"}
     [:id ::sm/word-string]
     [:name ::sm/word-string]
     [:file-uri ::sm/word-string]]))

(def ^:private
  schema:templates
  (sm/define
    [:vector schema:template]))

(defmethod ig/init-key ::setup/templates
  [_ _]
  (let [templates (-> "app/onboarding.edn" io/resource slurp edn/read-string)
        dest      (fs/join fs/*cwd* "builtin-templates")]

    (dm/verify!
     "expected a valid templates file"
     (sm/check! schema:templates templates))

    (doseq [{:keys [id path] :as template} templates]
      (let [path (or path (fs/join dest id))]
        (if (fs/exists? path)
          (l/dbg :hint "template file" :id id :state "present" :path (dm/str path))
          (l/dbg :hint "template file" :id id :state "absent"))))

    templates))

(defn get-template-stream
  [cfg template-id]
  (when-let [template (d/seek #(= (:id %) template-id)
                              (::setup/templates cfg))]
    (let [dest (fs/join fs/*cwd* "builtin-templates")
          path (or (:path template) (fs/join dest template-id))]
      (if (fs/exists? path)
        (io/input-stream path)
        (let [resp (http/req! cfg
                              {:method :get :uri (:file-uri template)}
                              {:response-type :input-stream :sync? true})]

          (dm/verify!
           "unexpected response found on fetching template"
           (= 200 (:status resp)))

          (io/input-stream (:body resp)))))))
