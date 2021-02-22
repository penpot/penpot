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
   [app.util.globals :refer [global location]]
   [app.util.object :as obj]
   [app.util.dom :as dom]
   [app.util.avatars :as avatars]
   [cuerdas.core :as str]))

;; --- Auxiliar Functions

(s/def ::platform #{:windows :linux :macos :other})
(s/def ::browser #{:chrome :firefox :safari :edge :other})

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
  (let [user-agent     (str/lower (dom/get-user-agent))
        check-windows? (fn [] (str/includes? user-agent "windows"))
        check-linux?   (fn [] (str/includes? user-agent "linux"))
        check-macos?   (fn [] (str/includes? user-agent "mac os"))]
    (cond
      (check-windows?) :windows
      (check-linux?)   :linux
      (check-macos?)   :macos
      :else            :other)))

(defn- parse-target
  [global]
  (if (some? (obj/get global "document"))
    :browser
    :webworker))

(defn- parse-version
  [global]
  (-> (obj/get global "penpotVersion")
      (v/parse)))

;; --- Globar Config Vars

(def default-theme  "default")
(def default-language "en")

(def demo-warning         (obj/get global "penpotDemoWarning" false))
(def feedback-enabled     (obj/get global "penpotFeedbackEnabled" false))
(def allow-demo-users     (obj/get global "penpotAllowDemoUsers" true))
(def google-client-id     (obj/get global "penpotGoogleClientID" nil))
(def gitlab-client-id     (obj/get global "penpotGitlabClientID" nil))
(def github-client-id     (obj/get global "penpotGithubClientID" nil))
(def login-with-ldap      (obj/get global "penpotLoginWithLDAP" false))
(def registration-enabled (obj/get global "penpotRegistrationEnabled" true))
(def worker-uri           (obj/get global "penpotWorkerURI" "/js/worker.js"))
(def translations         (obj/get global "penpotTranslations"))
(def themes               (obj/get global "penpotThemes"))

(def public-uri           (or (obj/get global "penpotPublicURI") (.-origin ^js location)))

(def version              (delay (parse-version global)))
(def target               (delay (parse-target global)))
(def browser              (delay (parse-browser)))
(def platform             (delay (parse-platform)))

(when (= :browser @target)
  (js/console.log
   (str/format "Welcome to penpot! Version: '%s'." (:full @version))))

;; --- Helper Functions

(defn ^boolean check-browser? [candidate]
  (us/verify ::browser candidate)
  (= candidate @browser))

(defn ^boolean check-platform? [candidate]
  (us/verify ::platform candidate)
  (= candidate @platform))

(defn resolve-profile-photo-url
  [{:keys [photo-id fullname name] :as profile}]
  (if (nil? photo-id)
    (avatars/generate {:name (or fullname name)})
    (str public-uri "/assets/by-id/" photo-id)))

(defn resolve-team-photo-url
  [{:keys [photo-id name] :as team}]
  (if (nil? photo-id)
    (avatars/generate {:name name})
    (str public-uri "/assets/by-id/" photo-id)))

(defn resolve-file-media
  ([media]
   (resolve-file-media media false))
  ([{:keys [id] :as media} thumnail?]
   (str public-uri "/assets/by-file-media-id/" id (when thumnail? "/thumbnail"))))


