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
   [app.util.extends]
   [app.util.globals :refer [global location]]
   [app.util.navigator :as nav]
   [app.util.object :as obj]
   [cuerdas.core :as str]))

(set! *assert* js/goog.DEBUG)

;; --- Auxiliar Functions

(def valid-browsers
  #{:chrome :firefox :safari :safari-16 :safari-17 :edge :other})

(def valid-platforms
  #{:windows :linux :macos :other})

(defn- parse-browser
  []
  (let [user-agent (-> (nav/get-user-agent) str/lower)
        check-chrome? (fn [] (str/includes? user-agent "chrom"))
        check-firefox? (fn [] (str/includes? user-agent "firefox"))
        check-edge? (fn [] (str/includes? user-agent "edg"))
        check-safari? (fn [] (str/includes? user-agent "safari"))
        check-safari-16? (fn [] (and (check-safari?) (str/includes? user-agent "version/16")))
        check-safari-17? (fn [] (and (check-safari?) (str/includes? user-agent "version/17")))]
    (cond
      ^boolean (check-edge?)      :edge
      ^boolean (check-chrome?)    :chrome
      ^boolean (check-firefox?)   :firefox
      ^boolean (check-safari-16?) :safari-16
      ^boolean (check-safari-17?) :safari-17
      ^boolean (check-safari?)    :safari
      :else              :other)))

(defn- parse-platform
  []
  (let [user-agent     (str/lower (nav/get-user-agent))
        check-windows? (fn [] (str/includes? user-agent "windows"))
        check-linux?   (fn [] (str/includes? user-agent "linux"))
        check-macos?   (fn [] (str/includes? user-agent "mac os"))]
    (cond
      ^boolean (check-windows?) :windows
      ^boolean (check-linux?)   :linux
      ^boolean (check-macos?)   :macos
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

(defn parse-build-date
  [global]
  (let [date (obj/get global "penpotBuildDate")]
    (if (= date "%buildDate%")
      "unknown"
      date)))

;; --- Global Config Vars

(def default-theme  "default")
(def default-language "en")

(def themes               (obj/get global "penpotThemes"))

(def build-date           (parse-build-date global))
(def flags                (parse-flags global))
(def version              (parse-version global))
(def target               (parse-target global))
(def browser              (parse-browser))
(def platform             (parse-platform))

(def terms-of-service-uri (obj/get global "penpotTermsOfServiceURI"))
(def privacy-policy-uri   (obj/get global "penpotPrivacyPolicyURI"))
(def flex-help-uri        (obj/get global "penpotGridHelpURI" "https://help.penpot.app/user-guide/flexible-layouts/"))
(def grid-help-uri        (obj/get global "penpotGridHelpURI" "https://help.penpot.app/user-guide/flexible-layouts/"))
(def plugins-list-uri     (obj/get global "penpotPluginsListUri" "https://penpot.app/penpothub/plugins"))
(def plugins-whitelist    (into #{} (obj/get global "penpotPluginsWhitelist" [])))
(def templates-uri        (obj/get global "penpotTemplatesUri" "https://penpot.github.io/penpot-files/"))

;; We set the current parsed flags under common for make
;; it available for common code without the need to pass
;; the flags all arround on parameters.
(set! app.common.flags/*current* flags)

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
  (obj/get global "penpotWorkerURI" "/js/worker/main.js"))

(defn external-feature-flag
  [flag value]
  (let [f (obj/get global "externalFeatureFlag")]
    (when (fn? f)
      (f flag value))))

(defn external-session-id
  []
  (let [f (obj/get global "externalSessionId")]
    (when (fn? f) (f))))

(defn external-context-info
  []
  (let [f (obj/get global "externalContextInfo")]
    (when (fn? f) (f))))

(defn initialize-external-context-info
  []
  (let [f (obj/get global "initializeExternalConfigInfo")]
    (when (fn? f) (f))))

;; --- Helper Functions

(defn ^boolean check-browser? [candidate]
  (dm/assert! (contains? valid-browsers candidate))
  (if (= candidate :safari)
    (contains? #{:safari :safari-16 :safari-17} browser)
    (= candidate browser)))

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

(defn resolve-media
  [id]
  (dm/str (u/join public-uri "assets/by-id/" (str id))))

(defn resolve-file-media
  ([media]
   (resolve-file-media media false))
  ([{:keys [id data-uri] :as media} thumbnail?]
   (if data-uri
     data-uri
     (dm/str
      (cond-> (u/join public-uri "assets/by-file-media-id/")
        (true? thumbnail?) (u/join (dm/str id "/thumbnail"))
        (false? thumbnail?) (u/join (dm/str id)))))))

(defn resolve-href
  [resource]
  (let [version (get version :full)
        href    (-> public-uri
                    (u/ensure-path-slash)
                    (u/join resource)
                    (get :path))]
    (str href "?version=" version)))
