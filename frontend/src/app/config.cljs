;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; This Source Code Form is "Incompatible With Secondary Licenses", as
;; defined by the Mozilla Public License, v. 2.0.
;;
;; Copyright (c) 2020 UXBOX Labs SL

(ns app.config
  (:require
   [clojure.spec.alpha :as s]
   [app.common.data :as d]
   [app.common.spec :as us]
   [app.common.version :as v]
   [app.util.object :as obj]
   [app.util.dom :as dom]
   [cuerdas.core :as str]))

;; --- Auxiliar Functions

(s/def ::platform #{:windows :linux :macos :other})
(s/def ::browser #{:chrome :mozilla :safari :edge :other})

(defn- parse-browser
  []
  (let [user-agent (-> (dom/get-user-agent) str/lower)
        check-chrome? (fn [] (str/includes? user-agent "chrom"))
        check-firefox? (fn [] (str/includes? user-agent "firefox"))
        check-edge? (fn [] (str/includes? user-agent "edg"))
        check-safari? (fn [] (str/includes? user-agent "safari"))]
    (cond
      (check-edge?)    :edge
      (check-chrome?)  :chrome
      (check-firefox?) :firefox
      (check-safari?)  :safari
      :else            :other)))

(defn- parse-platform
  []
  (let [user-agent (-> (dom/get-user-agent) str/lower)
        check-windows? (fn [] (str/includes? user-agent "windows"))
        check-linux? (fn [] (str/includes? user-agent "linux"))
        check-macos? (fn [] (str/includes? user-agent "mac os"))]
    (cond
      (check-windows?) :windows
      (check-linux?)   :linux
      (check-macos?)   :macos
      :else            :other)))

;; --- Globar Config Vars

(def default-theme  "default")

(this-as global
  (def default-language "en")
  (def demo-warning     (obj/get global "appDemoWarning" false))
  (def google-client-id (obj/get global "appGoogleClientID" nil))
  (def gitlab-client-id (obj/get global "appGitlabClientID" nil))
  (def login-with-ldap  (obj/get global "appLoginWithLDAP" false))
  (def worker-uri       (obj/get global "appWorkerURI" "/js/worker.js"))
  (def public-uri       (or (obj/get global "appPublicURI")
                            (.-origin ^js js/location)))
  (def version          (v/parse (obj/get global "appVersion"))))


(def media-uri (str public-uri "/media"))
(def browser   (parse-browser))
(def platform  (parse-platform))

(js/console.log
 (str/format "Welcome to penpot! Version: '%s'" (:full version)))

;; --- Helper Functions


(defn ^boolean check-browser? [candidate]
  (us/verify ::browser candidate)
  (= candidate browser))

(defn ^boolean check-platform? [candidate]
  (us/verify ::platform candidate)
  (= candidate platform))

(defn resolve-media-path
  [path]
  (when path
    (if (str/starts-with? path "data:")
      path
      (str media-uri "/" path))))
