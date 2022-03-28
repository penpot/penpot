;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) UXBOX Labs SL

(ns app.render
  "The main entry point for UI part needed by the exporter."
  (:require
   [app.common.logging :as log]
   [app.common.spec :as us]
   [app.common.uri :as u]
   [app.config :as cf]
   [app.main.ui.render :as render]
   [app.util.dom :as dom]
   [app.util.globals :as glob]
   [clojure.spec.alpha :as s]
   [rumext.alpha :as mf]))

(log/initialize!)
(log/set-level! :root :warn)
(log/set-level! :app :info)

(declare reinit)

(declare ^:private render-object)

(log/info :hint "Welcome to penpot (Export)"
          :version (:full @cf/version)
          :public-uri (str cf/public-uri))

(defn- parse-params
  [loc]
  (let [href (unchecked-get loc "href")]
    (some-> href u/uri :query u/query-string->map)))

(defn init-ui
  []
  (when-let [params (parse-params glob/location)]
    (when-let [component (case (:route params)
                           "render-object" (render-object params)
                           nil)]
      (mf/mount component (dom/get-element "app")))))

(defn ^:export init
  []
  (init-ui))

(defn reinit
  []
  (mf/unmount (dom/get-element "app"))
  (init-ui))

(defn ^:dev/after-load after-load
  []
  (reinit))

(s/def ::page-id ::us/uuid)
(s/def ::file-id ::us/uuid)
(s/def ::object-id ::us/uuid)
(s/def ::render-text ::us/boolean)
(s/def ::embed ::us/boolean)

(s/def ::render-object-params
  (s/keys :req-un [::file-id ::page-id ::object-id]
          :opt-un [::render-text ::embed]))

(defn- render-object
  [params]
  (let [{:keys [page-id file-id object-id render-texts embed]} (us/conform ::render-object-params params)]
    (mf/html
     [:& render/render-object
      {:file-id file-id
       :page-id page-id
       :object-id object-id
       :embed? embed
       :render-texts? render-texts}])))
