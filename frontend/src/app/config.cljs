;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) UXBOX Labs SL

(ns app.config
  (:require
   [app.common.flags :as flags]
   [app.common.spec :as us]
   [app.common.uri :as u]
   [app.common.version :as v]
   [app.util.avatars :as avatars]
   [app.util.dom :as dom]
   [app.util.globals :refer [global location]]
   [app.util.object :as obj]
   [clojure.spec.alpha :as s]
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

(defn- parse-flags
  [global]
  (let [flags (obj/get global "penpotFlags" "")
        flags (sequence (map keyword) (str/words flags))]
    (flags/parse flags/default flags)))

(defn- parse-version
  [global]
  (-> (obj/get global "penpotVersion")
      (v/parse)))

;; --- Globar Config Vars

(def default-theme  "default")
(def default-language "en")

(def google-client-id     (obj/get global "penpotGoogleClientID" nil))
(def gitlab-client-id     (obj/get global "penpotGitlabClientID" nil))
(def github-client-id     (obj/get global "penpotGithubClientID" nil))
(def oidc-client-id       (obj/get global "penpotOIDCClientID" nil))
(def worker-uri           (obj/get global "penpotWorkerURI" "/js/worker.js"))
(def translations         (obj/get global "penpotTranslations"))
(def themes               (obj/get global "penpotThemes"))
(def sentry-dsn           (obj/get global "penpotSentryDsn"))
(def onboarding-form-id   (obj/get global "penpotOnboardingQuestionsFormId"))

(def flags                (atom (parse-flags global)))
(def version              (atom (parse-version global)))
(def target               (atom (parse-target global)))
(def browser              (atom (parse-browser)))
(def platform             (atom (parse-platform)))

;; maintain for backward compatibility
(let [login-with-ldap (obj/get global "penpotLoginWithLDAP" false)
      registration    (obj/get global "penpotRegistrationEnabled" true)]
  (when login-with-ldap
    (swap! flags conj :login-with-ldap))
  (when (false? registration)
    (swap! flags disj :registration)))

(defn get-public-uri
  []
  (let [uri (u/uri (or (obj/get global "penpotPublicURI")
                       (.-origin ^js location)))]
    ;; Ensure that the path always ends with "/"; this ensures that
    ;; all path join operations works as expected.
    (cond-> uri
      (not (str/ends-with? (:path uri) "/"))
      (update :path #(str % "/")))))

(def public-uri (get-public-uri))

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
    (str (u/join public-uri "assets/by-id/" photo-id))))

(defn resolve-team-photo-url
  [{:keys [photo-id name] :as team}]
  (if (nil? photo-id)
    (avatars/generate {:name name})
    (str (u/join public-uri "assets/by-id/" photo-id))))

(defn resolve-file-media
  ([media]
   (resolve-file-media media false))

  ([{:keys [id]} thumbnail?]
   (str (cond-> (u/join public-uri "assets/by-file-media-id/")
          (true? thumbnail?) (u/join (str id "/thumbnail"))
          (false? thumbnail?) (u/join (str id))))))


