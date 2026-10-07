;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS SUBSIDIARY SL

(ns backend-tests.nitrate-test
  (:require
   [app.config :as cf]
   [app.nitrate]
   [clojure.test :as t]))

(def ^:private generate-nitrate-uri
  (ns-resolve 'app.nitrate 'generate-nitrate-uri))

(t/deftest generate-nitrate-uri-test
  (binding [cf/config (assoc cf/config
                             :admin-console-uri
                             "http://ac.example/admin-console")]
    (doseq [[label segments expected]
            [["single segment"
              ["api/connectivity"]
              "http://ac.example/admin-console/api/connectivity"]
             ["resource identifier as the final segment"
              ["api/teams/" "team-id"]
              "http://ac.example/admin-console/api/teams/team-id"]
             ["nested team membership path"
              ["api/teams/" "team-id/" "users/" "profile-id"]
              "http://ac.example/admin-console/api/teams/team-id/users/profile-id"]
             ["nested organization membership path"
              ["api/organizations/" "organization-id/" "members/" "profile-id"]
              "http://ac.example/admin-console/api/organizations/organization-id/members/profile-id"]]]
      (t/testing label
        (t/is (= expected (apply generate-nitrate-uri segments)))))))
