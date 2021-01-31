;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; This Source Code Form is "Incompatible With Secondary Licenses", as
;; defined by the Mozilla Public License, v. 2.0.
;;
;; Copyright (c) 2020 UXBOX Labs SL

(ns app.tests.test-services-projects
  (:require
   [clojure.test :as t]
   [promesa.core :as p]
   [app.db :as db]
   [app.http :as http]
   [app.tests.helpers :as th]
   [app.common.uuid :as uuid]))

(t/use-fixtures :once th/state-init)
(t/use-fixtures :each th/database-reset)

(t/deftest projects-crud
  (let [prof       (th/create-profile* 1)
        team       (th/create-team* 1 {:profile-id (:id prof)})
        project-id (uuid/next)]

    ;; crate project
    (let [data {::th/type :create-project
                :id project-id
                :profile-id (:id prof)
                :team-id (:id team)
                :name "test project"}
          out  (th/mutation! data)]
        ;; (th/print-result! out)

      (t/is (nil? (:error out)))
      (let [result (:result out)]
        (t/is (= (:name data) (:name result)))))

    ;; query a list of projects
    (let [data {::th/type :projects
                :team-id (:id team)
                :profile-id (:id prof)}
          out  (th/query! data)]
      ;; (th/print-result! out)

      (t/is (nil? (:error out)))
      (let [result (:result out)]
        (t/is (= 1 (count result)))
        (t/is project-id (get-in result [0 :id]))
        (t/is (= "test project" (get-in result [0 :name])))))

    ;; rename project"
    (let [data {::th/type :rename-project
                :id project-id
                :name "renamed project"
                :profile-id (:id prof)}
          out  (th/mutation! data)]
      ;; (th/print-result! out)
      (t/is (nil? (:error out)))
      (t/is (nil? (:result out))))

    ;; retrieve project
    (let [data {::th/type :project
                :id project-id
                :profile-id (:id prof)}
          out  (th/query! data)]
      ;; (th/print-result! out)
      (t/is (nil? (:error out)))
      (let [result (:result out)]
        (t/is (= "renamed project" (:name result)))))

    ;; delete project
    (let [data {::th/type :delete-project
                :id project-id
                :profile-id (:id prof)}
          out  (th/mutation! data)]

        ;; (th/print-result! out)
      (t/is (nil? (:error out)))
      (t/is (nil? (:result out))))

    ;; query a list of projects after delete"
    (let [data {::th/type :projects
                :team-id (:id team)
                :profile-id (:id prof)}
          out (th/query! data)]
      ;; (th/print-result! out)
      (t/is (nil? (:error out)))
      (let [result (:result out)]
        (t/is (= 0 (count result)))))
  ))
