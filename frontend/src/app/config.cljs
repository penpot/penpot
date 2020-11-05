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
   [app.util.object :as obj]
   [cuerdas.core :as str]))

(this-as global
  (def default-language "en")
  (def demo-warning     (obj/get global "appDemoWarning" false))
  (def google-client-id (obj/get global "appGoogleClientID" nil))
  (def gitlab-client-id (obj/get global "appGitlabClientID" nil))
  (def login-with-ldap  (obj/get global "appLoginWithLDAP" false))
  (def worker-uri       (obj/get global "appWorkerURI" "/js/worker.js"))
  (def public-uri       (or (obj/get global "appPublicURI")
                            (.-origin ^js js/location)))
  (def media-uri        (str public-uri "/media"))
  (def default-theme    "default"))

(defn resolve-media-path
  [path]
  (when path
    (if (str/starts-with? path "data:")
      path
      (str media-uri "/" path))))
