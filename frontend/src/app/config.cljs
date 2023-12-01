;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC

(ns app.config
  (:require
   [app.common.data.macros :as dm]
   [app.common.flags :as flags]
   [app.common.uri :as u]
   [app.common.version :as v]
   [app.util.avatars :as avatars]
   [app.util.globals :refer [global location]]
   [app.util.navigator :as nav]
   [app.util.object :as obj]
   [cuerdas.core :as str]))

(set! *assert* js/goog.DEBUG)

;; --- Auxiliar Functions

(def valid-browsers
  #{:chrome :firefox :safari :edge :other})

(def valid-platforms
  #{:windows :linux :macos :other})

(defn- parse-browser
  []
  (let [user-agent (-> (nav/get-user-agent) str/lower)
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
  (let [user-agent     (str/lower (nav/get-user-agent))
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

(def default-flags
  [:enable-onboarding
   :enable-onboarding-team
   :enable-onboarding-questions
   :enable-onboarding-newsletter
   :enable-dashboard-templates-section
   :enable-google-fonts-provider])

(defn- parse-flags
  [global]
  (let [flags (obj/get global "penpotFlags" "")
        flags (sequence (map keyword) (str/words flags))]
    (flags/parse flags/default default-flags flags)))

(defn- parse-version
  [global]
  (-> (obj/get global "penpotVersion")
      (v/parse)))

(defn parse-build-date
  [global]
  (let [date (obj/get global "penpotBuildDate")]
    (if (= date "%buildDate%")
      "unknown"
      date)))


;; --- Global Config Vars

(def default-theme  "default")
(def default-language "en")

(def translations         (obj/get global "penpotTranslations"))
(def themes               (obj/get global "penpotThemes"))

(def build-date           (parse-build-date global))
(def flags                (parse-flags global))
(def version              (parse-version global))
(def target               (parse-target global))
(def browser              (parse-browser))
(def platform             (parse-platform))

(def terms-of-service-uri (obj/get global "penpotTermsOfServiceURI" "https://penpot.app/terms"))
(def privacy-policy-uri   (obj/get global "penpotPrivacyPolicyURI" "https://penpot.app/privacy"))

(defn- normalize-uri
  [uri-str]
  (let [uri (u/uri uri-str)]
    ;; Ensure that the path always ends with "/"; this ensures that
    ;; all path join operations works as expected.
    (cond-> uri
      (not (str/ends-with? (:path uri) "/"))
      (update :path #(str % "/")))))

(def public-uri
  (normalize-uri (or (obj/get global "penpotPublicURI")
                     (obj/get location "origin"))))

(def rasterizer-uri
  (or (some-> (obj/get global "penpotRasterizerURI") normalize-uri)
      public-uri))

(def worker-uri
  (obj/get global "penpotWorkerURI" "/js/worker.js"))

;; --- Helper Functions

(defn ^boolean check-browser? [candidate]
  (dm/assert! (contains? valid-browsers candidate))
  (= candidate browser))

(defn ^boolean check-platform? [candidate]
  (dm/assert! (contains? valid-platforms candidate))
  (= candidate platform))

(defn resolve-profile-photo-url
  [{:keys [photo-id fullname name color] :as profile}]
  (if (nil? photo-id)
    (avatars/generate {:name (or fullname name) :color color})
    (dm/str (u/join public-uri "assets/by-id/" photo-id))))

(defn resolve-team-photo-url
  [{:keys [photo-id name] :as team}]
  (if (nil? photo-id)
    (avatars/generate {:name name})
    (dm/str (u/join public-uri "assets/by-id/" photo-id))))

(defn resolve-file-media
  ([media]
   (resolve-file-media media false))
  ([{:keys [id] :as media} thumbnail?]
   (dm/str
    (cond-> (u/join public-uri "assets/by-file-media-id/")
      (true? thumbnail?) (u/join (dm/str id "/thumbnail"))
      (false? thumbnail?) (u/join (dm/str id))))))
