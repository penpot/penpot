;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC

(ns backend-tests.auth-oidc-test
  (:require
   [app.auth.oidc :as oidc]
   [clojure.test :as t]))

(def ^:private oidc-provider
  {:id "oidc"
   :type "oidc"})

(t/deftest parse-attr-path-supports-dot-and-double-underscore
  (t/is
   (= [:oidc/resource-access :penpot_roles :roles]
      (#'oidc/parse-attr-path oidc-provider "resource_access__penpot_roles__roles")))
  (t/is
   (= [:oidc/ocs :data :email]
      (#'oidc/parse-attr-path oidc-provider "ocs.data.email"))))

(t/deftest process-user-info-supports-dot-notation-nested-attrs
  (let [provider (assoc oidc-provider
                        :email-attr "ocs.data.email"
                        :name-attr "ocs.data.display-name")
        info     (#'oidc/process-user-info provider
                                           {}
                                           {:email_verified true
                                            :ocs {:data {:email "nextcloud@example.com"
                                                         :display-name "Nextcloud User"}}})]
    (t/is (= "nextcloud@example.com" (:email info)))
    (t/is (= "Nextcloud User" (:fullname info)))
    (t/is (true? (:email-verified info)))))
