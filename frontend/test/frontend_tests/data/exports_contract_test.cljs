;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS SUBSIDIARY LTD

(ns frontend-tests.data.exports-contract-test
  "The payload of `create-export-assets-job` must match the shared
  contract (`app.common.export/schema:params`, closed): the backend
  rejects anything else with a params-validation error the user only
  sees as a failed export. These tests validate the items the callers
  build, so a UI flag cannot slip into the wire again."
  (:require
   [app.common.export :as export]
   [app.common.schema :as sm]
   [app.common.uuid :as uuid]
   [app.main.data.exports.assets :as de]
   [app.main.data.workspace.clipboard :as clipboard]
   [cljs.test :as t :include-macros true]))

(defn- valid?
  [params]
  (sm/validate export/schema:params params))

(defn- ids
  []
  [(uuid/next) (uuid/next) (uuid/next)])

(t/deftest image-export-matches-the-contract
  (let [[file-id page-id object-id] (ids)
        item (clipboard/image-export file-id page-id object-id)]
    (t/is (valid? {:exports [item]}))
    (t/testing "the name is filled from the object id, never left blank"
      (t/is (= (str object-id) (:name item))))
    (t/testing "and the clipboard flow sends exactly this item"
      (t/is (= :png (:type item)))
      (t/is (= 2 (:scale item))))))

(t/deftest a-ui-flag-is-not-part-of-the-contract
  (let [[file-id page-id object-id] (ids)
        item (assoc (clipboard/image-export file-id page-id object-id)
                    :enabled true)]
    (t/testing "the closed schema refuses the flag the dialog keeps"
      (t/is (not (valid? {:exports [item]}))))))

(t/deftest frames-items-match-the-contract
  (let [[file-id page-id object-id] (ids)
        item (de/normalize-export {:file-id   file-id
                                   :page-id   page-id
                                   :object-id object-id
                                   :type      :pdf
                                   :suffix    ""
                                   :scale     1
                                   :name      "Frame 1"})]
    (t/is (valid? {:exports [item] :kind :frames}))))
